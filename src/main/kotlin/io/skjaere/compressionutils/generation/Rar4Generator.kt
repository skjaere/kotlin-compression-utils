package io.skjaere.compressionutils.generation

import io.skjaere.compressionutils.generation.BinaryWriteUtils.crc16
import io.skjaere.compressionutils.generation.BinaryWriteUtils.crc32
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeLE16
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeLE32
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeBytes
import java.io.ByteArrayOutputStream

internal object Rar4Generator {

    private val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)

    // Block types
    private const val BLOCK_ARCHIVE = 0x73
    private const val BLOCK_FILE = 0x74
    private const val BLOCK_END = 0x7B

    // Archive header flags
    private const val ARCHIVE_FLAG_VOLUME = 0x0001
    private const val ARCHIVE_FLAG_FIRSTVOLUME = 0x0100

    // File header flags
    private const val FILE_FLAG_SPLIT_BEFORE = 0x01
    private const val FILE_FLAG_SPLIT_AFTER = 0x02

    // End of archive flags
    private const val END_FLAG_NEXT_VOLUME = 0x0001

    fun generate(data: ByteArray, numberOfVolumes: Int, filename: String): List<ArchiveVolume> {
        if (numberOfVolumes <= 1) {
            val archiveBytes = generateSingleVolume(data, filename)
            return listOf(ArchiveVolume("archive.rar", archiveBytes))
        }
        return generateMultiVolume(data, numberOfVolumes, filename)
    }

    fun generateWithVolumeSize(data: ByteArray, volumeSize: Long, filename: String): List<ArchiveVolume> {
        val nameBytes = filename.toByteArray(Charsets.UTF_8).size
        // overhead = SIGNATURE(7) + archiveHeader(13) + fileHeader(32 + nameBytes) + endHeader(7)
        val overhead = 59L + nameBytes
        require(volumeSize > overhead) {
            "volumeSize ($volumeSize) must be greater than per-volume overhead ($overhead)"
        }
        val dataPerVolume = volumeSize - overhead
        val singleVolumeSize = overhead + data.size
        if (singleVolumeSize <= volumeSize) {
            val archiveBytes = generateSingleVolume(data, filename)
            return listOf(ArchiveVolume("archive.rar", archiveBytes))
        }
        return generateMultiVolumeBySize(data, dataPerVolume, filename)
    }

    private fun generateSingleVolume(data: ByteArray, filename: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeBytes(SIGNATURE)
        out.writeBytes(buildArchiveHeader(isVolume = false, isFirstVolume = false))
        out.writeBytes(buildFileHeader(data, filename, data.size.toLong(), 0))
        out.writeBytes(data)
        out.writeBytes(buildEndHeader(hasNextVolume = false))
        return out.toByteArray()
    }

    private fun generateMultiVolumeBySize(data: ByteArray, dataPerVolume: Long, filename: String): List<ArchiveVolume> {
        val volumes = mutableListOf<ArchiveVolume>()
        var offset = 0
        var volIdx = 0

        while (offset < data.size) {
            val end = minOf(offset + dataPerVolume.toInt(), data.size)
            val chunk = data.copyOfRange(offset, end)
            val isFirst = volIdx == 0
            val isLast = end == data.size

            var fileFlags = 0
            if (!isLast) fileFlags = fileFlags or FILE_FLAG_SPLIT_AFTER
            if (!isFirst) fileFlags = fileFlags or FILE_FLAG_SPLIT_BEFORE

            val vol = ByteArrayOutputStream()
            vol.writeBytes(SIGNATURE)
            vol.writeBytes(buildArchiveHeader(isVolume = true, isFirstVolume = isFirst))
            vol.writeBytes(buildFileHeader(data, filename, chunk.size.toLong(), fileFlags))
            vol.writeBytes(chunk)
            vol.writeBytes(buildEndHeader(hasNextVolume = !isLast))

            val volFilename = if (isFirst) {
                "archive.rar"
            } else {
                "archive.r%02d".format(volIdx - 1)
            }
            volumes.add(ArchiveVolume(volFilename, vol.toByteArray()))
            offset = end
            volIdx++
        }

        return volumes
    }

    private fun generateMultiVolume(data: ByteArray, numberOfVolumes: Int, filename: String): List<ArchiveVolume> {
        val chunkSize = (data.size + numberOfVolumes - 1) / numberOfVolumes
        val volumes = mutableListOf<ArchiveVolume>()

        var offset = 0
        for (volIdx in 0 until numberOfVolumes) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val isFirst = volIdx == 0
            val isLast = volIdx == numberOfVolumes - 1

            var fileFlags = 0
            if (!isLast) fileFlags = fileFlags or FILE_FLAG_SPLIT_AFTER
            if (!isFirst) fileFlags = fileFlags or FILE_FLAG_SPLIT_BEFORE

            val vol = ByteArrayOutputStream()
            vol.writeBytes(SIGNATURE)
            vol.writeBytes(buildArchiveHeader(isVolume = true, isFirstVolume = isFirst))
            vol.writeBytes(buildFileHeader(data, filename, chunk.size.toLong(), fileFlags))
            vol.writeBytes(chunk)
            vol.writeBytes(buildEndHeader(hasNextVolume = !isLast))

            val volFilename = if (isFirst) {
                "archive.rar"
            } else {
                // Old-style: .r00, .r01, ...
                "archive.r%02d".format(volIdx - 1)
            }
            volumes.add(ArchiveVolume(volFilename, vol.toByteArray()))
            offset = end
        }

        return volumes
    }

    /**
     * Build RAR4 archive header (type 0x73).
     *
     * Layout (13 bytes total):
     * - CRC16 (2 bytes): CRC of bytes from type onward
     * - Type (1 byte): 0x73
     * - Flags (2 bytes LE)
     * - Size (2 bytes LE): 13 (always)
     * - Reserved (6 bytes): zeros
     */
    private fun buildArchiveHeader(isVolume: Boolean, isFirstVolume: Boolean): ByteArray {
        var flags = 0
        if (isVolume) flags = flags or ARCHIVE_FLAG_VOLUME
        if (isFirstVolume) flags = flags or ARCHIVE_FLAG_FIRSTVOLUME

        // Build content after CRC: type + flags + size + reserved
        val content = ByteArrayOutputStream()
        content.write(BLOCK_ARCHIVE)
        content.writeBytes(writeLE16(flags))
        content.writeBytes(writeLE16(13))  // total block size = 13
        content.write(ByteArray(6))        // reserved

        val contentBytes = content.toByteArray()
        val crc = crc16(contentBytes)

        val out = ByteArrayOutputStream()
        out.writeBytes(writeLE16(crc))
        out.writeBytes(contentBytes)
        return out.toByteArray()
    }

    /**
     * Build RAR4 file header (type 0x74).
     *
     * The parser reads (in parseFileHeader):
     * - packSize (4 bytes LE)
     * - unpackSize (4 bytes LE)
     * - hostOS (1 byte)
     * - fileCRC (4 bytes LE)
     * - ftime (4 bytes LE)
     * - unpackVersion (1 byte)
     * - method (1 byte): 0x30 = store
     * - nameLength (2 bytes LE)
     * - attributes (4 bytes LE)
     * - [optional: highPackSize + highUnpackSize if LARGE_FILE flag]
     * - name (nameLength bytes)
     *
     * Block header: CRC16(2) + type(1) + flags(2) + blockSize(2)
     * Total header = 7 (block header) + 25 (fixed fields) + nameLength = 32 + nameLength
     */
    private fun buildFileHeader(
        fullData: ByteArray,
        filename: String,
        packSize: Long,
        fileFlags: Int
    ): ByteArray {
        val nameBytes = filename.toByteArray(Charsets.UTF_8)
        val dataCrc = crc32(fullData)
        val blockSize = 7 + 25 + nameBytes.size  // total block header size

        // Build content after CRC: type + flags + size + fields + name
        val content = ByteArrayOutputStream()
        content.write(BLOCK_FILE)                                  // type
        content.writeBytes(writeLE16(fileFlags))                   // flags
        content.writeBytes(writeLE16(blockSize))                   // block size
        content.writeBytes(writeLE32(packSize.toInt()))            // pack size (low 32)
        content.writeBytes(writeLE32(fullData.size))               // unpack size (low 32)
        content.write(0x00)                                        // host OS (0 = MSDOS)
        content.writeBytes(writeLE32(dataCrc))                     // file CRC32
        content.writeBytes(writeLE32(0))                           // ftime (0 = no time)
        content.write(29)                                          // unpack version = 29
        content.write(0x30)                                        // method = 0x30 (store)
        content.writeBytes(writeLE16(nameBytes.size))              // name length
        content.writeBytes(writeLE32(0x20))                        // attributes (archive)
        content.writeBytes(nameBytes)                              // filename

        val contentBytes = content.toByteArray()
        val crc = crc16(contentBytes)

        val out = ByteArrayOutputStream()
        out.writeBytes(writeLE16(crc))
        out.writeBytes(contentBytes)
        return out.toByteArray()
    }

    /**
     * Build RAR4 end-of-archive header (type 0x7B).
     *
     * Layout (7 bytes total):
     * - CRC16 (2 bytes)
     * - Type (1 byte): 0x7B
     * - Flags (2 bytes LE): 0x4000 (always set)
     * - Size (2 bytes LE): 7
     */
    private fun buildEndHeader(hasNextVolume: Boolean): ByteArray {
        var flags = 0x4000  // Always set in end header
        if (hasNextVolume) flags = flags or END_FLAG_NEXT_VOLUME

        val content = ByteArrayOutputStream()
        content.write(BLOCK_END)
        content.writeBytes(writeLE16(flags))
        content.writeBytes(writeLE16(7))

        val contentBytes = content.toByteArray()
        val crc = crc16(contentBytes)

        val out = ByteArrayOutputStream()
        out.writeBytes(writeLE16(crc))
        out.writeBytes(contentBytes)
        return out.toByteArray()
    }
}
