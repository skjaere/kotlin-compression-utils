package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Rar4Parser {
    private val logger = LoggerFactory.getLogger(Rar4Parser::class.java)

    companion object {
        // RAR 4.x file flags (from block flags)
        private const val RAR4_FILE_FLAG_SPLIT_BEFORE = 0x01  // File continues from previous volume
        private const val RAR4_FILE_FLAG_SPLIT_AFTER = 0x02   // File continues in next volume
        private const val RAR4_FILE_FLAG_LARGE_FILE =
            0x0100  // 64-bit file sizes (adds HIGH_PACK_SIZE and HIGH_UNPACK_SIZE fields)

        // Fixed sizes for RAR4 structure
        private const val END_OF_ARCHIVE_SIZE = 7L
        private const val RAR4_SIGNATURE_SIZE = 7

        /**
         * Extracts metadata for all files from a RAR4 archive by parsing only the first volume.
         * The returned metadata can be stored and later used with [calculateSplitParts] to
         * compute byte positions without re-reading the stream.
         *
         * @param stream SeekableInputStream positioned at the start of the archive
         * @param maxFiles Maximum number of files to extract metadata for (null for all)
         * @return List of metadata for each file found in the first volume
         */
        fun extractMetadata(
            stream: SeekableInputStream,
            maxFiles: Int? = null
        ): List<Rar4FileMetadata> {
            val results = mutableListOf<Rar4FileMetadata>()

            // Skip RAR signature
            stream.seek(RAR4_SIGNATURE_SIZE.toLong())

            while (true) {
                if (maxFiles != null && results.size >= maxFiles) break

                val blockStartPosition = stream.position()

                // Read block header (7 bytes minimum)
                val headerBytes = ByteArray(7)
                if (stream.read(headerBytes, 0, 7) != 7) break

                val blockType = headerBytes[2].toInt() and 0xFF
                val blockFlags = ((headerBytes[4].toInt() and 0xFF) shl 8) or (headerBytes[3].toInt() and 0xFF)
                val blockSize = ((headerBytes[6].toInt() and 0xFF) shl 8) or (headerBytes[5].toInt() and 0xFF)

                val headerDataPosition = stream.position()

                when (blockType) {
                    0x74 -> { // File header
                        val metadata = parseFileMetadata(stream, headerDataPosition, blockSize - 7, blockFlags)
                        if (metadata != null) {
                            results.add(metadata)
                            // Seek past data
                            stream.seek(metadata.firstPartDataStart + metadata.firstPartDataSize)
                        } else {
                            // Skip this block
                            stream.seek(headerDataPosition + (blockSize - 7))
                        }
                    }

                    0x7B -> { // End of archive - stop parsing first volume
                        break
                    }

                    else -> {
                        // Skip other block types
                        stream.seek(headerDataPosition + (blockSize - 7))
                    }
                }
            }

            return results
        }

        private fun parseFileMetadata(
            stream: SeekableInputStream,
            headerDataPosition: Long,
            headerDataSize: Int,
            flags: Int
        ): Rar4FileMetadata? {
            val headerData = ByteArray(headerDataSize)
            if (stream.read(headerData, 0, headerDataSize) != headerDataSize) return null

            val buffer = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN)

            // Read pack size (4 bytes)
            if (buffer.remaining() < 4) return null
            val packSize = buffer.getInt().toLong() and 0xFFFFFFFFL

            // Read unpack size (4 bytes)
            if (buffer.remaining() < 4) return null
            val unpackSize = buffer.getInt().toLong() and 0xFFFFFFFFL

            // Skip host OS (1 byte), file CRC (4 bytes), file time (4 bytes), unpack version (1 byte)
            if (buffer.remaining() < 10) return null
            buffer.position(buffer.position() + 10)

            // Read method (1 byte)
            if (buffer.remaining() < 1) return null
            val method = buffer.get().toInt() and 0xFF
            val compressionMethod = if (method == 0x30) 0 else method - 0x30

            // Read name length (2 bytes)
            if (buffer.remaining() < 2) return null
            val nameLength = buffer.getShort().toInt() and 0xFFFF

            // Skip attributes (4 bytes)
            if (buffer.remaining() < 4) return null
            buffer.position(buffer.position() + 4)

            // Handle large file sizes
            var fullPackSize = packSize
            var fullUnpackSize = unpackSize
            if ((flags and RAR4_FILE_FLAG_LARGE_FILE) != 0) {
                if (buffer.remaining() < 8) return null
                val highPackSize = buffer.getInt().toLong() and 0xFFFFFFFFL
                val highUnpackSize = buffer.getInt().toLong() and 0xFFFFFFFFL
                fullPackSize = (highPackSize shl 32) or packSize
                fullUnpackSize = (highUnpackSize shl 32) or unpackSize
            }

            // Read filename
            if (buffer.remaining() < nameLength) return null
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            val filename = String(nameBytes, Charsets.UTF_8)

            val isSplitAfter = (flags and RAR4_FILE_FLAG_SPLIT_AFTER) != 0
            val isSplitBefore = (flags and RAR4_FILE_FLAG_SPLIT_BEFORE) != 0
            val isLargeFile = fullUnpackSize > 0xFFFFFFFFL

            val dataStartPosition = headerDataPosition + headerDataSize
            val continuationHeaderSize = calculateContinuationHeaderSize(nameLength, isLargeFile)

            return Rar4FileMetadata(
                filename = filename,
                uncompressedSize = fullUnpackSize,
                firstPartDataStart = dataStartPosition,
                firstPartDataSize = fullPackSize,
                continuationHeaderSize = continuationHeaderSize,
                isSplit = isSplitAfter || isSplitBefore,
                compressionMethod = compressionMethod
            )
        }

        /**
         * Calculates the continuation header size for a RAR4 multi-volume archive.
         * This is the fixed header size for all volumes after the first one.
         *
         * @param filenameByteLength Length of the filename in bytes (UTF-8 encoded)
         * @param isLargeFile Whether the file is >4GB (requires extra 8-byte size fields)
         * @return Header size in bytes
         */
        fun calculateContinuationHeaderSize(filenameByteLength: Int, isLargeFile: Boolean): Long {
            // RAR signature (7) + archive header (13) + file header base (7) + fixed fields (25) + filename + large file fields
            return 52L + filenameByteLength + (if (isLargeFile) 8L else 0L)
        }

        /**
         * Calculates split parts for a RAR4 multi-volume archive from stored metadata.
         * No stream reading required - uses pre-computed values.
         *
         * @param uncompressedSize Total uncompressed file size
         * @param firstPartDataStart Absolute byte position where data starts in the first volume
         * @param firstPartDataSize Size of file data in the first volume
         * @param continuationHeaderSize Header size for continuation volumes (use [calculateContinuationHeaderSize])
         * @param volumeSizes List of all volume sizes in order
         * @return List of SplitInfo describing each part's position and size
         */
        fun calculateSplitParts(
            uncompressedSize: Long,
            firstPartDataStart: Long,
            firstPartDataSize: Long,
            continuationHeaderSize: Long,
            volumeSizes: List<Long>
        ): List<SplitInfo> {
            val parts = mutableListOf<SplitInfo>()
            var remainingBytes = uncompressedSize
            var cumulativeOffset = 0L

            for (volIdx in volumeSizes.indices) {
                if (remainingBytes <= 0) break

                val volumeSize = volumeSizes[volIdx]
                val dataStartPosition: Long
                val availableDataSpace: Long

                if (volIdx == 0) {
                    // First volume: use provided values
                    dataStartPosition = firstPartDataStart
                    availableDataSpace = firstPartDataSize
                } else {
                    // Continuation volumes: fixed header + data + fixed footer
                    dataStartPosition = cumulativeOffset + continuationHeaderSize
                    availableDataSpace = maxOf(0L, volumeSize - continuationHeaderSize - END_OF_ARCHIVE_SIZE)
                }

                val dataSize = minOf(remainingBytes, availableDataSpace)

                parts.add(
                    SplitInfo(
                        volumeIndex = volIdx,
                        dataStartPosition = dataStartPosition,
                        dataSize = dataSize
                    )
                )

                remainingBytes -= dataSize
                cumulativeOffset += volumeSize
            }

            return parts
        }
    }

    /**
     * Parse RAR4 archive to extract file entries and their byte positions.
     *
     * @param stream Seekable stream representing all volumes concatenated together
     * @param entries Output list to populate with parsed file entries
     * @param maxFiles Maximum number of files to parse (null for unlimited)
     * @param volumeIndex Starting volume index (typically 0)
     * @param archiveSize Total size of all volumes combined (used for single-file optimization)
     * @param readBytes Function to read bytes from the stream
     * @param volumeSizes Optional list of individual volume sizes. When provided, enables optimization
     *                    for split files: byte positions are inferred from volume sizes instead of
     *                    parsing all volume headers. Only works for uncompressed (store) files.
     */
    fun parse(
        stream: SeekableInputStream,
        entries: MutableList<RarFileEntry>,
        maxFiles: Int?,
        volumeIndex: Int,
        archiveSize: Long?,
        readBytes: (SeekableInputStream, Int) -> ByteArray?,
        volumeSizes: List<Long>? = null
    ) {
        stream.seek(7) // Skip signature
        var foundEndArchive = false
        val seenFiles = mutableSetOf<String>() // Track files we've already added (multi-volume archives repeat headers)
        val fileSplitInfo = mutableMapOf<String, MutableList<SplitInfo>>() // Track split parts for each file
        var currentVolumeIndex = volumeIndex
        var skipRemainingVolumes = false // Set to true when we've inferred positions and can skip volume-by-volume parsing
        var inferredSplitParts: List<SplitInfo>? = null // Stores inferred split parts so we can seek past them

        while (true) {
            // Stop if we've reached the max file limit
            if (maxFiles != null && entries.size >= maxFiles) {
                logger.debug("Reached max files limit: $maxFiles")
                break
            }

            val blockStartPosition = stream.position()

            // After end-of-archive, check if we hit a new RAR signature (start of next volume)
            if (foundEndArchive) {
                // If we've inferred positions for split files, seek past the inferred data and continue parsing
                if (skipRemainingVolumes && inferredSplitParts != null) {
                    val lastPart = inferredSplitParts.last()
                    val seekPosition = lastPart.dataStartPosition + lastPart.dataSize
                    currentVolumeIndex = lastPart.volumeIndex
                    stream.seek(seekPosition)
                    skipRemainingVolumes = false
                    inferredSplitParts = null
                    foundEndArchive = false
                    logger.debug("Skipped to end of inferred split data at position $seekPosition (volume $currentVolumeIndex), continuing to parse remaining files")
                    continue
                }
                logger.debug("After end-of-archive, checking for RAR signature at position $blockStartPosition")
                val possibleSig = readBytes(stream, 7)
                if (possibleSig == null) {
                    logger.debug("End of stream after end-of-archive marker")
                    break // End of stream
                }

                logger.debug("Read 7 bytes: ${possibleSig.joinToString(" ") { "%02X".format(it) }}")

                val rar4Sig = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x00)
                if (possibleSig.contentEquals(rar4Sig)) {
                    currentVolumeIndex++
                    logger.debug("Found RAR4 signature at position $blockStartPosition - continuing to volume $currentVolumeIndex")
                    foundEndArchive = false
                    continue // Skip signature and continue parsing next volume
                }

                // Not a full signature - check if it starts with zeros (padding) and then RAR signature
                val firstNonZero = possibleSig.indexOfFirst { it != 0.toByte() }

                if (firstNonZero == -1) {
                    // All zeros - padding
                    logger.debug("Found zero padding at position $blockStartPosition, continuing...")
                    continue // Skip padding and check next 7 bytes
                } else if (firstNonZero > 0) {
                    // Partial padding followed by start of signature
                    val sigStart = possibleSig.sliceArray(firstNonZero until possibleSig.size)
                    val rar4SigStart = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x00)

                    if (sigStart.size < rar4SigStart.size &&
                        rar4SigStart.sliceArray(0 until sigStart.size).contentEquals(sigStart)
                    ) {
                        // This looks like start of RAR signature after padding
                        logger.debug("Found partial RAR signature after padding at position $blockStartPosition")
                        // Read remaining signature bytes
                        val remaining = rar4SigStart.size - sigStart.size
                        val restOfSig = readBytes(stream, remaining)
                        if (restOfSig != null && rar4SigStart.sliceArray(sigStart.size until rar4SigStart.size)
                                .contentEquals(restOfSig)
                        ) {
                            currentVolumeIndex++
                            logger.debug("Confirmed RAR4 signature after $firstNonZero bytes of padding - continuing to volume $currentVolumeIndex")
                            foundEndArchive = false
                            continue
                        }
                    }
                }

                logger.warn(
                    "Expected RAR signature or padding after end-of-archive, got: ${
                        possibleSig.joinToString(" ") {
                            "%02X".format(
                                it
                            )
                        }
                    }"
                )
                break
            }

            // Read block header (7 bytes minimum)
            val headerCrc = readBytes(stream, 2) ?: break
            val headerType = readBytes(stream, 1) ?: break
            val headerFlags = readBytes(stream, 2) ?: break
            val headerSize = readBytes(stream, 2) ?: break

            val blockType = headerType[0].toInt() and 0xFF
            val blockFlags = ((headerFlags[1].toInt() and 0xFF) shl 8) or (headerFlags[0].toInt() and 0xFF)
            val blockSize = ((headerSize[1].toInt() and 0xFF) shl 8) or (headerSize[0].toInt() and 0xFF)

            val headerDataPosition = stream.position()

            if (blockType == 0x74) { // File header
                val fileEntry = parseFileHeader(
                    stream,
                    headerDataPosition,
                    blockSize - 7,
                    blockFlags,
                    currentVolumeIndex,
                    readBytes
                )
                if (fileEntry != null) {
                    val hasSplitAfter = (blockFlags and RAR4_FILE_FLAG_SPLIT_AFTER) != 0

                    // Track split information
                    val dataStartPos = headerDataPosition + (blockSize - 7)

                    val splitInfo = SplitInfo(
                        volumeIndex = currentVolumeIndex,
                        dataStartPosition = dataStartPos,
                        dataSize = fileEntry.compressedSize
                    )

                    fileSplitInfo.getOrPut(fileEntry.path) { mutableListOf() }.add(splitInfo)

                    // Only add if we haven't seen this file path before (multi-volume archives repeat headers)
                    if (seenFiles.add(fileEntry.path)) {
                        // Check if we can infer remaining split positions from volume sizes
                        if (hasSplitAfter && volumeSizes != null && fileEntry.compressionMethod == 0) {
                            val inferredParts = inferSplitPositions(
                                fileEntry = fileEntry,
                                firstPartDataStart = dataStartPos,
                                firstPartDataSize = fileEntry.compressedSize,
                                currentVolumeIndex = currentVolumeIndex,
                                volumeSizes = volumeSizes,
                                fileHeaderBlockSize = blockSize
                            )
                            val entryWithSplits = fileEntry.copy(splitParts = inferredParts)
                            entries.add(entryWithSplits)
                            logger.debug("Found split file: ${fileEntry.path}, inferred ${inferredParts.size} parts from volume sizes")

                            // Mark that we should seek past inferred volumes instead of parsing them one by one
                            skipRemainingVolumes = true
                            inferredSplitParts = inferredParts
                        } else {
                            // For the first occurrence, add with current split info
                            val entryWithSplits = fileEntry.copy(splitParts = fileSplitInfo[fileEntry.path]!!.toList())
                            entries.add(entryWithSplits)
                            logger.debug("Found file: ${fileEntry.path} at position $blockStartPosition, split=${fileEntry.isSplit}")
                        }

                        // Optimization: if this single uncompressed file accounts for ~all of the archive,
                        // don't bother looking for more headers
                        // NOTE: Only apply to volume 0
                        if (currentVolumeIndex == 0 && archiveSize != null && entries.size == 1 && fileEntry.compressionMethod == 0 && !hasSplitAfter) {
                            val expectedDataSize = archiveSize * 0.95
                            if (fileEntry.uncompressedSize >= expectedDataSize) {
                                logger.debug("Single file accounts for entire volume, stopping parse")
                                break
                            }
                        }
                    } else {
                        // Update existing entry with accumulated split info (only if not using inferred positions)
                        if (!skipRemainingVolumes) {
                            val existingIndex = entries.indexOfFirst { it.path == fileEntry.path }
                            if (existingIndex >= 0) {
                                entries[existingIndex] =
                                    entries[existingIndex].copy(splitParts = fileSplitInfo[fileEntry.path]!!.toList())
                                logger.debug("Updated split info for: ${fileEntry.path}, parts=${fileSplitInfo[fileEntry.path]!!.size}")
                            }
                        }
                    }

                    // Seek past header and data
                    stream.seek(headerDataPosition + (blockSize - 7) + fileEntry.compressedSize)
                } else {
                    // Skip this block
                    stream.seek(headerDataPosition + (blockSize - 7))
                }
            } else if (blockType == 0x7B) { // End of archive
                logger.debug("Found end-of-archive marker at position $blockStartPosition")
                foundEndArchive = true
                // Don't break - continue parsing to find headers in next volume
                // Seek past this block first
                stream.seek(headerDataPosition + (blockSize - 7))
            } else {
                // Skip other block types
                logger.debug("Found block type 0x${blockType.toString(16)} at position $blockStartPosition")
                stream.seek(headerDataPosition + (blockSize - 7))
            }
        }
    }

    /**
     * Infers split positions for a file spanning multiple volumes based on volume sizes.
     *
     * For uncompressed (store) files, the data is simply split across volumes. Each continuation
     * volume has a fixed structure:
     * - Header: RAR signature (7) + archive header (13) + file header (32 + filename + optional 8) = 52 + filename + [8]
     * - Data: file content
     * - Footer: end-of-archive marker (7 bytes, fixed)
     *
     * @param fileEntry The file entry parsed from the first volume
     * @param firstPartDataStart Absolute position where data starts in the first part
     * @param firstPartDataSize Size of data in the first part (from header)
     * @param currentVolumeIndex Index of the current volume (where this file starts)
     * @param volumeSizes List of all volume sizes
     * @return List of SplitInfo for all parts of this file
     */
    private fun inferSplitPositions(
        fileEntry: RarFileEntry,
        firstPartDataStart: Long,
        firstPartDataSize: Long,
        currentVolumeIndex: Int,
        volumeSizes: List<Long>,
        fileHeaderBlockSize: Int
    ): List<SplitInfo> {
        val parts = mutableListOf<SplitInfo>()
        var remainingBytes = fileEntry.uncompressedSize
        var cumulativeOffset = 0L

        // Calculate cumulative offset up to current volume
        for (i in 0 until currentVolumeIndex) {
            cumulativeOffset += volumeSizes[i]
        }

        // Continuation header size: RAR signature (7) + archive header (13) + file header block
        // The file header block size is the same in all continuation volumes (same filename,
        // same flags layout, same optional fields like EXTTIME).
        val continuationHeaderSize = 7L + 13L + fileHeaderBlockSize

        // Calculate actual end-of-archive size from the current volume's layout
        // The data start position within this volume = firstPartDataStart - cumulativeOffset
        // footer_size = volume_size - local_data_start - data_size
        val localDataStart = firstPartDataStart - cumulativeOffset
        val firstVolumeSize = volumeSizes[currentVolumeIndex]
        val endOfArchiveSize = firstVolumeSize - localDataStart - firstPartDataSize

        for (volIdx in currentVolumeIndex until volumeSizes.size) {
            if (remainingBytes <= 0) break

            val volumeSize = volumeSizes[volIdx]
            val dataStartPosition: Long
            val availableDataSpace: Long

            if (volIdx == currentVolumeIndex) {
                // First part: use actual parsed position and size
                dataStartPosition = firstPartDataStart
                availableDataSpace = firstPartDataSize
            } else {
                // Continuation volumes: fixed header size + data + fixed footer
                dataStartPosition = cumulativeOffset + continuationHeaderSize
                availableDataSpace = maxOf(0L, volumeSize - continuationHeaderSize - endOfArchiveSize)
            }

            val dataSize = minOf(remainingBytes, availableDataSpace)

            parts.add(
                SplitInfo(
                    volumeIndex = volIdx,
                    dataStartPosition = dataStartPosition,
                    dataSize = dataSize
                )
            )
            logger.debug("Inferred part $volIdx: start=$dataStartPosition, size=$dataSize, remaining=${remainingBytes - dataSize}")

            remainingBytes -= dataSize
            cumulativeOffset += volumeSize
        }

        if (remainingBytes > 0) {
            logger.warn("Could not fit all file data: $remainingBytes bytes remaining after ${volumeSizes.size} volumes")
        }

        return parts
    }

    private fun parseFileHeader(
        stream: SeekableInputStream,
        headerDataPosition: Long,
        headerDataSize: Int,
        flags: Int,
        volumeIndex: Int,
        readBytes: (SeekableInputStream, Int) -> ByteArray?
    ): RarFileEntry? {
        val headerData = readBytes(stream, headerDataSize) ?: return null
        val buffer = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN)

        // Read pack size (4 bytes)
        if (buffer.remaining() < 4) return null
        val packSize = buffer.getInt().toLong() and 0xFFFFFFFFL

        // Read unpack size (4 bytes)
        if (buffer.remaining() < 4) return null
        val unpackSize = buffer.getInt().toLong() and 0xFFFFFFFFL

        // Read host OS (1 byte)
        if (buffer.remaining() < 1) return null
        val hostOS = buffer.get()

        // Read file CRC (4 bytes)
        if (buffer.remaining() < 4) return null
        val fileCrc = buffer.getInt()

        // Read file time (4 bytes)
        if (buffer.remaining() < 4) return null
        val fileTime = buffer.getInt()

        // Read unpack version (1 byte)
        if (buffer.remaining() < 1) return null
        val unpackVersion = buffer.get()

        // Read method (1 byte)
        if (buffer.remaining() < 1) return null
        val method = buffer.get().toInt() and 0xFF
        // RAR4: 0x30=store (uncompressed), 0x31-0x35=various compression levels
        val compressionMethod = if (method == 0x30) 0 else method - 0x30

        // Read name length (2 bytes)
        if (buffer.remaining() < 2) return null
        val nameLength = buffer.getShort().toInt() and 0xFFFF

        // Read attributes (4 bytes)
        if (buffer.remaining() < 4) return null
        val attributes = buffer.getInt()

        // If LARGE_FILE flag is set, read high 32-bit size fields (for files >4GB)
        var fullPackSize = packSize
        var fullUnpackSize = unpackSize
        if ((flags and RAR4_FILE_FLAG_LARGE_FILE) != 0) {
            if (buffer.remaining() < 8) return null
            val highPackSize = buffer.getInt().toLong() and 0xFFFFFFFFL
            val highUnpackSize = buffer.getInt().toLong() and 0xFFFFFFFFL
            fullPackSize = (highPackSize shl 32) or packSize
            fullUnpackSize = (highUnpackSize shl 32) or unpackSize
            logger.debug("LARGE_FILE: highPack=$highPackSize, highUnpack=$highUnpackSize, fullPack=$fullPackSize, fullUnpack=$fullUnpackSize")
        }

        // Read name
        if (buffer.remaining() < nameLength) return null
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        val fileName = String(nameBytes, Charsets.UTF_8)

        val isDirectory = (flags and 0xE0) == 0xE0 // Directory flag
        val isSplitBefore = (flags and RAR4_FILE_FLAG_SPLIT_BEFORE) != 0
        val isSplitAfter = (flags and RAR4_FILE_FLAG_SPLIT_AFTER) != 0
        val isSplit = isSplitBefore || isSplitAfter

        if (isSplit) {
            logger.debug("File '$fileName' is split: before=$isSplitBefore, after=$isSplitAfter")
        }

        return RarFileEntry(
            path = fileName,
            uncompressedSize = fullUnpackSize,
            compressedSize = fullPackSize,
            headerPosition = headerDataPosition,
            dataPosition = headerDataPosition + headerDataSize,
            isDirectory = isDirectory,
            volumeIndex = volumeIndex,
            compressionMethod = compressionMethod,
            splitParts = emptyList() // Will be populated by caller
        )
    }
}
