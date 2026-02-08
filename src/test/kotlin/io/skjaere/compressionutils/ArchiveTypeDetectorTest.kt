package io.skjaere.compressionutils

import io.skjaere.compressionutils.ArchiveTypeDetector.ArchiveType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveTypeDetectorTest {

    @Test
    fun `detect RAR5 signature from bytes`() {
        val rar5Bytes = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) +
            ByteArray(24) // padding to 32 bytes
        val result = ArchiveTypeDetector.detect(rar5Bytes)
        assertEquals(ArchiveType.RAR5, result.type)
        assertTrue(result.isFirstVolume)
    }

    @Test
    fun `detect RAR4 signature from bytes`() {
        // RAR4 signature (7 bytes) + archive header block (type 0x73)
        val rar4Bytes = byteArrayOf(
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, // RAR4 signature
            0x00, 0x00, // CRC
            0x73,       // Block type: archive header
            0x00, 0x01, // Flags with FIRSTVOLUME set (0x0100)
            0x0D, 0x00  // Block size
        ) + ByteArray(18) // padding to 32 bytes
        val result = ArchiveTypeDetector.detect(rar4Bytes)
        assertEquals(ArchiveType.RAR4, result.type)
        assertTrue(result.isFirstVolume)
    }

    @Test
    fun `detect RAR4 non-first-volume via file header with SPLIT_BEFORE`() {
        // RAR4 signature + file header with SPLIT_BEFORE flag
        val rar4Bytes = byteArrayOf(
            0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, // RAR4 signature
            0x00, 0x00, // CRC
            0x74,       // Block type: file header
            0x01, 0x00, // Flags with SPLIT_BEFORE (0x01)
            0x20, 0x00  // Block size
        ) + ByteArray(18) // padding to 32 bytes
        val result = ArchiveTypeDetector.detect(rar4Bytes)
        assertEquals(ArchiveType.RAR4, result.type)
        assertFalse(result.isFirstVolume)
    }

    @Test
    fun `detect 7zip signature from bytes`() {
        val sevenZipBytes = byteArrayOf(
            0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C
        ) + ByteArray(26) // padding to 32 bytes
        val result = ArchiveTypeDetector.detect(sevenZipBytes)
        assertEquals(ArchiveType.SEVENZIP, result.type)
        assertTrue(result.isFirstVolume)
    }

    @Test
    fun `detect unknown for random bytes`() {
        val randomBytes = ByteArray(32) { it.toByte() }
        val result = ArchiveTypeDetector.detect(randomBytes)
        assertEquals(ArchiveType.UNKNOWN, result.type)
    }

    @Test
    fun `detect unknown for too-short input`() {
        val shortBytes = byteArrayOf(0x52, 0x61, 0x72)
        val result = ArchiveTypeDetector.detect(shortBytes)
        assertEquals(ArchiveType.UNKNOWN, result.type)
    }

    @Test
    fun `detect from SeekableInputStream resets position`(@TempDir tempDir: Path) {
        val rar5Bytes = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) +
            ByteArray(24)
        val file = tempDir.resolve("test.bin").toFile()
        file.writeBytes(rar5Bytes)
        FileSeekableInputStream(java.io.RandomAccessFile(file, "r")).use { stream ->
            val result = ArchiveTypeDetector.detect(stream)
            assertEquals(ArchiveType.RAR5, result.type)
            // Position should be reset to 0
            assertEquals(0L, stream.position())
        }
    }

    @Test
    fun `isRar returns true for RAR5`() {
        val rar5Bytes = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) +
            ByteArray(24)
        assertTrue(ArchiveTypeDetector.isRar(rar5Bytes))
    }

    @Test
    fun `isRar returns true for RAR4`() {
        val rar4Bytes = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) +
            ByteArray(25)
        assertTrue(ArchiveTypeDetector.isRar(rar4Bytes))
    }

    @Test
    fun `isRar returns false for 7zip`() {
        val sevenZipBytes = byteArrayOf(
            0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C
        ) + ByteArray(26)
        assertFalse(ArchiveTypeDetector.isRar(sevenZipBytes))
    }

    @Test
    fun `isSevenZip returns true for 7zip`() {
        val sevenZipBytes = byteArrayOf(
            0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C
        ) + ByteArray(26)
        assertTrue(ArchiveTypeDetector.isSevenZip(sevenZipBytes))
    }

    @Test
    fun `isSevenZip returns false for RAR`() {
        val rar5Bytes = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) +
            ByteArray(24)
        assertFalse(ArchiveTypeDetector.isSevenZip(rar5Bytes))
    }

    @Test
    fun `detect real RAR5 archive from resources`() {
        val bytes = javaClass.getResourceAsStream("/test-rar5.rar")!!.use {
            it.readNBytes(32)
        }
        val result = ArchiveTypeDetector.detect(bytes)
        assertEquals(ArchiveType.RAR5, result.type)
    }

    @Test
    fun `detect real 7z archive from resources`() {
        val bytes = javaClass.getResourceAsStream("/test.7z")!!.use {
            it.readNBytes(32)
        }
        val result = ArchiveTypeDetector.detect(bytes)
        assertEquals(ArchiveType.SEVENZIP, result.type)
    }
}
