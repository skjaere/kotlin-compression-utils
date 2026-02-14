package io.skjaere.compressionutils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SevenZipArchiveServiceTest {

    private val service = SevenZipArchiveService()

    @Test
    fun `listFiles parses 7z archive from file path`() = runBlocking {
        val archiveBytes = javaClass.getResourceAsStream("/test.7z")!!.readAllBytes()
        val tempFile = kotlin.io.path.createTempFile(suffix = ".7z").toFile()
        try {
            tempFile.writeBytes(archiveBytes)
            val entries = service.listFiles(tempFile.absolutePath)

            assertTrue(entries.isNotEmpty(), "Should find at least one file")
            val firstEntry = entries[0]
            assertEquals("testfile.txt", firstEntry.path)
            assertFalse(firstEntry.isDirectory)
            assertEquals(110, firstEntry.size)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `listFiles parses 7z archive from stream`() = runBlocking {
        val archiveBytes = javaClass.getResourceAsStream("/test.7z")!!.readAllBytes()
        val stream = ByteArraySeekableInputStream(archiveBytes)
        val entries = service.listFiles(stream)

        assertTrue(entries.isNotEmpty())
        val fileEntry = entries.find { it.path == "testfile.txt" }
        assertTrue(fileEntry != null, "Should find testfile.txt")
        assertFalse(fileEntry.isDirectory)
        assertEquals(110, fileEntry.size)
        assertEquals(32L, fileEntry.dataOffset)
        assertEquals("Copy", fileEntry.method)
    }

    @Test
    fun `listFiles parses multi-file 7z archive with directory`() = runBlocking {
        val archiveBytes = javaClass.getResourceAsStream("/test-multifile.7z")!!.readAllBytes()
        val stream = ByteArraySeekableInputStream(archiveBytes)
        val entries = service.listFiles(stream)

        assertEquals(3, entries.size, "Should find 3 entries (1 dir + 2 files)")

        val dirEntry = entries.find { it.path == "testdir" }
        assertTrue(dirEntry != null, "Should find testdir directory")
        assertTrue(dirEntry.isDirectory)
        assertEquals(0, dirEntry.size)

        val file1 = entries.find { it.path == "testdir/file1.txt" }
        assertTrue(file1 != null, "Should find file1.txt")
        assertFalse(file1.isDirectory)
        assertEquals(19, file1.size)
        assertEquals(32L, file1.dataOffset)
        assertEquals("Copy", file1.method)

        val file2 = entries.find { it.path == "testdir/file2.txt" }
        assertTrue(file2 != null, "Should find file2.txt")
        assertFalse(file2.isDirectory)
        assertEquals(19, file2.size)
        assertEquals(51L, file2.dataOffset)
        assertEquals("Copy", file2.method)
    }

    @Test
    fun `listFiles rejects compressed 7z archive`() = runBlocking {
        val archiveBytes = javaClass.getResourceAsStream("/test-compressed.7z")!!.readAllBytes()
        val stream = ByteArraySeekableInputStream(archiveBytes)

        val exception = assertFailsWith<IOException> {
            service.listFiles(stream)
        }
        assertTrue(
            exception.message!!.contains("Only uncompressed 7z archives are supported"),
            "Error message should indicate unsupported compression, got: ${exception.message}"
        )
    }

    @Test
    fun `SevenZipFileEntry properties`() {
        val entry = SevenZipFileEntry(
            path = "documents/readme.txt",
            size = 1024,
            packedSize = 512,
            dataOffset = 32,
            isDirectory = false,
            method = "Copy"
        )

        assertEquals("documents/readme.txt", entry.path)
        assertEquals(1024, entry.size)
        assertEquals(512, entry.packedSize)
        assertEquals(32, entry.dataOffset)
        assertFalse(entry.isDirectory)
        assertEquals("Copy", entry.method)
    }

    @Test
    fun `SevenZipFileEntry directory entry`() {
        val entry = SevenZipFileEntry(
            path = "documents/",
            size = 0,
            packedSize = 0,
            dataOffset = 0,
            isDirectory = true
        )

        assertTrue(entry.isDirectory)
        assertEquals(0, entry.size)
    }
}
