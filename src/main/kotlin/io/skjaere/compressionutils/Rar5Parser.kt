package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Rar5Parser {
    private val logger = LoggerFactory.getLogger(Rar5Parser::class.java)

    companion object {
        // RAR 5.x header types
        private const val RAR5_HEAD_MAIN = 1
        private const val RAR5_HEAD_FILE = 2
        private const val RAR5_HEAD_SERVICE = 3
        private const val RAR5_HEAD_ENDARC = 5

        // RAR 5.x file flags
        private const val RAR5_FILE_FLAG_ISDIR = 0x01
        private const val RAR5_FILE_FLAG_HAS_CRC = 0x04
        private const val RAR5_FILE_FLAG_SPLIT_BEFORE = 0x08  // File continues from previous volume
        private const val RAR5_FILE_FLAG_SPLIT_AFTER = 0x10   // File continues in next volume
    }

    suspend fun parse(
        stream: SeekableInputStream,
        entries: MutableList<RarFileEntry>,
        maxFiles: Int?,
        volumeIndex: Int,
        archiveSize: Long?,
        readBytes: suspend (SeekableInputStream, Int) -> ByteArray?,
        volumeSizes: List<Long>? = null
    ) {
        stream.seek(8) // Skip signature
        var foundEndArchive = false
        val seenFiles = mutableSetOf<String>() // Track files we've already added (multi-volume archives repeat headers)
        val fileSplitInfo = mutableMapOf<String, MutableList<SplitInfo>>() // Track split parts for each file
        var currentVolumeIndex = volumeIndex
        var mainHeaderBlockSize = 0L // Total size of main archive header block
        var skipRemainingVolumes = false
        var inferredSplitParts: List<SplitInfo>? = null

        while (true) {
            // Stop if we've reached the max file limit
            if (maxFiles != null && entries.size >= maxFiles) {
                logger.debug("Reached max files limit: $maxFiles")
                break
            }
            val headerStartPosition = stream.position()

            // After end-of-archive, check if we hit a new RAR signature (start of next volume)
            val headerSize: Long
            val headerSizeVintBytes: Long
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
                logger.debug("After end-of-archive, checking for RAR signature at position $headerStartPosition")
                val possibleSig = readBytes(stream, 8)
                if (possibleSig == null) {
                    logger.debug("End of stream after end-of-archive marker")
                    break // End of stream
                }

                logger.debug("Read 8 bytes: ${possibleSig.joinToString(" ") { "%02X".format(it) }}")

                val rar5Sig = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x01, 0x00)
                if (possibleSig.contentEquals(rar5Sig)) {
                    currentVolumeIndex++
                    logger.debug("Found RAR5 signature at position $headerStartPosition - continuing to volume $currentVolumeIndex")
                    foundEndArchive = false
                    continue // Skip signature and continue parsing next volume
                }

                // Not a full signature - check if it starts with zeros (padding) and then RAR signature
                val firstNonZero = possibleSig.indexOfFirst { it != 0.toByte() }

                if (firstNonZero == -1) {
                    // All zeros - padding
                    logger.debug("Found zero padding at position $headerStartPosition, continuing...")
                    continue // Skip padding and check next 8 bytes
                } else if (firstNonZero > 0) {
                    // Partial padding followed by start of signature
                    // We need to read the rest of the signature
                    val sigStart = possibleSig.sliceArray(firstNonZero until possibleSig.size)
                    val rar5SigStart = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x01, 0x00)

                    if (sigStart.size < rar5SigStart.size &&
                        rar5SigStart.sliceArray(0 until sigStart.size).contentEquals(sigStart)
                    ) {
                        // This looks like start of RAR signature after padding
                        logger.debug("Found partial RAR signature after padding at position $headerStartPosition")
                        // Read remaining signature bytes
                        val remaining = rar5SigStart.size - sigStart.size
                        val restOfSig = readBytes(stream, remaining)
                        if (restOfSig != null && rar5SigStart.sliceArray(sigStart.size until rar5SigStart.size)
                                .contentEquals(restOfSig)
                        ) {
                            currentVolumeIndex++
                            logger.debug("Confirmed RAR5 signature after $firstNonZero bytes of padding - continuing to volume $currentVolumeIndex")
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

                // Continue to read rest of header
            } else {
                // Normal header reading
                val headerCrc = readBytes(stream, 4) ?: break

                // Read header size (vint)
                val headerSizeResult = readVInt(stream) ?: break
                headerSize = headerSizeResult.first
                headerSizeVintBytes = headerSizeResult.second
            }

            // Read header type (vint)
            val headerTypeResult = readVInt(stream) ?: break
            val headerType = headerTypeResult.first

            // Read header flags (vint)
            val headerFlagsResult = readVInt(stream) ?: break
            val headerFlags = headerFlagsResult.first

            // Calculate bytes consumed so far from header size
            var headerBytesConsumed = headerTypeResult.second + headerFlagsResult.second

            // Read extra area size if present
            var extraAreaSize = 0L
            if (headerFlags and 0x01L != 0L) { // Has extra area
                val extraSizeResult = readVInt(stream) ?: break
                extraAreaSize = extraSizeResult.first
                headerBytesConsumed += extraSizeResult.second
            }

            // Read data area size if present
            var dataAreaSize = 0L
            if (headerFlags and 0x02L != 0L) { // Has data
                val dataSizeResult = readVInt(stream) ?: break
                dataAreaSize = dataSizeResult.first
                headerBytesConsumed += dataSizeResult.second
            }

            // Calculate remaining header size (header size includes everything after CRC)
            val remainingHeaderSize = headerSize - headerBytesConsumed

            if (remainingHeaderSize < 0) {
                logger.warn("Invalid header size: $headerSize, consumed: $headerBytesConsumed, remaining: $remainingHeaderSize")
                break
            }

            val headerDataPosition = stream.position()

            when (headerType.toInt()) {
                RAR5_HEAD_FILE -> {
                    val fileEntry =
                        parseFileHeader(
                            stream,
                            headerDataPosition,
                            headerFlags,
                            remainingHeaderSize,
                            currentVolumeIndex,
                            dataAreaSize,
                            readBytes
                        )
                    if (fileEntry != null) {
                        // Track split information
                        val dataStartPos = headerDataPosition + remainingHeaderSize
                        // For uncompressed files, if data area is smaller than total size, the file is split
                        val isSplitAfter = fileEntry.compressionMethod == 0 && dataAreaSize < fileEntry.uncompressedSize

                        val splitInfo = SplitInfo(
                            volumeIndex = currentVolumeIndex,
                            dataStartPosition = dataStartPos,
                            dataSize = dataAreaSize
                        )

                        fileSplitInfo.getOrPut(fileEntry.path) { mutableListOf() }.add(splitInfo)

                        // Only add if we haven't seen this file path before (multi-volume archives repeat headers)
                        if (seenFiles.add(fileEntry.path)) {
                            // Check if we can infer remaining split positions from volume sizes
                            if (isSplitAfter && volumeSizes != null && fileEntry.compressionMethod == 0) {
                                val fileHeaderBlockSize = 4L + headerSizeVintBytes + headerSize
                                val continuationHeaderSize = 8L + mainHeaderBlockSize + fileHeaderBlockSize
                                val inferredParts = inferSplitPositions(
                                    fileEntry = fileEntry,
                                    firstPartDataStart = dataStartPos,
                                    firstPartDataSize = dataAreaSize,
                                    currentVolumeIndex = currentVolumeIndex,
                                    volumeSizes = volumeSizes,
                                    continuationHeaderSize = continuationHeaderSize
                                )
                                val entryWithSplits = fileEntry.copy(splitParts = inferredParts)
                                entries.add(entryWithSplits)
                                logger.debug("Found split file: ${fileEntry.path}, inferred ${inferredParts.size} parts from volume sizes")

                                skipRemainingVolumes = true
                                inferredSplitParts = inferredParts
                            } else {
                                // For the first occurrence, add with current split info
                                val entryWithSplits = fileEntry.copy(splitParts = fileSplitInfo[fileEntry.path]!!.toList())
                                entries.add(entryWithSplits)
                                logger.debug("Found file: ${fileEntry.path} at position $headerStartPosition, split=${fileEntry.isSplit}")
                            }

                            // Optimization: if this single uncompressed file accounts for ~all of the archive,
                            // don't bother looking for more headers
                            // NOTE: Only apply to volume 0 - for multi-volume archives, the file may be larger than
                            // a single volume but split across many volumes
                            if (currentVolumeIndex == 0 && archiveSize != null && entries.size == 1 && fileEntry.compressionMethod == 0 && !isSplitAfter) {
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
                    }
                }

                RAR5_HEAD_ENDARC -> {
                    // End of archive marker - for concatenated multi-volume archives, continue to next volume
                    logger.debug("Found end-of-archive marker at position $headerStartPosition")
                    foundEndArchive = true
                    // Don't break - continue parsing to find headers in next volume
                }

                RAR5_HEAD_MAIN -> {
                    mainHeaderBlockSize = 4L + headerSizeVintBytes + headerSize + dataAreaSize
                    logger.debug("Found main archive header at position $headerStartPosition, blockSize=$mainHeaderBlockSize")
                }

                RAR5_HEAD_SERVICE -> {
                    logger.debug("Found service header at position $headerStartPosition")
                }

                else -> {
                    logger.debug("Found unknown header type $headerType at position $headerStartPosition")
                }
            }

            // Seek to next header (skip remaining header data + data area)
            val nextHeaderPosition = headerDataPosition + remainingHeaderSize + dataAreaSize
            stream.seek(nextHeaderPosition)
        }
    }

    private suspend fun parseFileHeader(
        stream: SeekableInputStream,
        headerDataPosition: Long,
        headerFlags: Long,
        remainingSize: Long,
        volumeIndex: Int,
        dataAreaSize: Long,
        readBytes: suspend (SeekableInputStream, Int) -> ByteArray?
    ): RarFileEntry? {
        val headerData = readBytes(stream, remainingSize.toInt()) ?: return null
        val buffer = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN)

        try {
            // Read file flags (vint)
            val fileFlags = readVIntFromBuffer(buffer) ?: return null

            // Read unpacked size (vint)
            val unpackedSize = readVIntFromBuffer(buffer) ?: return null

            // Read attributes (vint)
            val attributes = readVIntFromBuffer(buffer) ?: return null

            // Read modification time if present (4 bytes, optional)
            if (fileFlags and 0x02L != 0L) {
                if (buffer.remaining() < 4) return null
                buffer.getInt() // skip mtime
            }

            // Read CRC32 if present (4 bytes, optional)
            var fileCrc: Long? = null
            if (fileFlags and 0x04L != 0L) {
                if (buffer.remaining() < 4) return null
                fileCrc = buffer.getInt().toLong() and 0xFFFFFFFFL
            }

            // Read compression info (vint)
            val compressionInfo = readVIntFromBuffer(buffer) ?: return null
            // Lower 7 bits = compression method (0=store, 1-5=various compression)
            val compressionMethod = (compressionInfo and 0x7F).toInt()

            // Read host OS (vint)
            val hostOS = readVIntFromBuffer(buffer) ?: return null

            // Read name length (vint)
            val nameLength = readVIntFromBuffer(buffer) ?: return null

            // Read name
            if (buffer.remaining() < nameLength.toInt()) {
                logger.warn("Not enough data for filename: expected $nameLength, got ${buffer.remaining()}")
                return null
            }
            val nameBytes = ByteArray(nameLength.toInt())
            buffer.get(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)

            val isDirectory = (fileFlags and RAR5_FILE_FLAG_ISDIR.toLong()) != 0L
            val isSplitBefore = (fileFlags and RAR5_FILE_FLAG_SPLIT_BEFORE.toLong()) != 0L
            val isSplitAfter = (fileFlags and RAR5_FILE_FLAG_SPLIT_AFTER.toLong()) != 0L
            val isSplit = isSplitBefore || isSplitAfter

            if (isSplit) {
                logger.debug("File '$fileName' is split: before=$isSplitBefore, after=$isSplitAfter")
            }

            return RarFileEntry(
                path = fileName.replace('\\', '/'),
                uncompressedSize = unpackedSize,
                compressedSize = dataAreaSize, // Size of compressed data in this volume
                headerPosition = headerDataPosition,
                dataPosition = headerDataPosition + remainingSize,
                isDirectory = isDirectory,
                volumeIndex = volumeIndex,
                compressionMethod = compressionMethod,
                splitParts = emptyList(), // Will be populated by caller
                crc32 = fileCrc
            )
        } catch (e: Exception) {
            logger.error("Error parsing RAR5 file header", e)
            return null
        }
    }

    /**
     * Infers split positions for a file spanning multiple RAR5 volumes based on volume sizes.
     *
     * For uncompressed (store) files, the data is simply split across volumes. Each continuation
     * volume has a fixed structure:
     * - RAR5 signature (8 bytes) + main archive header + file header
     * - Data: file content
     * - End-of-archive marker
     *
     * @param fileEntry The file entry parsed from the first volume
     * @param firstPartDataStart Absolute position where data starts in the first part
     * @param firstPartDataSize Size of data in the first part
     * @param currentVolumeIndex Index of the current volume (where this file starts)
     * @param volumeSizes List of all volume sizes
     * @param continuationHeaderSize Combined header size for continuation volumes
     *        (RAR5 signature + main header block + file header block)
     * @return List of SplitInfo for all parts of this file
     */
    private fun inferSplitPositions(
        fileEntry: RarFileEntry,
        firstPartDataStart: Long,
        firstPartDataSize: Long,
        currentVolumeIndex: Int,
        volumeSizes: List<Long>,
        continuationHeaderSize: Long
    ): List<SplitInfo> {
        val parts = mutableListOf<SplitInfo>()
        var remainingBytes = fileEntry.uncompressedSize
        var cumulativeOffset = 0L

        // Calculate cumulative offset up to current volume
        for (i in 0 until currentVolumeIndex) {
            cumulativeOffset += volumeSizes[i]
        }

        // Calculate actual end-of-archive size from the current volume's layout
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
            logger.debug("Inferred RAR5 part $volIdx: start=$dataStartPosition, size=$dataSize, remaining=${remainingBytes - dataSize}")

            remainingBytes -= dataSize
            cumulativeOffset += volumeSize
        }

        if (remainingBytes > 0) {
            logger.warn("Could not fit all file data: $remainingBytes bytes remaining after ${volumeSizes.size} volumes")
        }

        return parts
    }

    private suspend fun readVInt(stream: SeekableInputStream): Pair<Long, Long>? {
        var value = 0L
        var bytesRead = 0L

        while (bytesRead < 10) { // Max 10 bytes for vint
            val byte = stream.read()
            if (byte == -1) return null
            bytesRead++

            value = value or ((byte and 0x7F).toLong() shl ((bytesRead - 1) * 7).toInt())

            if (byte and 0x80 == 0) {
                return Pair(value, bytesRead)
            }
        }

        return null
    }

    private fun readVIntFromBuffer(buffer: ByteBuffer): Long? {
        var value = 0L
        var bytesRead = 0

        while (bytesRead < 10 && buffer.hasRemaining()) {
            val byte = buffer.get().toInt() and 0xFF
            bytesRead++

            value = value or ((byte and 0x7F).toLong() shl ((bytesRead - 1) * 7))

            if (byte and 0x80 == 0) {
                return value
            }
        }

        return null
    }
}
