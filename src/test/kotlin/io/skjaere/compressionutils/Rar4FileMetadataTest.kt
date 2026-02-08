package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Rar4FileMetadataTest {

    @Test
    fun `calculateContinuationHeaderSize for normal file`() {
        // RAR sig(7) + archive header(13) + file header base(7) + fixed fields(25) + filename(8) = 60
        val size = Rar4Parser.calculateContinuationHeaderSize(8, isLargeFile = false)
        assertEquals(60L, size)
    }

    @Test
    fun `calculateContinuationHeaderSize for large file`() {
        // Same as above but with +8 for large file size fields
        val size = Rar4Parser.calculateContinuationHeaderSize(8, isLargeFile = true)
        assertEquals(68L, size)
    }

    @Test
    fun `calculateContinuationHeaderSize with empty filename`() {
        val size = Rar4Parser.calculateContinuationHeaderSize(0, isLargeFile = false)
        assertEquals(52L, size)
    }

    @Test
    fun `calculateSplitParts single volume`() {
        val parts = Rar4Parser.calculateSplitParts(
            uncompressedSize = 500,
            firstPartDataStart = 100,
            firstPartDataSize = 500,
            continuationHeaderSize = 52,
            volumeSizes = listOf(700)
        )

        assertEquals(1, parts.size)
        assertEquals(0, parts[0].volumeIndex)
        assertEquals(100, parts[0].dataStartPosition)
        assertEquals(500, parts[0].dataSize)
    }

    @Test
    fun `calculateSplitParts across two volumes`() {
        val parts = Rar4Parser.calculateSplitParts(
            uncompressedSize = 1000,
            firstPartDataStart = 100,
            firstPartDataSize = 400,
            continuationHeaderSize = 52,
            volumeSizes = listOf(500, 700)
        )

        assertEquals(2, parts.size)
        // First volume
        assertEquals(0, parts[0].volumeIndex)
        assertEquals(100, parts[0].dataStartPosition)
        assertEquals(400, parts[0].dataSize)
        // Second volume
        assertEquals(1, parts[1].volumeIndex)
        assertEquals(500 + 52, parts[1].dataStartPosition) // cumulative offset + header
        assertEquals(600, parts[1].dataSize) // min(600, 700 - 52 - 7) = min(600, 641) = 600
    }

    @Test
    fun `calculateSplitParts across three volumes`() {
        // continuationHeaderSize=52, END_OF_ARCHIVE_SIZE=7
        // Vol 0: firstPartDataSize = 400
        // Vol 1: available = 600 - 52 - 7 = 541
        // Vol 2: available = 700 - 52 - 7 = 641, needed = 1500 - 400 - 541 = 559
        val parts = Rar4Parser.calculateSplitParts(
            uncompressedSize = 1500,
            firstPartDataStart = 100,
            firstPartDataSize = 400,
            continuationHeaderSize = 52,
            volumeSizes = listOf(500, 600, 700)
        )

        assertEquals(3, parts.size)
        assertEquals(0, parts[0].volumeIndex)
        assertEquals(1, parts[1].volumeIndex)
        assertEquals(2, parts[2].volumeIndex)

        // Verify individual part sizes
        assertEquals(400, parts[0].dataSize) // first volume
        assertEquals(541, parts[1].dataSize) // 600 - 52 - 7
        assertEquals(559, parts[2].dataSize) // remaining: 1500 - 400 - 541

        // Total data should equal uncompressed size
        val totalData = parts.sumOf { it.dataSize }
        assertEquals(1500, totalData)
    }

    @Test
    fun `Rar4FileMetadata isUncompressed`() {
        val metadata = Rar4FileMetadata(
            filename = "test.txt",
            uncompressedSize = 1000,
            firstPartDataStart = 100,
            firstPartDataSize = 1000,
            continuationHeaderSize = 52,
            isSplit = false,
            compressionMethod = 0
        )
        assertTrue(metadata.isUncompressed)

        val compressed = metadata.copy(compressionMethod = 3)
        assertFalse(compressed.isUncompressed)
    }

    @Test
    fun `Rar4FileMetadata isLargeFile`() {
        val small = Rar4FileMetadata(
            filename = "test.txt",
            uncompressedSize = 1000,
            firstPartDataStart = 100,
            firstPartDataSize = 1000,
            continuationHeaderSize = 52,
            isSplit = false,
            compressionMethod = 0
        )
        assertFalse(small.isLargeFile)

        val large = small.copy(uncompressedSize = 0x1_0000_0000L) // >4GB
        assertTrue(large.isLargeFile)
    }

    @Test
    fun `Rar4FileMetadata calculateSplitParts returns empty for compressed`() {
        val metadata = Rar4FileMetadata(
            filename = "test.txt",
            uncompressedSize = 1000,
            firstPartDataStart = 100,
            firstPartDataSize = 500,
            continuationHeaderSize = 52,
            isSplit = true,
            compressionMethod = 3
        )
        assertTrue(metadata.calculateSplitParts(listOf(600, 600)).isEmpty())
    }

    @Test
    fun `Rar4FileMetadata calculateSplitParts works for uncompressed`() {
        val metadata = Rar4FileMetadata(
            filename = "test.txt",
            uncompressedSize = 1000,
            firstPartDataStart = 100,
            firstPartDataSize = 400,
            continuationHeaderSize = 52,
            isSplit = true,
            compressionMethod = 0
        )
        val parts = metadata.calculateSplitParts(listOf(500, 700))
        assertEquals(2, parts.size)
        assertEquals(1000, parts.sumOf { it.dataSize })
    }
}
