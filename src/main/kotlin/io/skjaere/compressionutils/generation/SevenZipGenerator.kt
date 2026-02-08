package io.skjaere.compressionutils.generation

import io.skjaere.compressionutils.generation.BinaryWriteUtils.crc32
import io.skjaere.compressionutils.generation.BinaryWriteUtils.encode7zUInt64
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeLE32
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeLE64
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeBytes
import java.io.ByteArrayOutputStream

internal object SevenZipGenerator {

    private val MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private const val SIGNATURE_HEADER_SIZE = 32

    // Property IDs matching SevenZipParser
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
    private const val kName = 0x11

    fun generate(data: ByteArray, numberOfVolumes: Int, filename: String): List<ArchiveVolume> {
        val archiveBytes = generateSingleArchive(data, filename)

        if (numberOfVolumes <= 1) {
            return listOf(ArchiveVolume("archive.7z", archiveBytes))
        }

        // Multi-volume: split the complete archive at byte boundaries
        val volumeSize = (archiveBytes.size + numberOfVolumes - 1) / numberOfVolumes
        return splitArchive(archiveBytes, volumeSize)
    }

    fun generateWithVolumeSize(data: ByteArray, volumeSize: Long, filename: String): List<ArchiveVolume> {
        val archiveBytes = generateSingleArchive(data, filename)

        if (archiveBytes.size <= volumeSize) {
            return listOf(ArchiveVolume("archive.7z", archiveBytes))
        }

        return splitArchive(archiveBytes, volumeSize.toInt())
    }

    private fun splitArchive(archiveBytes: ByteArray, volumeSize: Int): List<ArchiveVolume> {
        val volumes = mutableListOf<ArchiveVolume>()
        var offset = 0
        var volNum = 1

        while (offset < archiveBytes.size) {
            val end = minOf(offset + volumeSize, archiveBytes.size)
            val volData = archiveBytes.copyOfRange(offset, end)
            val volFilename = "archive.7z.%03d".format(volNum)
            volumes.add(ArchiveVolume(volFilename, volData))
            offset = end
            volNum++
        }

        return volumes
    }

    private fun generateSingleArchive(data: ByteArray, filename: String): ByteArray {
        val dataCrc = crc32(data)
        val metadata = buildMetadata(data.size.toLong(), dataCrc, filename)
        val nextHeaderCrc = crc32(metadata)

        // nextHeaderOffset = size of file data (starts right after signature header)
        val nextHeaderOffset = data.size.toLong()
        val nextHeaderSize = metadata.size.toLong()

        // Build the start header content (16 bytes: offset + size + nextHeaderCrc)
        val startHeaderContent = ByteArrayOutputStream(16)
        startHeaderContent.writeBytes(writeLE64(nextHeaderOffset))
        startHeaderContent.writeBytes(writeLE64(nextHeaderSize))
        val startHeaderContentBytes = startHeaderContent.toByteArray()

        // Compute startHeaderCRC manually over the full 20 bytes: offset(8) + size(8) + nextHeaderCRC(4)
        val crcInput = ByteArrayOutputStream(20)
        crcInput.writeBytes(startHeaderContentBytes)
        crcInput.writeBytes(writeLE32(nextHeaderCrc))
        val startHeaderCrc = crc32(crcInput.toByteArray())

        // Build the full archive
        val out = ByteArrayOutputStream()

        // Signature header (32 bytes)
        out.writeBytes(MAGIC)                              // 6 bytes: magic
        out.write(0x00); out.write(0x04)                   // 2 bytes: version (0.4)
        out.writeBytes(writeLE32(startHeaderCrc))          // 4 bytes: start header CRC
        out.writeBytes(startHeaderContentBytes)            // 16 bytes: offset + size
        out.writeBytes(writeLE32(nextHeaderCrc))           // 4 bytes: next header CRC

        // File data (raw, uncompressed)
        out.writeBytes(data)

        // Metadata section
        out.writeBytes(metadata)

        return out.toByteArray()
    }

    private fun buildMetadata(dataSize: Long, dataCrc: Int, filename: String): ByteArray {
        val out = ByteArrayOutputStream()

        // kHeader
        out.write(kHeader)

        // kMainStreamsInfo
        out.write(kMainStreamsInfo)

        // kPackInfo
        out.write(kPackInfo)
        out.writeBytes(encode7zUInt64(0))          // packPos = 0 (relative to signature header end)
        out.writeBytes(encode7zUInt64(1))          // numPackStreams = 1
        out.write(kSize)
        out.writeBytes(encode7zUInt64(dataSize))   // size of the packed stream
        out.write(kEnd)                            // end of kPackInfo

        // kUnPackInfo
        out.write(kUnPackInfo)
        out.write(kFolder)
        out.writeBytes(encode7zUInt64(1))          // numFolders = 1
        out.write(0x00)                            // external = 0

        // Folder: 1 coder, Copy codec (0x00)
        out.write(0x01)                            // numCoders = 1
        out.write(0x01)                            // coder flags: codecIdSize=1, not complex, no attributes
        out.write(0x00)                            // codec ID: Copy

        out.write(kCodersUnPackSize)
        out.writeBytes(encode7zUInt64(dataSize))   // unpack size
        out.write(kEnd)                            // end of kUnPackInfo

        // kSubStreamsInfo
        out.write(kSubStreamsInfo)
        out.write(kCRC)
        out.write(0x01)                            // allDefined = 1
        out.writeBytes(writeLE32(dataCrc))         // CRC32 of file data
        out.write(kEnd)                            // end of kSubStreamsInfo

        out.write(kEnd)                            // end of kMainStreamsInfo

        // kFilesInfo
        out.write(kFilesInfo)
        out.writeBytes(encode7zUInt64(1))          // numFiles = 1

        // kName property
        out.write(kName)
        val nameBytes = encodeFilenameUtf16LE(filename)
        val namePropertySize = 1 + nameBytes.size  // 1 byte for external flag + name data
        out.writeBytes(encode7zUInt64(namePropertySize.toLong()))
        out.write(0x00)                            // external = 0
        out.writeBytes(nameBytes)

        out.write(kEnd)                            // end of kFilesInfo

        out.write(kEnd)                            // end of kHeader

        return out.toByteArray()
    }

    /** Encode filename as UTF-16LE with null terminator (matching SevenZipParser.readFileNames). */
    private fun encodeFilenameUtf16LE(filename: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (ch in filename) {
            out.write(ch.code and 0xFF)
            out.write((ch.code shr 8) and 0xFF)
        }
        // Null terminator
        out.write(0x00)
        out.write(0x00)
        return out.toByteArray()
    }
}
