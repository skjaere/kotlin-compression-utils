package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveServiceTest {

    // fileHasKnownExtension tests

    @Test
    fun `fileHasKnownExtension recognizes rar extension`() {
        assertTrue(ArchiveService.fileHasKnownExtension("archive.rar"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.RAR"))
    }

    @Test
    fun `fileHasKnownExtension recognizes partNN rar extension`() {
        assertTrue(ArchiveService.fileHasKnownExtension("archive.part01.rar"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.part1.rar"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.part100.rar"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.Part01.Rar"))
    }

    @Test
    fun `fileHasKnownExtension recognizes rNN extension`() {
        assertTrue(ArchiveService.fileHasKnownExtension("archive.r00"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.r01"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.r999"))
    }

    @Test
    fun `fileHasKnownExtension recognizes sNN extension`() {
        assertTrue(ArchiveService.fileHasKnownExtension("archive.s00"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.s01"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.s999"))
    }

    @Test
    fun `fileHasKnownExtension recognizes 7z extension`() {
        assertTrue(ArchiveService.fileHasKnownExtension("archive.7z"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.7z.001"))
        assertTrue(ArchiveService.fileHasKnownExtension("archive.7z.002"))
    }

    @Test
    fun `fileHasKnownExtension returns false for unknown extensions`() {
        assertFalse(ArchiveService.fileHasKnownExtension("abc123def456"))
        assertFalse(ArchiveService.fileHasKnownExtension("file.001"))
        assertFalse(ArchiveService.fileHasKnownExtension("file.xyz"))
        assertFalse(ArchiveService.fileHasKnownExtension("file.txt"))
        assertFalse(ArchiveService.fileHasKnownExtension("obfuscated_name"))
    }

    // Dispatch tests

    @Test
    fun `listFiles dispatches RAR5 archive correctly`() {
        val archiveBytes = javaClass.getResourceAsStream("/test-rar5.rar")!!.readAllBytes()
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(archiveBytes))
        val volumes = listOf(
            VolumeMetaData(filename = "test-rar5.rar", size = archiveBytes.size.toLong())
        )

        val entries = ArchiveService.listFiles(stream, volumes)

        assertTrue(entries.isNotEmpty(), "Should find at least one file")
        val firstEntry = entries[0] as RarFileEntry
        assertEquals("testfile.txt", firstEntry.path)
        assertFalse(firstEntry.isDirectory)
        assertTrue(firstEntry.uncompressedSize > 0)
        assertEquals(0, firstEntry.compressionMethod, "Archive should be in store mode")
    }

    @Test
    fun `listFiles dispatches 7z archive correctly`() {
        val archiveBytes = javaClass.getResourceAsStream("/test.7z")!!.readAllBytes()
        val stream = ByteArraySeekableInputStream(archiveBytes)
        val volumes = listOf(
            VolumeMetaData(filename = "test.7z", size = archiveBytes.size.toLong())
        )

        val entries = ArchiveService.listFiles(stream, volumes)

        assertTrue(entries.isNotEmpty(), "Should find at least one file")
        val firstEntry = entries[0] as SevenZipFileEntry
        assertEquals("testfile.txt", firstEntry.path)
        assertFalse(firstEntry.isDirectory)
        assertEquals(110, firstEntry.size)
    }

    @Test
    fun `listFiles dispatches multi-file 7z archive correctly`() {
        val archiveBytes = javaClass.getResourceAsStream("/test-multifile.7z")!!.readAllBytes()
        val stream = ByteArraySeekableInputStream(archiveBytes)
        val volumes = listOf(
            VolumeMetaData(filename = "test-multifile.7z", size = archiveBytes.size.toLong())
        )

        val entries = ArchiveService.listFiles(stream, volumes)

        assertEquals(3, entries.size, "Should find 3 entries (1 dir + 2 files)")
        val dirEntry = entries.find { (it as SevenZipFileEntry).path == "testdir" } as SevenZipFileEntry
        assertTrue(dirEntry.isDirectory)

        val file1 = entries.find { (it as SevenZipFileEntry).path == "testdir/file1.txt" } as SevenZipFileEntry
        assertFalse(file1.isDirectory)
        assertEquals(19, file1.size)
    }

    @Test
    fun `listFiles dispatches RAR archive by byte signature when filename is obfuscated`() {
        val archiveBytes = javaClass.getResourceAsStream("/test-rar5.rar")!!.readAllBytes()
        val first16kb = archiveBytes.sliceArray(0 until minOf(16384, archiveBytes.size))
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(archiveBytes))
        val volumes = listOf(
            VolumeMetaData(
                filename = "abc123def456",
                size = archiveBytes.size.toLong(),
                first16kb = first16kb
            )
        )

        val entries = ArchiveService.listFiles(stream, volumes)

        assertTrue(entries.isNotEmpty(), "Should find at least one file via byte signature detection")
        val firstEntry = entries[0] as RarFileEntry
        assertEquals("testfile.txt", firstEntry.path)
    }

    @Test
    fun `listFiles single file convenience method works for RAR`() {
        val archiveBytes = javaClass.getResourceAsStream("/test-rar5.rar")!!.readAllBytes()
        val tempFile = kotlin.io.path.createTempFile(suffix = ".rar").toFile()
        try {
            tempFile.writeBytes(archiveBytes)
            val entries = ArchiveService.listFiles(tempFile.absolutePath)

            assertTrue(entries.isNotEmpty())
            val firstEntry = entries[0] as RarFileEntry
            assertEquals("testfile.txt", firstEntry.path)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `listFiles single file convenience method works for 7z`() {
        val archiveBytes = javaClass.getResourceAsStream("/test.7z")!!.readAllBytes()
        val tempFile = kotlin.io.path.createTempFile(suffix = ".7z").toFile()
        try {
            tempFile.writeBytes(archiveBytes)
            val entries = ArchiveService.listFiles(tempFile.absolutePath)

            assertTrue(entries.isNotEmpty())
            val firstEntry = entries[0] as SevenZipFileEntry
            assertEquals("testfile.txt", firstEntry.path)
        } finally {
            tempFile.delete()
        }
    }
}
