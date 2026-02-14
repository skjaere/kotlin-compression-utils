package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native parser for 7z archive metadata. Reads the binary format directly
 * without requiring any native/JNI dependencies.
 *
 * Only supports uncompressed (store-mode / Copy codec) archives. Archives with
 * compressed headers or compressed file data will be rejected with a clear error.
 */
class SevenZipParser {
    private val logger = LoggerFactory.getLogger(SevenZipParser::class.java)

    companion object {
        private val MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        private const val SIGNATURE_HEADER_SIZE = 32L

        // Property IDs
        private const val kEnd = 0x00
        private const val kHeader = 0x01
        private const val kMainStreamsInfo = 0x04
        private const val kFilesInfo = 0x05
        private const val kPackInfo = 0x06
        private const val kUnPackInfo = 0x07
        private const val kSubStreamsInfo = 0x08
        private const val kSize = 0x09
        private const val kCRC = 0x0A
        private const val kFolder = 0x0B
        private const val kCodersUnPackSize = 0x0C
        private const val kNumUnPackStream = 0x0D
        private const val kEmptyStream = 0x0E
        private const val kEmptyFile = 0x0F
        private const val kName = 0x11
        private const val kMTime = 0x14
        private const val kWinAttributes = 0x15
        private const val kEncodedHeader = 0x17
        private const val kDummy = 0x19

        // Copy codec ID (uncompressed)
        private val COPY_CODEC_ID = byteArrayOf(0x00)
    }

    /**
     * Parses a 7z archive and returns its file entries.
     *
     * @param stream A SeekableInputStream positioned at the start of the archive
     * @return List of SevenZipFileEntry with metadata and calculated data offsets
     * @throws IOException if the archive is invalid, compressed, or cannot be parsed
     */
    suspend fun parse(stream: SeekableInputStream): List<SevenZipFileEntry> {
        val (nextHeaderOffset, nextHeaderSize) = readSignatureHeader(stream)

        val metadataStart = SIGNATURE_HEADER_SIZE + nextHeaderOffset
        stream.seek(metadataStart)

        val metadataBytes = ByteArray(nextHeaderSize.toInt())
        readFully(stream, metadataBytes)

        val buf = ByteBuffer.wrap(metadataBytes).order(ByteOrder.LITTLE_ENDIAN)
        return parseHeader(buf)
    }

    private data class SignatureHeader(val nextHeaderOffset: Long, val nextHeaderSize: Long)

    private suspend fun readSignatureHeader(stream: SeekableInputStream): SignatureHeader {
        stream.seek(0)
        val header = ByteArray(SIGNATURE_HEADER_SIZE.toInt())
        readFully(stream, header)

        // Validate magic bytes
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                throw IOException("Not a valid 7z archive: incorrect magic bytes")
            }
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12) // Skip magic (6) + version (2) + start header CRC (4)
        val nextHeaderOffset = buf.getLong()
        val nextHeaderSize = buf.getLong()

        logger.debug("Signature header: nextHeaderOffset=$nextHeaderOffset, nextHeaderSize=$nextHeaderSize")
        return SignatureHeader(nextHeaderOffset, nextHeaderSize)
    }

    private fun parseHeader(buf: ByteBuffer): List<SevenZipFileEntry> {
        val propertyId = readByte(buf)

        if (propertyId == kEncodedHeader) {
            throw IOException(
                "Compressed 7z headers are not supported; only uncompressed/store-mode archives can be parsed"
            )
        }

        if (propertyId != kHeader) {
            throw IOException("Expected kHeader (0x01) or kEncodedHeader (0x17), found: 0x${propertyId.toString(16)}")
        }

        var packInfo: PackInfo? = null
        var unpackInfo: UnpackInfo? = null
        var subStreamsInfo: SubStreamsInfo? = null
        var numFiles = 0
        var fileNames = emptyList<String>()
        var emptyStreamFlags = BooleanArray(0)
        var winAttributes = IntArray(0)

        // Parse kMainStreamsInfo and kFilesInfo at the top level
        while (buf.hasRemaining()) {
            when (val id = readByte(buf)) {
                kEnd -> break
                kMainStreamsInfo -> {
                    val streamsResult = parseMainStreamsInfo(buf)
                    packInfo = streamsResult.packInfo
                    unpackInfo = streamsResult.unpackInfo
                    subStreamsInfo = streamsResult.subStreamsInfo
                }
                kFilesInfo -> {
                    val filesResult = parseFilesInfo(buf, packInfo, unpackInfo, subStreamsInfo)
                    numFiles = filesResult.numFiles
                    fileNames = filesResult.names
                    emptyStreamFlags = filesResult.emptyStreamFlags
                    winAttributes = filesResult.winAttributes
                }
                else -> throw IOException("Unexpected property in header: 0x${id.toString(16)}")
            }
        }

        return buildEntries(packInfo, unpackInfo, subStreamsInfo, numFiles, fileNames, emptyStreamFlags, winAttributes)
    }

    // --- MainStreamsInfo ---

    private data class StreamsInfo(
        val packInfo: PackInfo?,
        val unpackInfo: UnpackInfo?,
        val subStreamsInfo: SubStreamsInfo?
    )

    private data class PackInfo(val packPos: Long, val numPackStreams: Int, val packSizes: LongArray)

    private data class FolderInfo(val codecId: ByteArray, val numCoders: Int)

    private data class UnpackInfo(val folders: List<FolderInfo>, val unpackSizes: LongArray)

    private data class SubStreamsInfo(
        val numUnpackStreamsPerFolder: IntArray,
        val unpackSizes: LongArray,
        val crcs: IntArray?
    )

    private fun parseMainStreamsInfo(buf: ByteBuffer): StreamsInfo {
        var packInfo: PackInfo? = null
        var unpackInfo: UnpackInfo? = null
        var subStreamsInfo: SubStreamsInfo? = null

        while (buf.hasRemaining()) {
            when (val id = readByte(buf)) {
                kEnd -> break
                kPackInfo -> packInfo = parsePackInfo(buf)
                kUnPackInfo -> unpackInfo = parseUnpackInfo(buf)
                kSubStreamsInfo -> subStreamsInfo = parseSubStreamsInfo(buf, unpackInfo)
                else -> throw IOException("Unexpected property in MainStreamsInfo: 0x${id.toString(16)}")
            }
        }

        return StreamsInfo(packInfo, unpackInfo, subStreamsInfo)
    }

    private fun parsePackInfo(buf: ByteBuffer): PackInfo {
        val packPos = readUInt64(buf)
        val numPackStreams = readUInt64(buf).toInt()
        var packSizes = LongArray(numPackStreams)

        while (buf.hasRemaining()) {
            when (val id = readByte(buf)) {
                kEnd -> break
                kSize -> {
                    for (i in 0 until numPackStreams) {
                        packSizes[i] = readUInt64(buf)
                    }
                }
                kCRC -> skipCrcData(buf, numPackStreams)
                else -> throw IOException("Unexpected property in PackInfo: 0x${id.toString(16)}")
            }
        }

        logger.debug("PackInfo: packPos=$packPos, numPackStreams=$numPackStreams, sizes=${packSizes.toList()}")
        return PackInfo(packPos, numPackStreams, packSizes)
    }

    private fun parseUnpackInfo(buf: ByteBuffer): UnpackInfo {
        var folders = emptyList<FolderInfo>()
        var unpackSizes = LongArray(0)

        while (buf.hasRemaining()) {
            when (val id = readByte(buf)) {
                kEnd -> break
                kFolder -> {
                    val numFolders = readUInt64(buf).toInt()
                    val external = readByte(buf)
                    if (external != 0) {
                        throw IOException("External folder data is not supported")
                    }

                    val folderList = mutableListOf<FolderInfo>()
                    for (i in 0 until numFolders) {
                        folderList.add(parseFolder(buf))
                    }
                    folders = folderList
                }
                kCodersUnPackSize -> {
                    // One unpack size per output stream per folder
                    // For simple codecs (1 coder, no binding), it's 1 per folder
                    val totalOutputStreams = folders.size // simplified: 1 output per folder
                    unpackSizes = LongArray(totalOutputStreams)
                    for (i in 0 until totalOutputStreams) {
                        unpackSizes[i] = readUInt64(buf)
                    }
                }
                kCRC -> skipCrcData(buf, folders.size)
                else -> throw IOException("Unexpected property in UnPackInfo: 0x${id.toString(16)}")
            }
        }

        logger.debug("UnpackInfo: ${folders.size} folders, unpackSizes=${unpackSizes.toList()}")
        return UnpackInfo(folders, unpackSizes)
    }

    private fun parseFolder(buf: ByteBuffer): FolderInfo {
        val numCoders = readUInt64(buf).toInt()
        if (numCoders != 1) {
            throw IOException("Only single-coder folders are supported; found $numCoders coders")
        }

        val coderByte = readByte(buf)
        val codecIdSize = coderByte and 0x0F
        val isComplex = (coderByte and 0x10) != 0
        val hasAttributes = (coderByte and 0x20) != 0

        val codecId = ByteArray(codecIdSize)
        for (i in 0 until codecIdSize) {
            codecId[i] = buf.get()
        }

        if (isComplex) {
            throw IOException("Complex coder configurations are not supported")
        }

        if (hasAttributes) {
            // Read and skip properties size + properties data
            val propsSize = readUInt64(buf).toInt()
            for (i in 0 until propsSize) {
                buf.get()
            }
        }

        // Validate it's Copy codec
        if (!codecId.contentEquals(COPY_CODEC_ID)) {
            val codecHex = codecId.joinToString("") { "%02x".format(it) }
            throw IOException("Only uncompressed 7z archives are supported; found codec: 0x$codecHex")
        }

        return FolderInfo(codecId, numCoders)
    }

    private fun parseSubStreamsInfo(buf: ByteBuffer, unpackInfo: UnpackInfo?): SubStreamsInfo {
        val numFolders = unpackInfo?.folders?.size ?: 0
        var numUnpackStreamsPerFolder = IntArray(numFolders) { 1 } // default: 1 stream per folder
        var unpackSizes = LongArray(0)
        var crcs: IntArray? = null

        while (buf.hasRemaining()) {
            when (val id = readByte(buf)) {
                kEnd -> break
                kNumUnPackStream -> {
                    for (i in 0 until numFolders) {
                        numUnpackStreamsPerFolder[i] = readUInt64(buf).toInt()
                    }
                }
                kSize -> {
                    // For each folder, read (numStreams - 1) sizes; the last is derived
                    val sizeList = mutableListOf<Long>()
                    for (folderIdx in 0 until numFolders) {
                        val numStreams = numUnpackStreamsPerFolder[folderIdx]
                        var remaining = unpackInfo?.unpackSizes?.get(folderIdx) ?: 0L
                        for (s in 0 until numStreams - 1) {
                            val size = readUInt64(buf)
                            sizeList.add(size)
                            remaining -= size
                        }
                        sizeList.add(remaining) // last stream size
                    }
                    unpackSizes = sizeList.toLongArray()
                }
                kCRC -> {
                    val totalStreams = numUnpackStreamsPerFolder.sum()
                    crcs = readCrcs(buf, totalStreams)
                }
                else -> throw IOException("Unexpected property in SubStreamsInfo: 0x${id.toString(16)}")
            }
        }

        // If no explicit sizes were given, use folder unpack sizes
        if (unpackSizes.isEmpty() && unpackInfo != null) {
            unpackSizes = unpackInfo.unpackSizes.clone()
        }

        return SubStreamsInfo(numUnpackStreamsPerFolder, unpackSizes, crcs)
    }

    // --- FilesInfo ---

    private data class FilesInfoResult(
        val numFiles: Int,
        val names: List<String>,
        val emptyStreamFlags: BooleanArray,
        val winAttributes: IntArray
    )

    private fun parseFilesInfo(
        buf: ByteBuffer,
        packInfo: PackInfo?,
        unpackInfo: UnpackInfo?,
        subStreamsInfo: SubStreamsInfo?
    ): FilesInfoResult {
        val numFiles = readUInt64(buf).toInt()
        var names = List(numFiles) { "" }
        var emptyStreamFlags = BooleanArray(numFiles)
        var winAttributes = IntArray(numFiles)

        logger.debug("FilesInfo: numFiles=$numFiles")

        while (buf.hasRemaining()) {
            val propertyType = readByte(buf)
            if (propertyType == kEnd) break

            val dataSize = readUInt64(buf).toInt()
            val dataStart = buf.position()

            when (propertyType) {
                kName -> {
                    val external = readByte(buf)
                    if (external != 0) {
                        throw IOException("External file names are not supported")
                    }
                    names = readFileNames(buf, numFiles)
                }
                kEmptyStream -> {
                    emptyStreamFlags = readBoolVector(buf, numFiles)
                }
                kEmptyFile -> {
                    // Number of empty files among empty streams
                    val numEmptyStreams = emptyStreamFlags.count { it }
                    readBoolVector(buf, numEmptyStreams) // skip, not needed
                }
                kWinAttributes -> {
                    val allDefined = readByte(buf)
                    val defined = if (allDefined != 0) {
                        BooleanArray(numFiles) { true }
                    } else {
                        readBoolVector(buf, numFiles)
                    }
                    val attrsExternal = readByte(buf)
                    if (attrsExternal != 0) {
                        throw IOException("External attributes data is not supported")
                    }
                    for (i in 0 until numFiles) {
                        if (defined[i]) {
                            winAttributes[i] = buf.getInt()
                        }
                    }
                }
                kDummy -> {
                    // Skip dummy padding
                }
                kMTime -> {
                    // Skip modification times — we don't need them
                }
                else -> {
                    logger.debug("Skipping unknown FilesInfo property: 0x${propertyType.toString(16)}, size=$dataSize")
                }
            }

            // Ensure we advance past the property data regardless of how much we consumed
            buf.position(dataStart + dataSize)
        }

        return FilesInfoResult(numFiles, names, emptyStreamFlags, winAttributes)
    }

    private fun readFileNames(buf: ByteBuffer, numFiles: Int): List<String> {
        val names = mutableListOf<String>()
        val sb = StringBuilder()

        for (i in 0 until numFiles) {
            sb.setLength(0)
            while (buf.hasRemaining()) {
                val ch = buf.getShort().toInt() and 0xFFFF
                if (ch == 0) break
                sb.append(ch.toChar())
            }
            names.add(sb.toString())
        }

        return names
    }

    // --- Building entries ---

    private fun buildEntries(
        packInfo: PackInfo?,
        unpackInfo: UnpackInfo?,
        subStreamsInfo: SubStreamsInfo?,
        numFiles: Int,
        fileNames: List<String>,
        emptyStreamFlags: BooleanArray,
        winAttributes: IntArray
    ): List<SevenZipFileEntry> {
        val entries = mutableListOf<SevenZipFileEntry>()

        // Determine file sizes from SubStreamsInfo or UnpackInfo
        val fileSizes = resolveFileSizes(packInfo, unpackInfo, subStreamsInfo, numFiles, emptyStreamFlags)

        // Calculate data offsets: file data starts at SIGNATURE_HEADER_SIZE + packPos
        val packPos = packInfo?.packPos ?: 0L
        var currentOffset = SIGNATURE_HEADER_SIZE + packPos
        val crcs = subStreamsInfo?.crcs

        var streamIdx = 0
        for (i in 0 until numFiles) {
            val name = if (i < fileNames.size) fileNames[i] else ""
            val isEmptyStream = i < emptyStreamFlags.size && emptyStreamFlags[i]
            val isDirectory = isEmptyStream && (i < winAttributes.size && (winAttributes[i] and 0x10) != 0)
                    || (isEmptyStream && fileSizes[i] == 0L && name.endsWith("/"))
            val size = fileSizes[i]

            val dataOffset = if (!isEmptyStream && size > 0) currentOffset else 0L
            val crc = if (!isEmptyStream && crcs != null && streamIdx < crcs.size)
                crcs[streamIdx].toLong() and 0xFFFFFFFFL else null
            if (!isEmptyStream) streamIdx++

            entries.add(
                SevenZipFileEntry(
                    path = name,
                    size = size,
                    packedSize = size, // Copy codec: packed == unpacked
                    dataOffset = dataOffset,
                    isDirectory = isDirectory,
                    method = if (isDirectory || isEmptyStream) null else "Copy",
                    crc32 = crc
                )
            )

            logger.trace("Entry: $name, size=$size, offset=$dataOffset, isDir=$isDirectory")

            if (!isEmptyStream && size > 0) {
                currentOffset += size
            }
        }

        return entries
    }

    private fun resolveFileSizes(
        packInfo: PackInfo?,
        unpackInfo: UnpackInfo?,
        subStreamsInfo: SubStreamsInfo?,
        numFiles: Int,
        emptyStreamFlags: BooleanArray
    ): LongArray {
        val sizes = LongArray(numFiles)
        val numNonEmptyStreams = (0 until numFiles).count { i ->
            i >= emptyStreamFlags.size || !emptyStreamFlags[i]
        }

        // Get sizes from SubStreamsInfo or fall back to UnpackInfo
        val streamSizes = subStreamsInfo?.unpackSizes ?: unpackInfo?.unpackSizes ?: LongArray(0)

        var streamIdx = 0
        for (i in 0 until numFiles) {
            val isEmpty = i < emptyStreamFlags.size && emptyStreamFlags[i]
            if (isEmpty) {
                sizes[i] = 0
            } else if (streamIdx < streamSizes.size) {
                sizes[i] = streamSizes[streamIdx++]
            }
        }

        return sizes
    }

    // --- Utility methods ---

    /**
     * Reads a 7z variable-length uint64.
     *
     * The first byte's leading 1-bits indicate the number of additional bytes:
     * - 0xxxxxxx = 1 byte total (value 0-127)
     * - 10xxxxxx + 1 byte = 2 bytes total
     * - 110xxxxx + 2 bytes = 3 bytes total
     * - etc.
     *
     * The remaining bits of the first byte are the high-order bits of the value.
     * Additional bytes are read in little-endian order.
     */
    private fun readUInt64(buf: ByteBuffer): Long {
        val firstByte = buf.get().toInt() and 0xFF

        // Count leading 1-bits
        var numExtra = 0
        var test = 0x80
        while (test != 0 && (firstByte and test) != 0) {
            numExtra++
            test = test shr 1
        }

        if (numExtra == 0) {
            return firstByte.toLong()
        }

        // Remaining bits of first byte
        val valueBitsInFirstByte = if (numExtra < 8) {
            firstByte and ((1 shl (7 - numExtra)) - 1)
        } else {
            0
        }

        var value = valueBitsInFirstByte.toLong()
        val shift = if (numExtra < 8) 7 - numExtra else 0

        for (i in 0 until numExtra) {
            val b = buf.get().toLong() and 0xFF
            value = value or (b shl (8 * i + shift))
        }

        return value
    }

    private fun readByte(buf: ByteBuffer): Int {
        return buf.get().toInt() and 0xFF
    }

    private fun readBoolVector(buf: ByteBuffer, count: Int): BooleanArray {
        val result = BooleanArray(count)
        var mask = 0
        var currentByte = 0

        for (i in 0 until count) {
            if (mask == 0) {
                currentByte = readByte(buf)
                mask = 0x80
            }
            result[i] = (currentByte and mask) != 0
            mask = mask shr 1
        }

        return result
    }

    private fun readCrcs(buf: ByteBuffer, count: Int): IntArray {
        val allDefined = readByte(buf)
        val defined = if (allDefined != 0) {
            BooleanArray(count) { true }
        } else {
            readBoolVector(buf, count)
        }

        val crcs = IntArray(count)
        for (i in 0 until count) {
            if (defined[i]) {
                crcs[i] = buf.getInt()
            }
        }
        return crcs
    }

    private fun skipCrcData(buf: ByteBuffer, count: Int) {
        readCrcs(buf, count) // just read and discard
    }

    private suspend fun readFully(stream: SeekableInputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val read = stream.read(data, offset, data.size - offset)
            if (read == -1) {
                throw IOException("Unexpected end of stream at offset $offset (expected ${data.size} bytes)")
            }
            offset += read
        }
    }
}
