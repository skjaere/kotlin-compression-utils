package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RarArchiveServiceTest {

    private val service = RarArchiveService()

    @Test
    fun `listFiles throws on invalid data`() {
        val invalidData = ByteArray(10) { 0 }
        assertThrows<IOException> {
            service.listFiles(ByteArrayInputStream(invalidData))
        }
    }

    @Test
    fun `listFiles throws on too-short data`() {
        val shortData = ByteArray(3) { 0x52 }
        assertThrows<IOException> {
            service.listFiles(ByteArrayInputStream(shortData))
        }
    }

    @Test
    fun `listFiles parses RAR5 archive from resources`() {
        val stream = javaClass.getResourceAsStream("/test-rar5.rar")!!
        val entries = service.listFiles(stream)

        assertTrue(entries.isNotEmpty(), "Should find at least one file")

        val firstEntry = entries[0]
        assertEquals("testfile.txt", firstEntry.path)
        assertFalse(firstEntry.isDirectory)
        assertTrue(firstEntry.uncompressedSize > 0)
        assertEquals(0, firstEntry.compressionMethod, "Archive should be in store mode")
        assertTrue(firstEntry.isUncompressed)
    }

    @Test
    fun `listFiles with maxFiles limits results`() {
        val stream = javaClass.getResourceAsStream("/test-rar5.rar")!!
        val entries = service.listFiles(stream, maxFiles = 1)
        assertEquals(1, entries.size)
    }

    @Test
    fun `listFiles parses multi-volume RAR5 archive`() {
        val part1 = javaClass.getResourceAsStream("/test-multivolume.part1.rar")!!
        val part2 = javaClass.getResourceAsStream("/test-multivolume.part2.rar")!!

        val part1Bytes = part1.readAllBytes()
        val part2Bytes = part2.readAllBytes()

        val concatenated = ByteArrayInputStream(part1Bytes + part2Bytes)
        val stream = BufferedSeekableInputStream(concatenated)

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = (part1Bytes.size + part2Bytes.size).toLong()
        )

        assertTrue(entries.isNotEmpty(), "Should find at least one file in multi-volume archive")
        val fileEntry = entries.find { it.path == "largefile.txt" }
        assertTrue(fileEntry != null, "Should find largefile.txt")
        assertTrue(fileEntry.uncompressedSize > 0)
    }

    @Test
    fun `RarFileEntry properties work correctly`() {
        val entry = RarFileEntry(
            path = "test/file.txt",
            uncompressedSize = 1000,
            compressedSize = 1000,
            headerPosition = 20,
            dataPosition = 50,
            isDirectory = false,
            volumeIndex = 0,
            compressionMethod = 0,
            splitParts = emptyList()
        )

        assertTrue(entry.isUncompressed)
        assertFalse(entry.isSplit)

        val splitEntry = entry.copy(
            splitParts = listOf(
                SplitInfo(0, 50, 500),
                SplitInfo(1, 20, 500)
            )
        )
        assertTrue(splitEntry.isSplit)
    }

    @Test
    fun `RarFileEntry compressed entry is not uncompressed`() {
        val entry = RarFileEntry(
            path = "test.txt",
            uncompressedSize = 1000,
            compressedSize = 500,
            headerPosition = 20,
            dataPosition = 50,
            isDirectory = false,
            compressionMethod = 3
        )

        assertFalse(entry.isUncompressed)
    }

    // VolumeMetaData integration tests

    @Test
    fun `listFilesFromConcatenatedStream works with volumeSizes`() {
        val part1 = javaClass.getResourceAsStream("/test-multivolume.part1.rar")!!
        val part2 = javaClass.getResourceAsStream("/test-multivolume.part2.rar")!!

        val part1Bytes = part1.readAllBytes()
        val part2Bytes = part2.readAllBytes()

        val concatenated = ByteArrayInputStream(part1Bytes + part2Bytes)
        val stream = BufferedSeekableInputStream(concatenated)

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = (part1Bytes.size + part2Bytes.size).toLong(),
            volumeSizes = listOf(part1Bytes.size.toLong(), part2Bytes.size.toLong())
        )

        assertTrue(entries.isNotEmpty(), "Should find at least one file in multi-volume archive")
        val fileEntry = entries.find { it.path == "largefile.txt" }
        assertTrue(fileEntry != null, "Should find largefile.txt")
        assertTrue(fileEntry.uncompressedSize > 0)
    }
}
