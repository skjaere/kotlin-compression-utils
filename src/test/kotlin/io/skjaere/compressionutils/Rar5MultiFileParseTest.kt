package io.skjaere.compressionutils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that Rar5Parser correctly discovers all files in multi-volume RAR5
 * archives containing more than one file — verifying the inferSplitPositions
 * optimization does not break multi-file archives.
 *
 * Constructs synthetic RAR5 volumes in memory with proper vint encoding.
 */
class Rar5MultiFileParseTest {

    private val service = RarArchiveService()

    // RAR5 signature
    private val rar5Signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x01, 0x00)

    /** Encode a value as a RAR5 vint (variable-length integer, 7 bits per byte, MSB = continuation). */
    private fun encodeVint(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v > 0) b = b or 0x80
            bytes.add(b.toByte())
        } while (v > 0)
        return bytes.toByteArray()
    }

    /**
     * Encode a value as a RAR5 vint with a minimum number of bytes (non-minimal / padded encoding).
     * Real RAR encoders sometimes pad vints to a fixed width.
     */
    private fun encodeVintPadded(value: Long, minBytes: Int): ByteArray {
        val minimal = encodeVint(value)
        if (minimal.size >= minBytes) return minimal
        val result = ByteArray(minBytes)
        var v = value
        for (i in 0 until minBytes) {
            result[i] = ((v and 0x7F) or (if (i < minBytes - 1) 0x80 else 0x00)).toByte()
            v = v ushr 7
        }
        return result
    }

    /**
     * Build a RAR5 main archive header block.
     * @param isVolume set bit 0x01 in arcFlags (multi-volume)
     * @param hasVolumeNumber set bit 0x02 in arcFlags + append volumeNumber vint
     * @param volumeNumber the volume number to encode (only when hasVolumeNumber=true)
     */
    private fun buildMainHeader(
        isVolume: Boolean = true,
        hasVolumeNumber: Boolean = false,
        volumeNumber: Int = 0
    ): ByteArray {
        val headerType = encodeVint(1) // RAR5_HEAD_MAIN
        val headerFlags = encodeVint(0) // no extra area, no data area

        // Header body: arcFlags [+ volumeNumber]
        var arcFlags = 0
        if (isVolume) arcFlags = arcFlags or 0x01
        if (hasVolumeNumber) arcFlags = arcFlags or 0x02
        val arcFlagsBytes = encodeVint(arcFlags.toLong())
        val volumeNumberBytes = if (hasVolumeNumber) encodeVint(volumeNumber.toLong()) else byteArrayOf()

        val headerContent = headerType + headerFlags + arcFlagsBytes + volumeNumberBytes
        val headerSize = encodeVint(headerContent.size.toLong())

        val crc = ByteArray(4) // dummy CRC
        return crc + headerSize + headerContent
    }

    /**
     * Build a RAR5 file header block with associated data.
     * @param filename the file name
     * @param dataSize size of file data in this volume
     * @param unpackedSize total uncompressed file size
     * @param splitBefore data continues from previous volume (block flag 0x08)
     * @param splitAfter data continues in next volume (block flag 0x10)
     * @param data explicit data bytes (random if null)
     * @param crc32 optional CRC32 of the full file (set fileFlags bit 0x04)
     */
    private fun buildFileBlock(
        filename: String,
        dataSize: Int,
        unpackedSize: Int,
        splitBefore: Boolean = false,
        splitAfter: Boolean = false,
        data: ByteArray? = null,
        crc32: Long? = null
    ): ByteArray {
        val headerType = encodeVint(2) // RAR5_HEAD_FILE

        // Block-level flags: 0x02 = has data area, 0x08 = split before, 0x10 = split after
        var blockFlags = 0x02 // always has data
        if (splitBefore) blockFlags = blockFlags or 0x08
        if (splitAfter) blockFlags = blockFlags or 0x10
        val headerFlagsBytes = encodeVint(blockFlags.toLong())

        val dataSizeBytes = encodeVint(dataSize.toLong())

        // File header body:
        // fileFlags(vint) + unpackedSize(vint) + attributes(vint) + [crc32(4)] +
        // compressionInfo(vint) + hostOS(vint) + nameLength(vint) + name
        var fileFlags = 0
        if (crc32 != null) fileFlags = fileFlags or 0x04
        val fileFlagsBytes = encodeVint(fileFlags.toLong())
        val unpackedSizeBytes = encodeVint(unpackedSize.toLong())
        val attributesBytes = encodeVint(0)
        val crcBytes = if (crc32 != null) {
            val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.putInt(crc32.toInt())
            buf.array()
        } else byteArrayOf()
        val compressionInfoBytes = encodeVint(0) // store (uncompressed)
        val hostOSBytes = encodeVint(0) // Windows
        val nameBytes = filename.toByteArray(Charsets.UTF_8)
        val nameLengthBytes = encodeVint(nameBytes.size.toLong())

        val headerBody = fileFlagsBytes + unpackedSizeBytes + attributesBytes + crcBytes +
                compressionInfoBytes + hostOSBytes + nameLengthBytes + nameBytes

        val headerContent = headerType + headerFlagsBytes + dataSizeBytes + headerBody
        val headerSize = encodeVint(headerContent.size.toLong())
        val crcField = ByteArray(4) // dummy CRC

        val fileData = data ?: ByteArray(dataSize) { (it % 251).toByte() }
        return crcField + headerSize + headerContent + fileData
    }

    /** Build a RAR5 end-of-archive block (headerType=5, no flags, no data). */
    private fun buildEndOfArchive(): ByteArray {
        val headerType = encodeVint(5)
        val headerFlags = encodeVint(0)
        val headerContent = headerType + headerFlags
        val headerSize = encodeVint(headerContent.size.toLong())
        val crc = ByteArray(4)
        return crc + headerSize + headerContent
    }

    /** Build a RAR5 service block (headerType=3) with the given data payload size. */
    private fun buildServiceBlock(dataSize: Int): ByteArray {
        val headerType = encodeVint(3) // RAR5_HEAD_SERVICE
        val headerFlags = encodeVint(0x02) // has data area
        val dataSizeBytes = encodeVint(dataSize.toLong())
        val headerContent = headerType + headerFlags + dataSizeBytes
        val headerSize = encodeVint(headerContent.size.toLong())
        val crc = ByteArray(4)
        val data = ByteArray(dataSize)
        return crc + headerSize + headerContent + data
    }

    /** Concatenate signature + blocks into one volume. */
    private fun buildVolume(vararg blocks: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(rar5Signature)
        for (block in blocks) buf.write(block)
        return buf.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `parse finds all files in multi-file multi-volume RAR5`() = runBlocking {
        // A split file (bigfile.dat) spanning volumes 0-1, plus a small file in volume 1.
        // The split file does NOT fill all volumes, so inferSplitPositions must not be used.
        val bigfileTotal = 200
        val bigfileChunk0 = 120
        val bigfileChunk1 = bigfileTotal - bigfileChunk0
        val smallSize = 30

        val vol0 = buildVolume(
            buildMainHeader(isVolume = true),
            buildFileBlock("bigfile.dat", dataSize = bigfileChunk0, unpackedSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlock("bigfile.dat", dataSize = bigfileChunk1, unpackedSize = bigfileTotal, splitBefore = true),
            buildFileBlock("small.txt", dataSize = smallSize, unpackedSize = smallSize),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong())

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(2, entries.size, "Should find both files, got: ${entries.map { it.path }}")

        val bigfile = entries.find { it.path == "bigfile.dat" }
        assertNotNull(bigfile, "Should find bigfile.dat")
        assertEquals(bigfileTotal.toLong(), bigfile.uncompressedSize)
        assertEquals(2, bigfile.splitParts.size, "bigfile should span 2 volumes")

        val small = entries.find { it.path == "small.txt" }
        assertNotNull(small, "Should find small.txt")
        assertEquals(smallSize.toLong(), small.uncompressedSize)
    }

    @Test
    fun `parse finds files across three volumes with multiple files`() = runBlocking {
        // Split file spans vol 0-1, additional files in vol 1 and vol 2.
        val bigfileTotal = 300
        val bigfileChunk0 = 200
        val bigfileChunk1 = bigfileTotal - bigfileChunk0

        val vol0 = buildVolume(
            buildMainHeader(isVolume = true),
            buildFileBlock("bigfile.dat", dataSize = bigfileChunk0, unpackedSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlock("bigfile.dat", dataSize = bigfileChunk1, unpackedSize = bigfileTotal, splitBefore = true),
            buildFileBlock("info.nfo", dataSize = 50, unpackedSize = 50),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 2),
            buildFileBlock("sample.mkv", dataSize = 100, unpackedSize = 100),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong(), vol2.size.toLong())

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(3, entries.size, "Should find all 3 files, got: ${entries.map { it.path }}")
        assertNotNull(entries.find { it.path == "bigfile.dat" })
        assertNotNull(entries.find { it.path == "info.nfo" })
        assertNotNull(entries.find { it.path == "sample.mkv" })
    }

    @Test
    fun `single file filling all volumes uses infer and reads correct data`() = runBlocking {
        // Single file spanning all 3 volumes — infer optimization SHOULD be used.
        // Verify data is at the correct positions by checking CRC.
        val totalSize = 600
        val fileData = ByteArray(totalSize) { (it % 251).toByte() }
        val crc = CRC32()
        crc.update(fileData)
        val expectedCrc = crc.value

        // Calculate how much data fits in each volume.
        // Volume layout: sig(8) + mainHeader + fileHeader + data + endOfArchive
        // We'll build volumes with specific chunk sizes.
        val chunk0 = 200
        val chunk1 = 200
        val chunk2 = totalSize - chunk0 - chunk1

        val vol0 = buildVolume(
            buildMainHeader(isVolume = true),
            buildFileBlock(
                "payload.bin", dataSize = chunk0, unpackedSize = totalSize,
                splitAfter = true, data = fileData.sliceArray(0 until chunk0),
                crc32 = expectedCrc
            ),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlock(
                "payload.bin", dataSize = chunk1, unpackedSize = totalSize,
                splitBefore = true, splitAfter = true,
                data = fileData.sliceArray(chunk0 until chunk0 + chunk1),
                crc32 = expectedCrc
            ),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 2),
            buildFileBlock(
                "payload.bin", dataSize = chunk2, unpackedSize = totalSize,
                splitBefore = true,
                data = fileData.sliceArray(chunk0 + chunk1 until totalSize),
                crc32 = expectedCrc
            ),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong(), vol2.size.toLong())

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("payload.bin", entry.path)
        assertEquals(totalSize.toLong(), entry.uncompressedSize)
        assertEquals(3, entry.splitParts.size, "Should have 3 split parts")

        // Read data from split positions and verify CRC
        val reassembled = ByteArrayOutputStream()
        for (part in entry.splitParts) {
            stream.seek(part.dataStartPosition)
            val buf = ByteArray(part.dataSize.toInt())
            stream.read(buf, 0, buf.size)
            reassembled.write(buf)
        }
        val reassembledBytes = reassembled.toByteArray()
        assertEquals(totalSize, reassembledBytes.size, "Reassembled data size mismatch")

        val actualCrc = CRC32()
        actualCrc.update(reassembledBytes)
        assertEquals(expectedCrc, actualCrc.value, "CRC mismatch — inferred split positions are wrong")
    }

    @Test
    fun `multi-file archive without volumeSizes falls back to normal parsing`() = runBlocking {
        // Same layout as the first test, but without volumeSizes.
        // Parser must walk all volumes.
        val bigfileTotal = 200
        val bigfileChunk0 = 120
        val bigfileChunk1 = bigfileTotal - bigfileChunk0
        val smallSize = 30

        val vol0 = buildVolume(
            buildMainHeader(isVolume = true),
            buildFileBlock("bigfile.dat", dataSize = bigfileChunk0, unpackedSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlock("bigfile.dat", dataSize = bigfileChunk1, unpackedSize = bigfileTotal, splitBefore = true),
            buildFileBlock("small.txt", dataSize = smallSize, unpackedSize = smallSize),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1
        val stream = ByteArraySeekableInputStream(concatenated)

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = null
        )

        assertEquals(2, entries.size, "Should find both files without volumeSizes, got: ${entries.map { it.path }}")
        assertNotNull(entries.find { it.path == "bigfile.dat" })
        assertNotNull(entries.find { it.path == "small.txt" })
    }

    @Test
    fun `infer with volume number difference produces correct positions`() = runBlocking {
        // Volume 0 has no volume number (isVolume=true, hasVolumeNumber=false).
        // Continuation volumes have volume number (extra vint).
        // This mimics real-world RAR5 archives where main header size differs.
        // Single file fills all volumes — infer should handle the size difference.
        val totalSize = 500
        val fileData = ByteArray(totalSize) { (it % 251).toByte() }
        val crc = CRC32()
        crc.update(fileData)
        val expectedCrc = crc.value

        val chunk0 = 200
        val chunk1 = 200
        val chunk2 = totalSize - chunk0 - chunk1

        val vol0 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = false),
            buildFileBlock(
                "movie.mkv", dataSize = chunk0, unpackedSize = totalSize,
                splitAfter = true, data = fileData.sliceArray(0 until chunk0),
                crc32 = expectedCrc
            ),
            buildEndOfArchive()
        )

        // Continuation volumes have volume number — main header is 1 byte larger
        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlock(
                "movie.mkv", dataSize = chunk1, unpackedSize = totalSize,
                splitBefore = true, splitAfter = true,
                data = fileData.sliceArray(chunk0 until chunk0 + chunk1),
                crc32 = expectedCrc
            ),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 2),
            buildFileBlock(
                "movie.mkv", dataSize = chunk2, unpackedSize = totalSize,
                splitBefore = true,
                data = fileData.sliceArray(chunk0 + chunk1 until totalSize),
                crc32 = expectedCrc
            ),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong(), vol2.size.toLong())

        // Verify vol0 main header is smaller than vol1 (no volume number vs has volume number)
        val vol0MainSize = buildMainHeader(isVolume = true, hasVolumeNumber = false).size
        val vol1MainSize = buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1).size
        assertTrue(vol1MainSize > vol0MainSize, "Continuation volume main header should be larger")

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(3, entry.splitParts.size)

        // Verify data at inferred positions matches original
        val reassembled = ByteArrayOutputStream()
        for (part in entry.splitParts) {
            stream.seek(part.dataStartPosition)
            val buf = ByteArray(part.dataSize.toInt())
            stream.read(buf, 0, buf.size)
            reassembled.write(buf)
        }

        val actualCrc = CRC32()
        actualCrc.update(reassembled.toByteArray())
        assertEquals(expectedCrc, actualCrc.value, "CRC mismatch — volume number size difference not handled correctly")
    }

    /**
     * Build a RAR5 file header block using a padded (non-minimal) vint for the data area size.
     * This simulates real RAR encoders that pad the dataAreaSize vint to a fixed width.
     */
    private fun buildFileBlockPadded(
        filename: String,
        dataSize: Int,
        unpackedSize: Int,
        splitBefore: Boolean = false,
        splitAfter: Boolean = false,
        data: ByteArray? = null,
        crc32: Long? = null,
        dataSizeVintMinBytes: Int = 4
    ): ByteArray {
        val headerType = encodeVint(2) // RAR5_HEAD_FILE

        var blockFlags = 0x02 // has data
        if (splitBefore) blockFlags = blockFlags or 0x08
        if (splitAfter) blockFlags = blockFlags or 0x10
        val headerFlagsBytes = encodeVint(blockFlags.toLong())

        // Use padded vint for data area size
        val dataSizeBytes = encodeVintPadded(dataSize.toLong(), dataSizeVintMinBytes)

        var fileFlags = 0
        if (crc32 != null) fileFlags = fileFlags or 0x04
        val fileFlagsBytes = encodeVint(fileFlags.toLong())
        val unpackedSizeBytes = encodeVint(unpackedSize.toLong())
        val attributesBytes = encodeVint(0)
        val crcBytes = if (crc32 != null) {
            val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.putInt(crc32.toInt())
            buf.array()
        } else byteArrayOf()
        val compressionInfoBytes = encodeVint(0) // store
        val hostOSBytes = encodeVint(0)
        val nameBytes = filename.toByteArray(Charsets.UTF_8)
        val nameLengthBytes = encodeVint(nameBytes.size.toLong())

        val headerBody = fileFlagsBytes + unpackedSizeBytes + attributesBytes + crcBytes +
                compressionInfoBytes + hostOSBytes + nameLengthBytes + nameBytes

        val headerContent = headerType + headerFlagsBytes + dataSizeBytes + headerBody
        val headerSize = encodeVint(headerContent.size.toLong())
        val crcField = ByteArray(4) // dummy CRC

        val fileData = data ?: ByteArray(dataSize) { (it % 251).toByte() }
        return crcField + headerSize + headerContent + fileData
    }

    @Test
    fun `infer handles non-minimal padded vint encoding for data area size`() = runBlocking {
        // RAR encoders may pad the dataAreaSize vint to a fixed width (e.g., 4 bytes instead of
        // the minimal 2 bytes for values around 200). The inferSplitPositions function uses
        // vintLength() which returns minimal encoding length, causing position miscalculation.
        val totalSize = 500
        val fileData = ByteArray(totalSize) { (it % 251).toByte() }
        val crc = CRC32()
        crc.update(fileData)
        val expectedCrc = crc.value

        val chunk0 = 200
        val chunk1 = 200
        val chunk2 = totalSize - chunk0 - chunk1 // 100

        val padBytes = 4 // pad dataAreaSize vints to 4 bytes (minimal would be 1-2 bytes)

        val vol0 = buildVolume(
            buildMainHeader(isVolume = true),
            buildFileBlockPadded(
                "payload.bin", dataSize = chunk0, unpackedSize = totalSize,
                splitAfter = true, data = fileData.sliceArray(0 until chunk0),
                crc32 = expectedCrc, dataSizeVintMinBytes = padBytes
            ),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlockPadded(
                "payload.bin", dataSize = chunk1, unpackedSize = totalSize,
                splitBefore = true, splitAfter = true,
                data = fileData.sliceArray(chunk0 until chunk0 + chunk1),
                crc32 = expectedCrc, dataSizeVintMinBytes = padBytes
            ),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 2),
            buildFileBlockPadded(
                "payload.bin", dataSize = chunk2, unpackedSize = totalSize,
                splitBefore = true,
                data = fileData.sliceArray(chunk0 + chunk1 until totalSize),
                crc32 = expectedCrc, dataSizeVintMinBytes = padBytes
            ),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong(), vol2.size.toLong())

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("payload.bin", entry.path)
        assertEquals(totalSize.toLong(), entry.uncompressedSize)
        assertEquals(3, entry.splitParts.size, "Should have 3 split parts")

        // Verify split data sizes sum to total file size
        val totalDataFromSplits = entry.splitParts.sumOf { it.dataSize }
        assertEquals(
            totalSize.toLong(), totalDataFromSplits,
            "Split data sizes should sum to file size (got $totalDataFromSplits)"
        )

        // Verify data at inferred positions matches original (CRC check)
        val reassembled = ByteArrayOutputStream()
        for (part in entry.splitParts) {
            stream.seek(part.dataStartPosition)
            val buf = ByteArray(part.dataSize.toInt())
            stream.read(buf, 0, buf.size)
            reassembled.write(buf)
        }
        val reassembledBytes = reassembled.toByteArray()
        assertEquals(totalSize, reassembledBytes.size, "Reassembled data size mismatch")

        val actualCrc = CRC32()
        actualCrc.update(reassembledBytes)
        assertEquals(expectedCrc, actualCrc.value, "CRC mismatch — padded vint positions are wrong")
    }

    @Test
    fun `infer handles different end-of-archive section size in last volume`() = runBlocking {
        // Real RAR archives have a larger end-of-archive section in full volumes
        // (service block + end-of-archive + zero padding) than in the last volume
        // (smaller service block + end-of-archive, no padding).
        // The inferSplitPositions function computes endOfArchiveSize from volume 0,
        // so it must not use that value for the last volume's data size calculation.
        val totalSize = 600
        val fileData = ByteArray(totalSize) { (it % 251).toByte() }
        val crc = CRC32()
        crc.update(fileData)
        val expectedCrc = crc.value

        // Full volumes get a service block (20 bytes data) + end-of-archive + 4 bytes of zero padding.
        // Last volume gets a smaller service block (15 bytes data) + end-of-archive, no padding.
        val fullVolumeServiceBlock = buildServiceBlock(20)
        val lastVolumeServiceBlock = buildServiceBlock(15)
        val zeroPadding = ByteArray(4)
        val endOfArchive = buildEndOfArchive()

        // Build volume 0 to determine data chunk size
        val vol0Header = buildMainHeader(isVolume = true)
        val vol0FileHeaderProbe = buildFileBlock(
            "movie.mkv", dataSize = 0, unpackedSize = totalSize,
            splitAfter = true, data = ByteArray(0), crc32 = expectedCrc
        )
        // Overhead = sig + mainHeader + fileHeaderBlock (without data) + service + endOfArchive + padding
        val vol0Overhead = rar5Signature.size + vol0Header.size + vol0FileHeaderProbe.size +
                fullVolumeServiceBlock.size + endOfArchive.size + zeroPadding.size

        // Target a volume size that fits ~250 bytes of data
        val targetVolumeSize = vol0Overhead + 250
        val chunk0 = targetVolumeSize - vol0Overhead

        // Continuation volume overhead (main header is 1 byte larger due to volume number)
        val contHeader = buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1)
        val contFileHeaderProbe = buildFileBlock(
            "movie.mkv", dataSize = 0, unpackedSize = totalSize,
            splitBefore = true, splitAfter = true, data = ByteArray(0), crc32 = expectedCrc
        )
        val contOverhead = rar5Signature.size + contHeader.size + contFileHeaderProbe.size +
                fullVolumeServiceBlock.size + endOfArchive.size + zeroPadding.size
        val chunk1 = targetVolumeSize - contOverhead

        val chunk2 = totalSize - chunk0 - chunk1
        assertTrue(chunk2 > 0, "Last chunk must be positive (got $chunk2)")
        assertTrue(chunk2 < chunk1, "Last chunk should be smaller than full chunks")

        val vol0 = buildVolume(
            vol0Header,
            buildFileBlock(
                "movie.mkv", dataSize = chunk0, unpackedSize = totalSize,
                splitAfter = true, data = fileData.sliceArray(0 until chunk0),
                crc32 = expectedCrc
            ),
            fullVolumeServiceBlock,
            endOfArchive,
            zeroPadding
        )

        val vol1 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 1),
            buildFileBlock(
                "movie.mkv", dataSize = chunk1, unpackedSize = totalSize,
                splitBefore = true, splitAfter = true,
                data = fileData.sliceArray(chunk0 until chunk0 + chunk1),
                crc32 = expectedCrc
            ),
            fullVolumeServiceBlock,
            endOfArchive,
            zeroPadding
        )

        // Last volume: smaller service block, no zero padding
        val vol2 = buildVolume(
            buildMainHeader(isVolume = true, hasVolumeNumber = true, volumeNumber = 2),
            buildFileBlock(
                "movie.mkv", dataSize = chunk2, unpackedSize = totalSize,
                splitBefore = true,
                data = fileData.sliceArray(chunk0 + chunk1 until totalSize),
                crc32 = expectedCrc
            ),
            lastVolumeServiceBlock,
            endOfArchive
        )

        // Verify the end sections actually differ
        val vol0EndSection = fullVolumeServiceBlock.size + endOfArchive.size + zeroPadding.size
        val vol2EndSection = lastVolumeServiceBlock.size + endOfArchive.size
        assertTrue(vol0EndSection > vol2EndSection,
            "Full volume end section ($vol0EndSection) should be larger than last volume ($vol2EndSection)")

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong(), vol2.size.toLong())

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("movie.mkv", entry.path)
        assertEquals(totalSize.toLong(), entry.uncompressedSize)
        assertEquals(3, entry.splitParts.size, "Should have 3 split parts")

        // Verify split data sizes sum to total file size
        val totalDataFromSplits = entry.splitParts.sumOf { it.dataSize }
        assertEquals(
            totalSize.toLong(), totalDataFromSplits,
            "Split data sizes should sum to file size (got $totalDataFromSplits)"
        )

        // Verify data at inferred positions matches original (CRC check)
        val reassembled = ByteArrayOutputStream()
        for (part in entry.splitParts) {
            stream.seek(part.dataStartPosition)
            val buf = ByteArray(part.dataSize.toInt())
            stream.read(buf, 0, buf.size)
            reassembled.write(buf)
        }
        val reassembledBytes = reassembled.toByteArray()
        assertEquals(totalSize, reassembledBytes.size, "Reassembled data size mismatch")

        val actualCrc = CRC32()
        actualCrc.update(reassembledBytes)
        assertEquals(expectedCrc, actualCrc.value,
            "CRC mismatch — last volume end-of-archive size difference not handled correctly")
    }
}
