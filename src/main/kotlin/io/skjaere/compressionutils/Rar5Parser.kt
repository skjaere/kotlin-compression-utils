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
        private const val RAR5_HEAD_CRYPT = 4
        private const val RAR5_HEAD_ENDARC = 5

        // RAR 5.x HEAD_CRYPT body field offsets (within the block's body, after the
        // block header). See rar5tech.txt §4 (encryption block).
        private const val RAR5_CRYPT_SALT_SIZE = 16
        private const val RAR5_CRYPT_PWCHECK_SIZE = 8

        // Volume-boundary tolerance: when scanning for the next volume's RAR signature
        // after end-of-archive, if we're within this many bytes of the stream end and
        // the bytes don't match a signature/padding, treat as clean EOF rather than
        // throwing MalformedRarArchive. Encrypted RAR5 volumes commonly have a trailing
        // tail of encryption padding that decrypts to non-zero bytes.
        private const val END_OF_STREAM_TOLERANCE = 256

        // RAR 5.x file flags (inside file header body)
        private const val RAR5_FILE_FLAG_ISDIR = 0x01

        // File header extra-area record types (rar5tech.txt §4.3)
        private const val FILE_EXTRA_TYPE_ENCRYPTION = 0x01

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
        volumeSizes: List<Long>? = null,
        archiveName: String? = null,
        // Optional password. When supplied AND the archive contains a `HEAD_CRYPT`
        // block, we derive the AES key via PBKDF2 and validate by decrypting the
        // next block's CRC. Wrong passwords still throw `EncryptedRarArchiveException`
        // (caught by the public boundary and converted to
        // [ListFilesResult.Encrypted] with `passwordIncorrect=true`).
        password: String? = null,
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

                // Bytes after end-of-archive aren't padding, aren't a continuation
                // signature. Two cases:
                //   1. Trailing bytes near EOF — common in encrypted RAR5 volumes
                //      where the last block is padded out to volume boundary with
                //      what decrypts to non-zero garbage. Treat as clean end of
                //      stream; we've parsed everything we could.
                //   2. Real corruption mid-stream — bytes appear well before EOF.
                //      That's a genuine malformed archive; throw.
                val streamSize = stream.size()
                if (streamSize >= 0 && stream.position() + END_OF_STREAM_TOLERANCE >= streamSize) {
                    logger.debug(
                        "Bytes after end-of-archive at position {} are within {} of EOF " +
                            "(stream size {}); treating as clean end of stream",
                        headerStartPosition, END_OF_STREAM_TOLERANCE, streamSize,
                    )
                    break
                }
                val sigHex = possibleSig.joinToString(" ") { "%02X".format(it) }
                throw MalformedRarArchiveException(
                    archiveName = archiveName,
                    volumeIndex = currentVolumeIndex,
                    unexpectedBytes = possibleSig,
                    message = "Expected RAR signature or padding after end-of-archive " +
                            "in '${archiveName ?: "<unknown>"}' (volume $currentVolumeIndex), got: $sigHex",
                )

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
                throw MalformedRarArchiveException(
                    archiveName = archiveName,
                    volumeIndex = currentVolumeIndex,
                    unexpectedBytes = ByteArray(0),
                    message = "Invalid RAR5 header size in '${archiveName ?: "<unknown>"}' " +
                            "(volume $currentVolumeIndex): headerSize=$headerSize, " +
                            "consumed=$headerBytesConsumed, remaining=$remainingHeaderSize",
                )
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

                RAR5_HEAD_CRYPT -> {
                    // Archive is password-protected. Subsequent block headers are
                    // AES-CBC encrypted with a key derived from (password, salt,
                    // 2^kdfIterations). If a password was supplied, derive the key
                    // and switch into the encrypted-block reading path; this loop
                    // continues with `stream` advanced to whatever the encrypted
                    // path leaves it (start of next non-encrypted region or EOF).
                    val cryptException = parseCryptHeaderAndThrow(
                        stream = stream,
                        archiveName = archiveName,
                        currentVolumeIndex = currentVolumeIndex,
                        headerStartPosition = headerStartPosition,
                        remainingHeaderSize = remainingHeaderSize,
                    )
                    if (password == null || cryptException.info.salt == null ||
                        cryptException.info.kdfIterationsLog2 == null
                    ) {
                        throw cryptException
                    }
                    val nextHeaderPosition = headerDataPosition + remainingHeaderSize + dataAreaSize
                    stream.seek(nextHeaderPosition)
                    val key = Rar5Crypto.deriveKey(
                        password = password,
                        salt = cryptException.info.salt,
                        kdfIterationsLog2 = cryptException.info.kdfIterationsLog2,
                    )
                    val outcome = parseEncryptedBlocks(
                        stream = stream,
                        key = key,
                        salt = cryptException.info.salt,
                        kdfIterationsLog2 = cryptException.info.kdfIterationsLog2,
                        entries = entries,
                        seenFiles = seenFiles,
                        fileSplitInfo = fileSplitInfo,
                        currentVolumeIndex = currentVolumeIndex,
                    )
                    if (outcome == EncryptedParseOutcome.PASSWORD_INCORRECT) {
                        throw EncryptedRarArchiveException(
                            info = cryptException.info,
                            passwordIncorrect = true,
                            message = cryptException.message + " — supplied password is incorrect",
                        )
                    }
                    if (outcome == EncryptedParseOutcome.END_OF_ARCHIVE) {
                        foundEndArchive = true
                    }
                    // parseEncryptedBlocks has already advanced the stream past all the
                    // encrypted blocks (well past where this CRYPT iteration's
                    // nextHeaderPosition would land). `continue` the outer loop to skip
                    // the bottom-of-loop `stream.seek(nextHeaderPosition)` that would
                    // otherwise yank us backwards to the end of the unencrypted CRYPT
                    // block, undoing our forward progress.
                    continue
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

    /**
     * Parses the body of a `HEAD_CRYPT` block (RAR5 type 4) and constructs the
     * exception that carries the extracted crypto parameters. Body layout per
     * rar5tech.txt §4 (encryption block):
     *   vint encryption_version  (0 = AES-256)
     *   vint encryption_flags    (bit 0x01 = password check value present)
     *   1 byte kdf_count         (PBKDF2 iterations = 2^kdf_count)
     *   16 bytes salt
     *   if (flags & 0x01): 12 bytes password-check (8 bytes pswcheck + 4 bytes CRC)
     *
     * If any field can't be read the params are surfaced as null — the exception
     * still fires so the caller knows the archive is encrypted, just without the
     * crypto details. Phase 1 doesn't actually use the params; Phase 3 will.
     */
    private suspend fun parseCryptHeaderAndThrow(
        stream: SeekableInputStream,
        archiveName: String?,
        currentVolumeIndex: Int,
        headerStartPosition: Long,
        remainingHeaderSize: Long,
    ): EncryptedRarArchiveException {
        val bodyStart = stream.position()
        val bodyEnd = bodyStart + remainingHeaderSize

        val encryptionVersion = readVInt(stream)?.first
        val encryptionFlags = readVInt(stream)?.first ?: 0L
        val hasPasswordCheck = (encryptionFlags and 0x01L) != 0L

        val kdfCount: Int? = if (stream.position() < bodyEnd) {
            val buf = ByteArray(1)
            if (stream.read(buf, 0, 1) == 1) buf[0].toInt() and 0xFF else null
        } else {
            null
        }

        val salt: ByteArray? = if (kdfCount != null && stream.position() + RAR5_CRYPT_SALT_SIZE <= bodyEnd) {
            val s = ByteArray(RAR5_CRYPT_SALT_SIZE)
            if (stream.read(s, 0, RAR5_CRYPT_SALT_SIZE) == RAR5_CRYPT_SALT_SIZE) s else null
        } else {
            null
        }

        val saltHex = salt?.joinToString("") { "%02x".format(it) }
        val info = EncryptionInfo(
            archiveName = archiveName,
            volumeIndex = currentVolumeIndex,
            encryptionVersion = encryptionVersion,
            kdfIterationsLog2 = kdfCount,
            salt = salt,
            hasPasswordCheck = hasPasswordCheck,
        )
        return EncryptedRarArchiveException(
            info = info,
            message = buildString {
                append("RAR5 archive '${archiveName ?: "<unknown>"}' (volume $currentVolumeIndex) is ")
                append("password-protected (HEAD_CRYPT block at offset $headerStartPosition)")
                if (kdfCount != null) append("; kdfIter=2^$kdfCount")
                if (saltHex != null) append("; salt=$saltHex")
                if (hasPasswordCheck) append("; pwcheck=present")
            },
        )
    }

    private enum class EncryptedParseOutcome {
        END_OF_ARCHIVE,    // hit HEAD_ENDARC — outer loop should set foundEndArchive
        STREAM_END,        // ran out of bytes without ENDARC — outer loop will see EOF
        PASSWORD_INCORRECT // first block CRC mismatch — outer caller throws Encrypted(pwIncorrect=true)
    }

    /**
     * Reads encrypted blocks one-by-one after a `HEAD_CRYPT`, decrypts each via
     * [Rar5EncryptedBlockReader], CRC-validates, and parses HEAD_FILE entries
     * from the plaintext. The first block also serves as password validation:
     * if its CRC doesn't match, the password was wrong and we abort.
     *
     * Subsequent blocks beyond the first don't re-validate — RAR5 encrypted
     * archives with a wrong key would diverge wildly across blocks but our
     * concern is the wrong-password case which the first-block check catches.
     *
     * Limitations of this implementation (Phase 3 scope):
     * - Multi-volume continuation across encrypted volumes works through the
     *   outer loop's existing `foundEndArchive` → next-volume signature scan
     *   logic — when a new volume's HEAD_CRYPT block is hit, this function is
     *   re-entered.
     * - Split-file position inference (the `inferSplitPositions` optimization
     *   path) is intentionally skipped in encrypted mode; we always use the
     *   normal multi-volume parse path. That's slower for huge single-file
     *   season packs but vastly simpler and correctness-first.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private suspend fun parseEncryptedBlocks(
        stream: SeekableInputStream,
        key: ByteArray,
        salt: ByteArray,
        kdfIterationsLog2: Int,
        entries: MutableList<RarFileEntry>,
        seenFiles: MutableSet<String>,
        fileSplitInfo: MutableMap<String, MutableList<SplitInfo>>,
        currentVolumeIndex: Int,
    ): EncryptedParseOutcome {
        val reader = Rar5EncryptedBlockReader(key)
        var firstBlock = true
        while (true) {
            val blockStartOnDisk = stream.position()
            val block = reader.readBlockHeader(stream) ?: return EncryptedParseOutcome.STREAM_END
            val pt = block.plaintextHeader

            // CRC-validate the first block (catches wrong password). If it fails,
            // we don't know whether subsequent block bytes are also from a wrong
            // key or from corruption, but either way we're done.
            if (firstBlock) {
                val storedCrc = ByteBuffer.wrap(pt, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    .toLong() and 0xFFFFFFFFL
                val crc32 = java.util.zip.CRC32()
                crc32.update(pt, 4, pt.size - 4)
                if (storedCrc != crc32.value) return EncryptedParseOutcome.PASSWORD_INCORRECT
                firstBlock = false
            }

            // Re-parse the block from plaintext using the same vint helpers the
            // unencrypted path uses, just over a ByteArraySeekableInputStream.
            val ptStream = ByteArraySeekableInputStream(pt)
            ptStream.seek(4) // skip CRC

            val headerSizeResult = readVInt(ptStream) ?: return EncryptedParseOutcome.STREAM_END
            val headerSize = headerSizeResult.first

            val headerTypeResult = readVInt(ptStream) ?: return EncryptedParseOutcome.STREAM_END
            val headerType = headerTypeResult.first.toInt()

            val headerFlagsResult = readVInt(ptStream) ?: return EncryptedParseOutcome.STREAM_END
            val headerFlags = headerFlagsResult.first
            var headerBytesConsumed = headerTypeResult.second + headerFlagsResult.second

            var extraAreaSize = 0L
            if (headerFlags and 0x01L != 0L) {
                val r = readVInt(ptStream) ?: return EncryptedParseOutcome.STREAM_END
                extraAreaSize = r.first
                headerBytesConsumed += r.second
            }
            var dataAreaSize = 0L
            if (headerFlags and 0x02L != 0L) {
                val r = readVInt(ptStream) ?: return EncryptedParseOutcome.STREAM_END
                dataAreaSize = r.first
                headerBytesConsumed += r.second
            }

            val remainingHeaderSize = headerSize - headerBytesConsumed
            if (remainingHeaderSize < 0) return EncryptedParseOutcome.STREAM_END

            val headerDataPosition = ptStream.position()

            when (headerType) {
                RAR5_HEAD_FILE -> {
                    val fileEntry = parseFileHeader(
                        ptStream, headerDataPosition, headerFlags,
                        remainingHeaderSize, currentVolumeIndex, dataAreaSize,
                        ::readBytesFromInMemoryStream,
                    )
                    if (fileEntry != null) {
                        // dataStartPosition for encrypted blocks points at the IV (which is at
                        // blockStartOnDisk). plaintextHeaderSize tells the streaming layer how
                        // many bytes of decrypted block precede the actual file data.
                        val plaintextHeaderSize = 4L + headerSizeResult.second + headerSize
                        // The extra area sits at the END of the header, just before the data area.
                        // It can carry a per-file encryption record (type 1) with the data area's
                        // own AES-CBC IV. Without that IV we'd decrypt the data area chained from
                        // the header's last ciphertext block, which produces garbage.
                        val fileEncryptionIv = if (extraAreaSize > 0) {
                            val extraStart = (plaintextHeaderSize - extraAreaSize).toInt()
                            parseFileEncryptionIv(pt, extraStart, extraAreaSize.toInt())
                        } else null
                        val splitInfo = SplitInfo(
                            volumeIndex = currentVolumeIndex,
                            dataStartPosition = blockStartOnDisk,
                            dataSize = dataAreaSize,
                            encryption = SplitEncryptionInfo(
                                plaintextHeaderSize = plaintextHeaderSize,
                                salt = salt,
                                kdfIterationsLog2 = kdfIterationsLog2,
                                dataAreaIv = fileEncryptionIv,
                            ),
                        )
                        fileSplitInfo.getOrPut(fileEntry.path) { mutableListOf() }.add(splitInfo)
                        if (seenFiles.add(fileEntry.path)) {
                            entries.add(fileEntry.copy(splitParts = fileSplitInfo[fileEntry.path]!!.toList()))
                            logger.debug("Found encrypted file: {} in volume {}", fileEntry.path, currentVolumeIndex)
                        } else {
                            val idx = entries.indexOfFirst { it.path == fileEntry.path }
                            if (idx >= 0) {
                                entries[idx] = entries[idx].copy(
                                    splitParts = fileSplitInfo[fileEntry.path]!!.toList(),
                                    crc32 = fileEntry.crc32 ?: entries[idx].crc32,
                                )
                            }
                        }
                    }
                }
                RAR5_HEAD_SERVICE, RAR5_HEAD_MAIN -> {
                    logger.debug("Encrypted block type {} at volume {}", headerType, currentVolumeIndex)
                }
                RAR5_HEAD_ENDARC -> {
                    logger.debug("Encrypted end-of-archive marker (volume {})", currentVolumeIndex)
                }
                else -> {
                    logger.debug("Unknown encrypted block type {} at volume {}", headerType, currentVolumeIndex)
                }
            }

            // Advance past this block's full ciphertext: header + data area, padded to 16.
            // Using a unified formula across all block types so we can't get the seek
            // wrong per-branch.
            val totalPlaintextSize = 4L + headerSizeResult.second + headerSize + dataAreaSize
            stream.seek(
                blockStartOnDisk + Rar5EncryptedBlockReader.IV_SIZE +
                    Rar5Crypto.ciphertextLengthFor(totalPlaintextSize)
            )

            if (headerType == RAR5_HEAD_ENDARC) return EncryptedParseOutcome.END_OF_ARCHIVE
        }
    }

    /**
     * Adapter that lets [parseFileHeader] use the in-memory plaintext stream
     * with the same `readBytes` lambda shape it expects from disk-backed reads.
     */
    private suspend fun readBytesFromInMemoryStream(stream: SeekableInputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = stream.read(buffer, offset, count - offset)
            if (read == -1) return null
            offset += read
        }
        return buffer
    }

    /**
     * Walks the file header's extra-area records looking for type 1 (file encryption)
     * and returns its 16-byte AES IV. The data area's AES-CBC stream is independent of
     * the header's CBC stream and uses this IV as its starting state.
     *
     * Per rar5tech.txt §4.3.4, a file-encryption record is laid out:
     * ```
     *   Encryption version  vint (1 byte for AES-256)
     *   Encryption flags    vint (bit 0: PSWCHECK_PRESENT, bit 1: HAS_MAC)
     *   KDF count           1 byte
     *   Salt                16 bytes
     *   IV                  16 bytes
     *   [Password check    12 bytes if PSWCHECK_PRESENT]
     *   [Hash type/data    if HAS_MAC]
     * ```
     *
     * Returns null when the extra area has no encryption record (legacy RAR5 archives
     * where header-encryption was used without per-file data encryption — uncommon).
     */
    private fun parseFileEncryptionIv(plaintextBlock: ByteArray, extraAreaStart: Int, extraAreaSize: Int): ByteArray? {
        var pos = extraAreaStart
        val end = extraAreaStart + extraAreaSize
        while (pos < end) {
            val recordSizeVint = readVIntFromArray(plaintextBlock, pos) ?: return null
            val recordSize = recordSizeVint.first.toInt()
            pos += recordSizeVint.second
            val recordEnd = pos + recordSize
            if (recordEnd > end) return null

            val recordTypeVint = readVIntFromArray(plaintextBlock, pos) ?: return null
            val recordType = recordTypeVint.first.toInt()
            val recordTypeLen = recordTypeVint.second
            val bodyStart = pos + recordTypeLen

            if (recordType == FILE_EXTRA_TYPE_ENCRYPTION) {
                // version (vint) + flags (vint) + kdfCount (1 byte) + salt (16) + iv (16)
                val versionVint = readVIntFromArray(plaintextBlock, bodyStart) ?: return null
                val flagsVint = readVIntFromArray(
                    plaintextBlock, bodyStart + versionVint.second,
                ) ?: return null
                val kdfStart = bodyStart + versionVint.second + flagsVint.second
                val ivStart = kdfStart + 1 + 16  // skip kdfCount + salt
                if (ivStart + 16 > recordEnd) return null
                return plaintextBlock.copyOfRange(ivStart, ivStart + 16)
            }
            pos = recordEnd
        }
        return null
    }

    private fun readVIntFromArray(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        var value = 0L
        var bytesRead = 0
        var pos = offset
        while (bytesRead < 10 && pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl (bytesRead * 7))
            bytesRead++
            pos++
            if (b and 0x80 == 0) return value to bytesRead
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
