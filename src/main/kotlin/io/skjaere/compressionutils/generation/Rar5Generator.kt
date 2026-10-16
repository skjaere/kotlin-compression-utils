package io.skjaere.compressionutils.generation

import io.skjaere.compressionutils.generation.BinaryWriteUtils.crc32
import io.skjaere.compressionutils.generation.BinaryWriteUtils.encodeVInt
import io.skjaere.compressionutils.generation.BinaryWriteUtils.writeLE32
import java.io.ByteArrayOutputStream

internal object Rar5Generator {

    private val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    // Header types
    private const val HEAD_MAIN = 1
    private const val HEAD_FILE = 2
    private const val HEAD_ENDARC = 5

    // Header flag: has data area
    private const val HF_HAS_DATA = 0x02L

    // Archive flags (main header): a volume of a set, and its volume number given (all but the first).
    private const val ARCHIVE_FLAG_VOLUME = 0x01L
    private const val ARCHIVE_FLAG_VOLUME_NUMBER = 0x02L

    // End-of-archive flag: another volume follows.
    private const val END_FLAG_MORE_VOLUMES = 0x01L

    // File flags
    private const val FILE_FLAG_HAS_CRC = 0x04L
    private const val FILE_FLAG_SPLIT_BEFORE = 0x08L
    private const val FILE_FLAG_SPLIT_AFTER = 0x10L

    fun generate(data: ByteArray, numberOfVolumes: Int, filename: String): List<ArchiveVolume> {
        if (numberOfVolumes <= 1) {
            val archiveBytes = generateSingleVolume(data, filename)
            return listOf(ArchiveVolume("archive.rar", archiveBytes))
        }
        return generateMultiVolume(data, numberOfVolumes, filename)
    }

    fun generateWithVolumeSize(data: ByteArray, volumeSize: Long, filename: String): List<ArchiveVolume> {
        // Fixed overhead: SIGNATURE(8) + mainHeader + endHeader
        val fixedOverhead = SIGNATURE.size + buildMainHeader().size + buildEndHeader().size

        // Try to fit as single volume first
        val singleFileHeader = buildFileHeader(data, filename, fileFlags = FILE_FLAG_HAS_CRC)
        val singleVolumeSize = fixedOverhead + singleFileHeader.size + data.size
        if (singleVolumeSize <= volumeSize) {
            val archiveBytes = generateSingleVolume(data, filename)
            return listOf(ArchiveVolume("archive.rar", archiveBytes))
        }

        return generateMultiVolumeBySize(data, volumeSize, filename)
    }

    private fun generateMultiVolumeBySize(data: ByteArray, volumeSize: Long, filename: String): List<ArchiveVolume> {
        val volumes = mutableListOf<ArchiveVolume>()
        var offset = 0
        var volIdx = 0

        while (offset < data.size) {
            val isFirst = volIdx == 0
            val remaining = data.size - offset
            // Each volume's main header gives its number (but the first's), so it grows with it.
            val mainHeader = buildMainHeader(volIdx)
            val fixedOverhead = SIGNATURE.size + mainHeader.size + buildEndHeader().size

            // Determine fileFlags for this volume (we don't know isLast yet, but we need it for header size)
            // Try with SPLIT_AFTER first, then adjust if this is the last volume
            var fileFlags = FILE_FLAG_HAS_CRC or FILE_FLAG_SPLIT_AFTER
            if (!isFirst) fileFlags = fileFlags or FILE_FLAG_SPLIT_BEFORE

            // Iterative approach: build file header with a trial data size to determine actual header size
            var trialDataSize = volumeSize - fixedOverhead // rough upper bound
            var fileHeader = buildFileHeader(data, filename, fileFlags, trialDataSize)
            var dataForThisVolume = volumeSize - fixedOverhead - fileHeader.size

            // Rebuild with correct data size in case vint encoding changed
            fileHeader = buildFileHeader(data, filename, fileFlags, dataForThisVolume)
            dataForThisVolume = volumeSize - fixedOverhead - fileHeader.size

            require(dataForThisVolume > 0) {
                "volumeSize ($volumeSize) too small to fit any data with RAR5 overhead"
            }

            val chunkSize = minOf(dataForThisVolume.toInt(), remaining)
            val isLast = offset + chunkSize >= data.size

            // If this is the last volume, rebuild without SPLIT_AFTER
            if (isLast) {
                fileFlags = FILE_FLAG_HAS_CRC
                if (!isFirst) fileFlags = fileFlags or FILE_FLAG_SPLIT_BEFORE
                fileHeader = buildFileHeader(data, filename, fileFlags, chunkSize.toLong())
            }

            val chunk = data.copyOfRange(offset, offset + chunkSize)

            val vol = ByteArrayOutputStream()
            vol.write(SIGNATURE)
            vol.write(mainHeader)
            vol.write(fileHeader)
            vol.write(chunk)
            vol.write(buildEndHeader(moreVolumes = !isLast))

            val volFilename = "archive.part${volIdx + 1}.rar"
            volumes.add(ArchiveVolume(volFilename, vol.toByteArray()))
            offset += chunkSize
            volIdx++
        }

        return volumes
    }

    private fun generateSingleVolume(data: ByteArray, filename: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(buildMainHeader())
        out.write(buildFileHeader(data, filename, fileFlags = FILE_FLAG_HAS_CRC))
        out.write(data)
        out.write(buildEndHeader())
        return out.toByteArray()
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

            var fileFlags = FILE_FLAG_HAS_CRC
            if (!isLast) fileFlags = fileFlags or FILE_FLAG_SPLIT_AFTER
            if (!isFirst) fileFlags = fileFlags or FILE_FLAG_SPLIT_BEFORE

            val vol = ByteArrayOutputStream()
            vol.write(SIGNATURE)
            vol.write(buildMainHeader(volIdx))
            vol.write(buildFileHeader(
                fullData = data,
                filename = filename,
                fileFlags = fileFlags,
                dataSize = chunk.size.toLong()
            ))
            vol.write(chunk)
            vol.write(buildEndHeader(moreVolumes = !isLast))

            val volFilename = "archive.part${volIdx + 1}.rar"
            volumes.add(ArchiveVolume(volFilename, vol.toByteArray()))
            offset = end
        }

        return volumes
    }

    fun generateMultiFile(files: List<ArchiveFile>, volumeSize: Long): List<ArchiveVolume> {
        val mainHeader = buildMainHeader()
        val endHeader = buildEndHeader()
        val fixedOverhead = SIGNATURE.size + mainHeader.size + endHeader.size

        // Check if everything fits in a single volume
        val totalSingleVolumeSize = fixedOverhead + files.sumOf { file ->
            val header = buildFileHeader(file.data, file.filename, FILE_FLAG_HAS_CRC)
            (header.size + file.data.size).toLong()
        }

        if (totalSingleVolumeSize <= volumeSize) {
            val out = ByteArrayOutputStream()
            out.write(SIGNATURE)
            out.write(mainHeader)
            for (file in files) {
                out.write(buildFileHeader(file.data, file.filename, FILE_FLAG_HAS_CRC))
                out.write(file.data)
            }
            out.write(endHeader)
            return listOf(ArchiveVolume("archive.rar", out.toByteArray()))
        }

        return generateMultiVolumeMultiFile(files, volumeSize)
    }

    private fun generateMultiVolumeMultiFile(files: List<ArchiveFile>, volumeSize: Long): List<ArchiveVolume> {
        val volumes = mutableListOf<ArchiveVolume>()
        var fileIdx = 0
        var fileOffset = 0
        var volIdx = 0

        while (fileIdx < files.size) {
            val mainHeader = buildMainHeader(volIdx)
            val vol = ByteArrayOutputStream()
            vol.write(SIGNATURE)
            vol.write(mainHeader)

            var remaining = volumeSize - (SIGNATURE.size + mainHeader.size + buildEndHeader().size)

            while (fileIdx < files.size && remaining > 0) {
                val file = files[fileIdx]
                val isFirstChunk = fileOffset == 0
                val dataRemaining = file.data.size - fileOffset

                // Build trial header with worst-case flags (SPLIT_AFTER) to get upper bound on header size
                var trialFlags = FILE_FLAG_HAS_CRC or FILE_FLAG_SPLIT_AFTER
                if (!isFirstChunk) trialFlags = trialFlags or FILE_FLAG_SPLIT_BEFORE

                val trialHeader = buildFileHeader(file.data, file.filename, trialFlags, remaining)
                val availableForData = remaining - trialHeader.size
                if (availableForData <= 0) break // can't fit even a header

                val chunkSize = minOf(availableForData.toInt(), dataRemaining)
                val isLastChunk = fileOffset + chunkSize >= file.data.size

                // Build actual header with correct flags
                var fileFlags = FILE_FLAG_HAS_CRC
                if (!isFirstChunk) fileFlags = fileFlags or FILE_FLAG_SPLIT_BEFORE
                if (!isLastChunk) fileFlags = fileFlags or FILE_FLAG_SPLIT_AFTER

                val actualHeader = buildFileHeader(file.data, file.filename, fileFlags, chunkSize.toLong())

                vol.write(actualHeader)
                vol.write(file.data, fileOffset, chunkSize)
                remaining -= actualHeader.size + chunkSize

                fileOffset += chunkSize
                if (isLastChunk) {
                    fileIdx++
                    fileOffset = 0
                } else {
                    break // file split, continue in next volume
                }
            }

            vol.write(buildEndHeader(moreVolumes = fileIdx < files.size))
            val volFilename = "archive.part${volIdx + 1}.rar"
            volumes.add(ArchiveVolume(volFilename, vol.toByteArray()))
            volIdx++
        }

        return volumes
    }

    /** Build a RAR5 header block: CRC32(4) + body. CRC32 covers everything from headerSize onward. */
    private fun buildHeader(type: Int, headerFlags: Long, bodyContent: ByteArray, dataSize: Long?): ByteArray {
        // Build the inner content (everything after CRC32): headerSize(vint) + type(vint) + flags(vint) + [extra/data sizes] + body
        val inner = ByteArrayOutputStream()

        val actualFlags = if (dataSize != null) headerFlags or HF_HAS_DATA else headerFlags

        // Build header content (type + flags + optional data size + body) to calculate headerSize
        val headerContent = ByteArrayOutputStream()
        headerContent.write(encodeVInt(type.toLong()))
        headerContent.write(encodeVInt(actualFlags))
        if (dataSize != null) {
            headerContent.write(encodeVInt(dataSize))
        }
        headerContent.write(bodyContent)
        val headerContentBytes = headerContent.toByteArray()

        // headerSize = size of headerContent
        val headerSizeVint = encodeVInt(headerContentBytes.size.toLong())

        inner.write(headerSizeVint)
        inner.write(headerContentBytes)

        val innerBytes = inner.toByteArray()
        val crc = crc32(innerBytes)

        val out = ByteArrayOutputStream()
        out.write(writeLE32(crc))
        out.write(innerBytes)
        return out.toByteArray()
    }

    /**
     * The main archive header of volume [volIdx] of a set (0 for the first), or of a single-volume
     * archive when null: its archive flags, and the volume number but in the first volume's.
     */
    private fun buildMainHeader(volIdx: Int? = null): ByteArray {
        val body = when (volIdx) {
            null -> encodeVInt(0)
            0 -> encodeVInt(ARCHIVE_FLAG_VOLUME)
            else -> encodeVInt(ARCHIVE_FLAG_VOLUME or ARCHIVE_FLAG_VOLUME_NUMBER) + encodeVInt(volIdx.toLong())
        }
        return buildHeader(HEAD_MAIN, 0L, body, null)
    }

    /** The end-of-archive header: its flags, which say whether another volume follows. */
    private fun buildEndHeader(moreVolumes: Boolean = false): ByteArray =
        buildHeader(HEAD_ENDARC, 0L, encodeVInt(if (moreVolumes) END_FLAG_MORE_VOLUMES else 0L), null)

    /**
     * Build a RAR5 file header.
     *
     * The parser reads (in parseFileHeader):
     * 1. fileFlags (vint)
     * 2. unpackedSize (vint)
     * 3. attributes (vint)
     * 4. optional: mtime (4 bytes LE) if fileFlags & 0x02
     * 5. optional: crc32 (4 bytes LE) if fileFlags & 0x04 (HAS_CRC)
     * 6. compressionInfo (vint) - 0 for store
     * 7. hostOS (vint) - 0 for Windows
     * 8. nameLength (vint)
     * 9. name (bytes)
     */
    private fun buildFileHeader(
        fullData: ByteArray,
        filename: String,
        fileFlags: Long,
        dataSize: Long = fullData.size.toLong()
    ): ByteArray {
        val nameBytes = filename.toByteArray(Charsets.UTF_8)
        val dataCrc = crc32(fullData)

        val body = ByteArrayOutputStream()
        body.write(encodeVInt(fileFlags))
        body.write(encodeVInt(fullData.size.toLong()))        // unpacked size (full file size)
        body.write(encodeVInt(0))                             // attributes = 0

        // CRC32 (if HAS_CRC flag set)
        if (fileFlags and FILE_FLAG_HAS_CRC != 0L) {
            body.write(writeLE32(dataCrc))
        }

        body.write(encodeVInt(0))                             // compressionInfo = 0 (store)
        body.write(encodeVInt(0))                             // hostOS = 0
        body.write(encodeVInt(nameBytes.size.toLong()))       // name length
        body.write(nameBytes)                                 // name

        return buildHeader(HEAD_FILE, 0L, body.toByteArray(), dataSize)
    }
}
