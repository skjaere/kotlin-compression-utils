package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that Rar4Parser correctly discovers files that begin in later volumes
 * of a multi-volume RAR4 archive, after a large split file has been inferred
 * via the volumeSizes optimization.
 *
 * We construct synthetic RAR4 volumes in memory to exercise the exact parser
 * code paths without needing real archive files.
 */
class Rar4MultiFileParseTest {

    private val service = RarArchiveService()

    // RAR4 signature: "Rar!\x1a\x07\x00"
    private val rar4Signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x00)

    /**
     * Builds a RAR4 archive header block (type 0x73).
     * Standard 13-byte block: 7-byte base header + 6 bytes reserved data.
     */
    private fun buildArchiveHeader(multiVolume: Boolean = true): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x00, 0x00))          // CRC (dummy)
        buf.write(0x73)                               // Block type
        val flags = if (multiVolume) 0x0001 else 0x0000
        buf.write(flags and 0xFF)
        buf.write((flags shr 8) and 0xFF)
        buf.write(byteArrayOf(0x0D, 0x00))           // Block size = 13 (LE)
        buf.write(ByteArray(6))                        // Reserved data
        return buf.toByteArray()
    }

    /**
     * Builds a RAR4 file header block (type 0x74) with associated data placeholder.
     * Returns the complete block (header + data bytes).
     */
    private fun buildFileBlock(
        filename: String,
        packSize: Int,
        unpackSize: Int,
        splitBefore: Boolean = false,
        splitAfter: Boolean = false,
        method: Int = 0x30, // 0x30 = store (uncompressed)
        data: ByteArray? = null
    ): ByteArray {
        val nameBytes = filename.toByteArray(Charsets.UTF_8)
        val headerDataSize = 25 + nameBytes.size
        val blockSize = 7 + headerDataSize

        var flags = 0
        if (splitBefore) flags = flags or 0x01
        if (splitAfter) flags = flags or 0x02

        val buf = ByteArrayOutputStream()

        // --- Base header (7 bytes) ---
        buf.write(byteArrayOf(0x00, 0x00))       // CRC (dummy)
        buf.write(0x74)                            // Block type: file header
        buf.write(flags and 0xFF)
        buf.write((flags shr 8) and 0xFF)
        // Block size (LE)
        buf.write(blockSize and 0xFF)
        buf.write((blockSize shr 8) and 0xFF)

        // --- Header data (25 + nameLength bytes) ---
        val headerData = ByteBuffer.allocate(headerDataSize).order(ByteOrder.LITTLE_ENDIAN)
        headerData.putInt(packSize)                // Pack size
        headerData.putInt(unpackSize)              // Unpack size
        headerData.put(0)                           // Host OS
        headerData.putInt(0)                        // File CRC
        headerData.putInt(0)                        // File time
        headerData.put(29)                          // Unpack version
        headerData.put(method.toByte())             // Method
        headerData.putShort(nameBytes.size.toShort()) // Name length
        headerData.putInt(0)                        // Attributes
        headerData.put(nameBytes)                   // Filename
        buf.write(headerData.array())

        // --- File data ---
        val fileData = data ?: ByteArray(packSize) { (it % 251).toByte() }
        buf.write(fileData)

        return buf.toByteArray()
    }

    /**
     * Builds a RAR4 end-of-archive block (type 0x7B). Always 7 bytes.
     */
    private fun buildEndOfArchive(): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x00, 0x00))  // CRC (dummy)
        buf.write(0x7B)                       // Block type: end of archive
        buf.write(byteArrayOf(0x00, 0x00))   // Flags
        buf.write(byteArrayOf(0x07, 0x00))   // Block size = 7 (LE)
        return buf.toByteArray()
    }

    /**
     * Builds one complete RAR4 volume from a list of blocks.
     */
    private fun buildVolume(vararg blocks: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(rar4Signature)
        for (block in blocks) {
            buf.write(block)
        }
        return buf.toByteArray()
    }

    /**
     * Core scenario: A split file spanning 3 volumes (with volumeSizes optimization)
     * and an additional non-split file in the last volume after the split file ends.
     *
     * Volume layout:
     *   Vol 0: [sig][archHdr][fileHdr bigfile SPLIT_AFTER][data 80B][endArchive]
     *   Vol 1: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE+AFTER][data 80B][endArchive]
     *   Vol 2: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE][data 40B][fileHdr small.txt][data 20B][endArchive]
     *
     * Without the fix, the parser infers bigfile's split positions from volumeSizes
     * then breaks, never reaching small.txt in volume 2.
     */
    @Test
    fun `parse finds additional files in same volume where split file ends`() {
        val bigfileTotal = 200
        val bigfileChunk0 = 80
        val bigfileChunk1 = 80
        val bigfileChunk2 = bigfileTotal - bigfileChunk0 - bigfileChunk1 // 40
        val smallSize = 20

        val vol0 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk0, unpackSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk1, unpackSize = bigfileTotal, splitBefore = true, splitAfter = true),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk2, unpackSize = bigfileTotal, splitBefore = true),
            buildFileBlock("small.txt", packSize = smallSize, unpackSize = smallSize),
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

        assertEquals(2, entries.size, "Should find both bigfile.dat and small.txt, got: ${entries.map { it.path }}")

        val bigfile = entries.find { it.path == "bigfile.dat" }
        assertNotNull(bigfile, "Should find bigfile.dat")
        assertEquals(bigfileTotal.toLong(), bigfile.uncompressedSize)
        assertTrue(bigfile.splitParts.isNotEmpty(), "bigfile should have split parts")
        assertEquals(3, bigfile.splitParts.size, "bigfile should span 3 volumes")

        val small = entries.find { it.path == "small.txt" }
        assertNotNull(small, "Should find small.txt")
        assertEquals(smallSize.toLong(), small.uncompressedSize)
    }

    /**
     * Additional files exist in a completely new volume after the split file ends.
     *
     * Volume layout:
     *   Vol 0: [sig][archHdr][fileHdr bigfile SPLIT_AFTER][data 100B][endArchive]
     *   Vol 1: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE][data 50B][endArchive]
     *   Vol 2: [sig][archHdr][fileHdr extra.txt][data 30B][endArchive]
     */
    @Test
    fun `parse finds files in volumes after the split file has ended`() {
        val bigfileTotal = 150
        val bigfileChunk0 = 100
        val bigfileChunk1 = bigfileTotal - bigfileChunk0 // 50
        val extraSize = 30

        val vol0 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk0, unpackSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk1, unpackSize = bigfileTotal, splitBefore = true),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("extra.txt", packSize = extraSize, unpackSize = extraSize),
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

        assertEquals(2, entries.size, "Should find both bigfile.dat and extra.txt, got: ${entries.map { it.path }}")

        val bigfile = entries.find { it.path == "bigfile.dat" }
        assertNotNull(bigfile, "Should find bigfile.dat")
        assertEquals(bigfileTotal.toLong(), bigfile.uncompressedSize)

        val extra = entries.find { it.path == "extra.txt" }
        assertNotNull(extra, "Should find extra.txt")
        assertEquals(extraSize.toLong(), extra.uncompressedSize)
    }

    /**
     * Multiple additional files after the split, spanning multiple volumes.
     *
     * Volume layout:
     *   Vol 0: [sig][archHdr][fileHdr bigfile SPLIT_AFTER][data 80B][endArchive]
     *   Vol 1: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE][data 20B][fileHdr a.txt][data 10B][fileHdr b.txt][data 15B][endArchive]
     *   Vol 2: [sig][archHdr][fileHdr c.txt][data 25B][endArchive]
     */
    @Test
    fun `parse finds multiple additional files across volumes after split file`() {
        val bigfileTotal = 100
        val bigfileChunk0 = 80
        val bigfileChunk1 = bigfileTotal - bigfileChunk0 // 20

        val vol0 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk0, unpackSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk1, unpackSize = bigfileTotal, splitBefore = true),
            buildFileBlock("a.txt", packSize = 10, unpackSize = 10),
            buildFileBlock("b.txt", packSize = 15, unpackSize = 15),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("c.txt", packSize = 25, unpackSize = 25),
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

        assertEquals(4, entries.size, "Should find all 4 files, got: ${entries.map { it.path }}")

        assertNotNull(entries.find { it.path == "bigfile.dat" }, "Should find bigfile.dat")
        assertNotNull(entries.find { it.path == "a.txt" }, "Should find a.txt")
        assertNotNull(entries.find { it.path == "b.txt" }, "Should find b.txt")
        assertNotNull(entries.find { it.path == "c.txt" }, "Should find c.txt")
    }

    /**
     * Without volumeSizes, the parser falls back to parsing each volume header.
     * This should still find all files.
     */
    @Test
    fun `parse finds all files without volumeSizes optimization`() {
        val bigfileTotal = 200
        val bigfileChunk0 = 80
        val bigfileChunk1 = 80
        val bigfileChunk2 = bigfileTotal - bigfileChunk0 - bigfileChunk1
        val smallSize = 20

        val vol0 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk0, unpackSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk1, unpackSize = bigfileTotal, splitBefore = true, splitAfter = true),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk2, unpackSize = bigfileTotal, splitBefore = true),
            buildFileBlock("small.txt", packSize = smallSize, unpackSize = smallSize),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)

        // No volumeSizes → no split inference → parser must walk all volumes
        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = null
        )

        assertEquals(2, entries.size, "Should find both files without volumeSizes, got: ${entries.map { it.path }}")
        assertNotNull(entries.find { it.path == "bigfile.dat" })
        assertNotNull(entries.find { it.path == "small.txt" })
    }

    /**
     * Reproduces the real-world bug: when a split file's uncompressedSize accounts
     * for >= 95% of the total archive, the single-file optimization at line ~403
     * fires because `fileEntry.isSplit` checks the original entry (which has
     * `splitParts = emptyList()`), not the copy with inferred parts. This causes
     * a `break` before the seek-past-inferred-data code ever executes.
     *
     * Volume layout (bigfile dominates ~96% of archive):
     *   Vol 0: [sig][archHdr][fileHdr bigfile SPLIT_AFTER][data 2020B][endArchive]  = 2090 bytes
     *   Vol 1: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE+AFTER][data 2020B][endArchive]  = 2090 bytes
     *   Vol 2: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE][data 1960B][fileHdr small.txt][data 20B][endArchive]  = 2091 bytes
     *   Total archive = 6271 bytes, bigfile = 6000 bytes → 95.7% of archive
     */
    @Test
    fun `parse finds additional files when split file dominates archive size`() {
        val bigfileTotal = 6000
        val bigfileChunk0 = 2020
        val bigfileChunk1 = 2020
        val bigfileChunk2 = bigfileTotal - bigfileChunk0 - bigfileChunk1 // 1960
        val smallSize = 20

        val vol0 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk0, unpackSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk1, unpackSize = bigfileTotal, splitBefore = true, splitAfter = true),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = bigfileChunk2, unpackSize = bigfileTotal, splitBefore = true),
            buildFileBlock("small.txt", packSize = smallSize, unpackSize = smallSize),
            buildEndOfArchive()
        )

        val concatenated = vol0 + vol1 + vol2
        val stream = ByteArraySeekableInputStream(concatenated)
        val volumeSizes = listOf(vol0.size.toLong(), vol1.size.toLong(), vol2.size.toLong())
        val totalArchiveSize = concatenated.size.toLong()

        // Verify precondition: bigfile dominates the archive (>= 95%)
        assertTrue(
            bigfileTotal >= totalArchiveSize * 0.95,
            "Test precondition: bigfile ($bigfileTotal) should be >= 95% of archive ($totalArchiveSize)"
        )

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = totalArchiveSize,
            volumeSizes = volumeSizes
        )

        assertEquals(2, entries.size, "Should find both bigfile.dat and small.txt, got: ${entries.map { it.path }}")

        val bigfile = entries.find { it.path == "bigfile.dat" }
        assertNotNull(bigfile, "Should find bigfile.dat")
        assertEquals(bigfileTotal.toLong(), bigfile.uncompressedSize)
        assertTrue(bigfile.splitParts.isNotEmpty(), "bigfile should have split parts")

        val small = entries.find { it.path == "small.txt" }
        assertNotNull(small, "Should find small.txt")
        assertEquals(smallSize.toLong(), small.uncompressedSize)
    }

    /**
     * Verifies that inferSplitPositions computes correct byte positions for a split
     * file that starts MID-VOLUME (not at the beginning of a volume).
     *
     * This reproduces the real-world bug where `inferSplitPositions` uses the formula
     * `firstPartDataStart - 20` to derive the file header block size. This formula
     * only works when `firstPartDataStart` is a within-volume-0 position. For files
     * starting mid-volume (like sample.mkv starting after the big mkv data in volume 172),
     * `firstPartDataStart` is an absolute position in the concatenated stream, making
     * `firstPartDataStart - 20` produce a nonsensical value.
     *
     * Volume layout:
     *   Vol 0: [sig][archHdr][fileHdr bigfile SPLIT_AFTER][bigfile data 100B][endArchive]         = 170 bytes
     *   Vol 1: [sig][archHdr][fileHdr bigfile SPLIT_BEFORE][bigfile data 100B]
     *          [fileHdr midfile SPLIT_AFTER][midfile data 60B][endArchive]                        = 273 bytes
     *   Vol 2: [sig][archHdr][fileHdr midfile SPLIT_BEFORE][midfile data 90B]
     *          [fileHdr small.txt][small data 20B][endArchive]                                    = 221 bytes
     *
     * midfile starts mid-volume in vol 1 (after bigfile's last chunk).
     * Its inferred continuation data in vol 2 should start at: vol2_start + 63 (= 7+13+43).
     */
    @Test
    fun `parse infers correct split positions for file starting mid-volume`() {
        val bigfileTotal = 200
        val midfileTotal = 150
        val smallSize = 20

        // Construct known data so we can verify correct bytes are read from inferred positions
        val midfileData = ByteArray(midfileTotal) { (it % 251).toByte() }
        val midfileChunk1 = 60  // in vol 1
        val midfileChunk2 = 90  // in vol 2

        val vol0 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = 100, unpackSize = bigfileTotal, splitAfter = true),
            buildEndOfArchive()
        )

        val vol1 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock("bigfile.dat", packSize = 100, unpackSize = bigfileTotal, splitBefore = true),
            buildFileBlock(
                "midfile.dat", packSize = midfileChunk1, unpackSize = midfileTotal,
                splitAfter = true, data = midfileData.sliceArray(0 until midfileChunk1)
            ),
            buildEndOfArchive()
        )

        val vol2 = buildVolume(
            buildArchiveHeader(),
            buildFileBlock(
                "midfile.dat", packSize = midfileChunk2, unpackSize = midfileTotal,
                splitBefore = true, data = midfileData.sliceArray(midfileChunk1 until midfileTotal)
            ),
            buildFileBlock("small.txt", packSize = smallSize, unpackSize = smallSize),
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

        // All 3 files should be found
        assertEquals(3, entries.size, "Should find all 3 files, got: ${entries.map { it.path }}")

        val midfile = entries.find { it.path == "midfile.dat" }
        assertNotNull(midfile, "Should find midfile.dat")
        assertEquals(midfileTotal.toLong(), midfile.uncompressedSize)
        assertEquals(2, midfile.splitParts.size, "midfile should have 2 split parts")

        // Verify inferred positions are correct by reading data from them
        val vol2Start = vol0.size + vol1.size
        // midfile header in vol 2 is 43 bytes (7+25+11), so data starts at:
        //   vol2_start + sig(7) + archHdr(13) + fileHdr(43) = vol2_start + 63
        val expectedPart2DataStart = (vol2Start + 63).toLong()
        assertEquals(
            expectedPart2DataStart,
            midfile.splitParts[1].dataStartPosition,
            "midfile part 2 data should start at vol2_start + 63 (continuation header size)"
        )
        assertEquals(midfileChunk2.toLong(), midfile.splitParts[1].dataSize)

        // Verify actual bytes at inferred positions match the known midfile data
        for (part in midfile.splitParts) {
            stream.seek(part.dataStartPosition)
            val readBuf = ByteArray(part.dataSize.toInt())
            stream.read(readBuf, 0, readBuf.size)
            val expectedOffset = if (part.volumeIndex == 1) 0 else midfileChunk1
            val expectedBytes = midfileData.sliceArray(expectedOffset until expectedOffset + part.dataSize.toInt())
            assertTrue(
                readBuf.contentEquals(expectedBytes),
                "Data at inferred position for midfile part (vol ${part.volumeIndex}) doesn't match expected data"
            )
        }

        // small.txt should also be found
        val small = entries.find { it.path == "small.txt" }
        assertNotNull(small, "Should find small.txt after mid-volume split file")
    }
}
