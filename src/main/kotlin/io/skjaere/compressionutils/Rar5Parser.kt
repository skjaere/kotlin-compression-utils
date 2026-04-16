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

        // RAR 5.x file flags (inside file header body)
        private const val RAR5_FILE_FLAG_ISDIR = 0x01

        // RAR 5.x block header flags (split indicators are in the block-level header, not file flags)
        private const val RAR5_BLOCK_FLAG_SPLIT_BEFORE = 0x08  // Data continues from previous volume
        private const val RAR5_BLOCK_FLAG_SPLIT_AFTER = 0x10   // Data continues in next volume
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
        var mainHeaderBlockSize = 0L
        var mainHeaderIsVolume = false
        var mainHeaderHasVolumeNumber = false
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
                // If we've inferred positions for a split file that fills all remaining volumes,
                // seek past the inferred data and resume parsing (there may be trailing headers).
                if (skipRemainingVolumes && inferredSplitParts != null) {
                    val lastPart = inferredSplitParts.last()
                    val seekPosition = lastPart.dataStartPosition + lastPart.dataSize
                    currentVolumeIndex = lastPart.volumeIndex
                    logger.debug("Seeking to $seekPosition after inferred split (volume $currentVolumeIndex)")
                    stream.seek(seekPosition)
                    skipRemainingVolumes = false
                    inferredSplitParts = null
                    foundEndArchive = false
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
            var dataAreaSizeVintLen = 0L
            if (headerFlags and 0x02L != 0L) { // Has data
                val dataSizeResult = readVInt(stream) ?: break
                dataAreaSize = dataSizeResult.first
                dataAreaSizeVintLen = dataSizeResult.second
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
                            // For uncompressed split files, try to infer remaining split positions
                            // from volume sizes to avoid parsing every continuation volume's headers.
                            // Only use the skip optimization when:
                            //  1. This is the first file (no prior files whose service blocks change layout)
                            //  2. The file fills ALL remaining volumes (single-file-per-archive pattern)
                            // Multi-file archives have varying service/end-of-archive sections per volume
                            // that make position inference unreliable.
                            if (isSplitAfter && volumeSizes != null && fileEntry.compressionMethod == 0 && entries.isEmpty()) {
                                val fixedOverheadVintBytes = headerBytesConsumed - dataAreaSizeVintLen
                                val inferredParts = inferSplitPositions(
                                    fileEntry = fileEntry,
                                    firstPartDataStart = dataStartPos,
                                    firstPartDataSize = dataAreaSize,
                                    currentVolumeIndex = currentVolumeIndex,
                                    volumeSizes = volumeSizes,
                                    mainHeaderBlockSize = mainHeaderBlockSize,
                                    fixedOverheadVintBytes = fixedOverheadVintBytes,
                                    fileBodySize = remainingHeaderSize,
                                    actualDataSizeVintLen = dataAreaSizeVintLen,
                                    mainHeaderIsVolume = mainHeaderIsVolume,
                                    mainHeaderHasVolumeNumber = mainHeaderHasVolumeNumber
                                )

                                val lastPart = inferredParts.last()
                                val fillsAllVolumes = lastPart.volumeIndex >= volumeSizes.size - 1

                                if (fillsAllVolumes) {
                                    // Clear CRC: RAR5 stores a running CRC per volume, and the
                                    // infer path skips continuation headers so we only have vol 0's
                                    // partial CRC, not the full file CRC from the last volume.
                                    val entryWithSplits = fileEntry.copy(splitParts = inferredParts, crc32 = null)
                                    entries.add(entryWithSplits)
                                    logger.debug("Found split file: ${fileEntry.path}, inferred ${inferredParts.size} parts (fills all volumes)")
                                    skipRemainingVolumes = true
                                    inferredSplitParts = inferredParts
                                } else {
                                    // File doesn't fill all volumes — other files coexist.
                                    // Fall back to normal parsing which reads actual headers.
                                    val entryWithSplits = fileEntry.copy(splitParts = fileSplitInfo[fileEntry.path]!!.toList())
                                    entries.add(entryWithSplits)
                                    logger.debug("Found split file: ${fileEntry.path}, ends at vol ${lastPart.volumeIndex} of ${volumeSizes.size}, using normal parsing")
                                }
                            } else {
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
                            // Update existing entry with accumulated split info (only when not using inferred positions)
                            if (!skipRemainingVolumes) {
                                val existingIndex = entries.indexOfFirst { it.path == fileEntry.path }
                                if (existingIndex >= 0) {
                                    // Also update CRC: RAR5 stores a running CRC in each volume's
                                    // header, so only the last volume has the full file CRC.
                                    val updatedCrc = fileEntry.crc32 ?: entries[existingIndex].crc32
                                    entries[existingIndex] =
                                        entries[existingIndex].copy(
                                            splitParts = fileSplitInfo[fileEntry.path]!!.toList(),
                                            crc32 = updatedCrc
                                        )
                                    logger.debug("Updated split info for: ${fileEntry.path}, parts=${fileSplitInfo[fileEntry.path]!!.size}, crc=${updatedCrc}")
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
                    // Read archive flags to detect volume number field
                    if (remainingHeaderSize > 0) {
                        val arcFlagsResult = readVInt(stream)
                        if (arcFlagsResult != null) {
                            val arcFlags = arcFlagsResult.first
                            mainHeaderIsVolume = (arcFlags and 0x01L) != 0L
                            mainHeaderHasVolumeNumber = (arcFlags and 0x02L) != 0L
                        }
                    }
                    logger.debug("Found main archive header at position $headerStartPosition, blockSize=$mainHeaderBlockSize, isVolume=$mainHeaderIsVolume, hasVolNum=$mainHeaderHasVolumeNumber")
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
            // Split flags are in the block-level header flags, not file-level flags
            val isSplitBefore = (headerFlags and RAR5_BLOCK_FLAG_SPLIT_BEFORE.toLong()) != 0L
            val isSplitAfter = (headerFlags and RAR5_BLOCK_FLAG_SPLIT_AFTER.toLong()) != 0L
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
     * Infers split positions for a store-mode file spanning multiple RAR5 volumes.
     *
     * Each continuation volume has: signature(8) + main header + file header + data + service + end-of-archive.
     *
     * The file header content is: fixedOverhead (type + flags + [extraAreaSize]) + dataAreaSizeVint + fileBody.
     * The fixedOverhead and fileBody are constant across volumes; only the dataAreaSize vint varies.
     *
     * Uses the observed dataAreaSize vint length from the first volume rather than computing minimal
     * encoding via vintLength(), since real RAR encoders may pad vints to a fixed width.
     * For the last (partial) volume, detects whether the encoder uses padded vints to choose the
     * correct vint length for the smaller data size.
     *
     * Only reliable when the file fills all remaining volumes (single-file-per-archive pattern).
     */
    private fun inferSplitPositions(
        fileEntry: RarFileEntry,
        firstPartDataStart: Long,
        firstPartDataSize: Long,
        currentVolumeIndex: Int,
        volumeSizes: List<Long>,
        mainHeaderBlockSize: Long,
        fixedOverheadVintBytes: Long,
        fileBodySize: Long,
        actualDataSizeVintLen: Long,
        mainHeaderIsVolume: Boolean,
        mainHeaderHasVolumeNumber: Boolean
    ): List<SplitInfo> {
        val parts = mutableListOf<SplitInfo>()
        var remainingBytes = fileEntry.uncompressedSize
        var cumulativeOffset = 0L

        for (i in 0 until currentVolumeIndex) {
            cumulativeOffset += volumeSizes[i]
        }

        // End-of-archive section size from this volume's layout
        val localDataStart = firstPartDataStart - cumulativeOffset
        val endOfArchiveSize = volumeSizes[currentVolumeIndex] - localDataStart - firstPartDataSize

        // Detect whether the encoder uses padded (non-minimal) vint encoding for dataAreaSize
        val isPaddedVint = actualDataSizeVintLen > vintLength(firstPartDataSize)

        for (volIdx in currentVolumeIndex until volumeSizes.size) {
            if (remainingBytes <= 0) break
            val volumeSize = volumeSizes[volIdx]

            if (volIdx == currentVolumeIndex) {
                val dataSize = minOf(remainingBytes, firstPartDataSize)
                parts.add(SplitInfo(volIdx, firstPartDataStart, dataSize))
                remainingBytes -= dataSize
            } else {
                // Continuation volumes may have a volume number vint that volume 0 lacks.
                val continuationMainHeaderExtra = if (mainHeaderIsVolume && !mainHeaderHasVolumeNumber) {
                    vintLength(volIdx.toLong())
                } else {
                    0L
                }
                val contMainHeaderBlockSize = mainHeaderBlockSize + continuationMainHeaderExtra

                // For the last volume in the archive, the end-of-archive section may differ
                // from volume 0 (e.g., different service block size, no zero padding).
                // Use remainingBytes directly instead of computing maxData from endOfArchiveSize.
                val isLastVolume = volIdx == volumeSizes.size - 1

                if (isLastVolume && remainingBytes > 0) {
                    val vintLen = if (isPaddedVint) actualDataSizeVintLen else vintLength(remainingBytes)
                    val headerBlockSize = computeHeaderBlockSize(fixedOverheadVintBytes, vintLen, fileBodySize)
                    val dataStartPosition = cumulativeOffset + 8 + contMainHeaderBlockSize + headerBlockSize
                    parts.add(SplitInfo(volIdx, dataStartPosition, remainingBytes))
                    remainingBytes = 0
                } else {
                    val totalSpace = volumeSize - 8 - contMainHeaderBlockSize - endOfArchiveSize

                    // Compute layout: header block size and available data, iterating to account
                    // for the circular dependency between data size and dataAreaSize vint length.
                    val (fileHeaderBlockSize, maxData) = computeContinuationLayout(
                        totalSpace, fixedOverheadVintBytes, fileBodySize,
                        actualDataSizeVintLen, isPaddedVint
                    )
                    val dataSize = minOf(remainingBytes, maxData)

                    val actualHeaderBlockSize = if (dataSize < maxData && !isPaddedVint) {
                        // Partial fill with minimal-vint encoder: recompute for actual data size
                        computeHeaderBlockSize(fixedOverheadVintBytes, vintLength(dataSize), fileBodySize)
                    } else {
                        fileHeaderBlockSize
                    }

                    val dataStartPosition = cumulativeOffset + 8 + contMainHeaderBlockSize + actualHeaderBlockSize
                    parts.add(SplitInfo(volIdx, dataStartPosition, dataSize))
                    remainingBytes -= dataSize
                }
            }

            cumulativeOffset += volumeSize
        }

        if (remainingBytes > 0) {
            logger.warn("Could not fit all file data: $remainingBytes bytes remaining after ${volumeSizes.size} volumes")
        }
        return parts
    }

    /**
     * Iteratively computes the file header block size and available data space for a continuation volume.
     * Handles the circular dependency: data size determines the dataAreaSize vint length,
     * which determines the header size, which determines available data space.
     */
    private fun computeContinuationLayout(
        totalSpace: Long,
        fixedOverhead: Long,
        fileBody: Long,
        startingVintLen: Long,
        isPaddedVint: Boolean
    ): Pair<Long, Long> {
        var dataSizeVintLen = startingVintLen
        var headerBlockSize = computeHeaderBlockSize(fixedOverhead, dataSizeVintLen, fileBody)
        var dataSize = totalSpace - headerBlockSize

        if (!isPaddedVint && dataSize > 0) {
            val actualVintLen = vintLength(dataSize)
            if (actualVintLen != dataSizeVintLen) {
                dataSizeVintLen = actualVintLen
                headerBlockSize = computeHeaderBlockSize(fixedOverhead, dataSizeVintLen, fileBody)
                dataSize = totalSpace - headerBlockSize
            }
        }
        return Pair(headerBlockSize, maxOf(0L, dataSize))
    }

    private fun computeHeaderBlockSize(fixedOverheadVintBytes: Long, dataSizeVintLen: Long, fileBodySize: Long): Long {
        val headerContentSize = fixedOverheadVintBytes + dataSizeVintLen + fileBodySize
        val headerSizeVintLen = vintLength(headerContentSize)
        return 4L + headerSizeVintLen + headerContentSize
    }

    private fun vintLength(value: Long): Long {
        if (value <= 0) return 1
        var v = value
        var len = 0L
        do {
            len++
            v = v ushr 7
        } while (v > 0)
        return len
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
