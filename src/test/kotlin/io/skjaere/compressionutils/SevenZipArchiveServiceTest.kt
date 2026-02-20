package io.skjaere.compressionutils

import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `listFiles parses 7z archive with encoded header using Copy codec`() = runBlocking {
        // Generate a normal 7z archive and re-wrap its metadata in an EncodedHeader
        val archiveBytes = ArchiveGenerator.generate(
            ByteArray(200) { (it % 256).toByte() },
            1,
            ContainerType.SEVENZIP
        )[0].data

        val encodedArchive = wrapMetadataInEncodedHeader(archiveBytes)
        val stream = ByteArraySeekableInputStream(encodedArchive)
        val entries = service.listFiles(stream)

        assertTrue(entries.isNotEmpty(), "Should find at least one file entry")
        val fileEntry = entries.first { !it.isDirectory }
        assertEquals(200, fileEntry.size)
        assertEquals("Copy", fileEntry.method)
    }

    @Test
    fun `listFiles parses 7z archive with LZMA-compressed encoded header`() = runBlocking {
        val archiveBytes = ArchiveGenerator.generate(
            ByteArray(200) { (it % 256).toByte() },
            1,
            ContainerType.SEVENZIP
        )[0].data

        val encodedArchive = wrapMetadataInLzmaEncodedHeader(archiveBytes)
        val stream = ByteArraySeekableInputStream(encodedArchive)
        val entries = service.listFiles(stream)

        assertTrue(entries.isNotEmpty(), "Should find at least one file entry")
        val fileEntry = entries.first { !it.isDirectory }
        assertEquals(200, fileEntry.size)
        assertEquals("Copy", fileEntry.method)
    }

    /**
     * Takes a 7z archive with a plain kHeader and rewrites it so the metadata
     * is stored as a packed stream referenced by a kEncodedHeader.
     *
     * Layout produced:
     *   [0..31]                                   Signature Header (points to EncodedHeader)
     *   [32..32+fileDataSize-1]                   File data (unchanged)
     *   [32+fileDataSize..+metadataSize-1]        Original metadata (now a packed stream)
     *   [32+fileDataSize+metadataSize..]           EncodedHeader (references the packed metadata)
     */
    private fun wrapMetadataInEncodedHeader(archive: ByteArray): ByteArray {
        // Parse the existing signature header to find the metadata
        val sigBuf = ByteBuffer.wrap(archive, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
        sigBuf.position(12)
        val nextHeaderOffset = sigBuf.getLong()
        val nextHeaderSize = sigBuf.getLong()

        val metadataStart = (32 + nextHeaderOffset).toInt()
        val metadataBytes = archive.copyOfRange(metadataStart, metadataStart + nextHeaderSize.toInt())
        val fileData = archive.copyOfRange(32, metadataStart)

        // Build the EncodedHeader structure
        val encodedHeader = buildEncodedHeader(
            packPos = fileData.size.toLong(),
            packedSize = metadataBytes.size.toLong()
        )

        val encodedHeaderCrc = crc32(encodedHeader)

        // New signature header
        val newNextHeaderOffset = fileData.size.toLong() + metadataBytes.size.toLong()
        val newNextHeaderSize = encodedHeader.size.toLong()

        val startHeaderContent = ByteArrayOutputStream(16)
        startHeaderContent.write(writeLE64(newNextHeaderOffset))
        startHeaderContent.write(writeLE64(newNextHeaderSize))
        val shBytes = startHeaderContent.toByteArray()

        val crcInput = ByteArrayOutputStream(20)
        crcInput.write(shBytes)
        crcInput.write(writeLE32(encodedHeaderCrc))
        val startHeaderCrc = crc32(crcInput.toByteArray())

        val out = ByteArrayOutputStream()
        // Signature header (32 bytes)
        out.write(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) // magic
        out.write(byteArrayOf(0x00, 0x04)) // version
        out.write(writeLE32(startHeaderCrc))
        out.write(shBytes) // nextHeaderOffset + nextHeaderSize
        out.write(writeLE32(encodedHeaderCrc))

        // File data
        out.write(fileData)
        // Original metadata (now a packed stream)
        out.write(metadataBytes)
        // EncodedHeader
        out.write(encodedHeader)

        return out.toByteArray()
    }

    private fun buildEncodedHeader(packPos: Long, packedSize: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x17) // kEncodedHeader

        // PackInfo
        out.write(0x06) // kPackInfo
        out.write(encode7zUInt64(packPos))
        out.write(encode7zUInt64(1)) // numPackStreams
        out.write(0x09) // kSize
        out.write(encode7zUInt64(packedSize))
        out.write(0x00) // kEnd (PackInfo)

        // UnPackInfo
        out.write(0x07) // kUnPackInfo
        out.write(0x0B) // kFolder
        out.write(encode7zUInt64(1)) // numFolders
        out.write(0x00) // external = 0
        // Folder: 1 coder, Copy codec
        out.write(0x01) // numCoders = 1
        out.write(0x01) // coderFlags: codecIdSize=1
        out.write(0x00) // codec ID: Copy
        out.write(0x0C) // kCodersUnPackSize
        out.write(encode7zUInt64(packedSize)) // unpackSize == packSize for Copy
        out.write(0x00) // kEnd (UnPackInfo)

        out.write(0x00) // kEnd (StreamsInfo / EncodedHeader)
        return out.toByteArray()
    }

    private fun encode7zUInt64(value: Long): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())
        var numExtra = 1
        while (numExtra < 8) {
            val totalBits = (7 - numExtra) + 8 * numExtra
            if (value < (1L shl totalBits)) break
            numExtra++
        }
        val out = ByteArray(1 + numExtra)
        for (i in 0 until numExtra) {
            out[1 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        val mask = (0xFF shl (8 - numExtra)) and 0xFF
        val high = if (numExtra < 8) ((value ushr (8 * numExtra)) and ((1L shl (7 - numExtra)) - 1)).toInt() else 0
        out[0] = (mask or high).toByte()
        return out
    }

    private fun writeLE32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun writeLE64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun wrapMetadataInLzmaEncodedHeader(archive: ByteArray): ByteArray {
        val sigBuf = ByteBuffer.wrap(archive, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
        sigBuf.position(12)
        val nextHeaderOffset = sigBuf.getLong()
        val nextHeaderSize = sigBuf.getLong()

        val metadataStart = (32 + nextHeaderOffset).toInt()
        val metadataBytes = archive.copyOfRange(metadataStart, metadataStart + nextHeaderSize.toInt())
        val fileData = archive.copyOfRange(32, metadataStart)

        // LZMA-compress the metadata
        val opts = LZMA2Options(LZMA2Options.PRESET_DEFAULT)
        val compressedStream = ByteArrayOutputStream()
        LZMAOutputStream(compressedStream, opts, metadataBytes.size.toLong()).use { lzma ->
            lzma.write(metadataBytes)
        }
        // LZMAOutputStream prepends 5 bytes of properties + 8 bytes of size; strip them
        val lzmaAlone = compressedStream.toByteArray()
        val lzmaProperties = lzmaAlone.copyOfRange(0, 5)
        val lzmaCompressed = lzmaAlone.copyOfRange(13, lzmaAlone.size)

        val encodedHeader = buildLzmaEncodedHeader(
            packPos = fileData.size.toLong(),
            packedSize = lzmaCompressed.size.toLong(),
            unpackedSize = metadataBytes.size.toLong(),
            lzmaProperties = lzmaProperties
        )

        val encodedHeaderCrc = crc32(encodedHeader)
        val newNextHeaderOffset = fileData.size.toLong() + lzmaCompressed.size.toLong()
        val newNextHeaderSize = encodedHeader.size.toLong()

        val startHeaderContent = ByteArrayOutputStream(16)
        startHeaderContent.write(writeLE64(newNextHeaderOffset))
        startHeaderContent.write(writeLE64(newNextHeaderSize))
        val shBytes = startHeaderContent.toByteArray()

        val crcInput = ByteArrayOutputStream(20)
        crcInput.write(shBytes)
        crcInput.write(writeLE32(encodedHeaderCrc))
        val startHeaderCrc = crc32(crcInput.toByteArray())

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))
        out.write(byteArrayOf(0x00, 0x04))
        out.write(writeLE32(startHeaderCrc))
        out.write(shBytes)
        out.write(writeLE32(encodedHeaderCrc))
        out.write(fileData)
        out.write(lzmaCompressed)
        out.write(encodedHeader)

        return out.toByteArray()
    }

    private fun buildLzmaEncodedHeader(
        packPos: Long,
        packedSize: Long,
        unpackedSize: Long,
        lzmaProperties: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x17) // kEncodedHeader

        // PackInfo
        out.write(0x06) // kPackInfo
        out.write(encode7zUInt64(packPos))
        out.write(encode7zUInt64(1)) // numPackStreams
        out.write(0x09) // kSize
        out.write(encode7zUInt64(packedSize))
        out.write(0x00) // kEnd (PackInfo)

        // UnPackInfo
        out.write(0x07) // kUnPackInfo
        out.write(0x0B) // kFolder
        out.write(encode7zUInt64(1)) // numFolders
        out.write(0x00) // external = 0
        // Folder: 1 coder, LZMA codec (0x030101)
        out.write(0x01) // numCoders = 1
        out.write(0x23) // coderFlags: codecIdSize=3, hasAttributes=true
        out.write(byteArrayOf(0x03, 0x01, 0x01)) // codec ID: LZMA
        out.write(encode7zUInt64(lzmaProperties.size.toLong())) // properties size
        out.write(lzmaProperties) // LZMA properties (5 bytes)
        out.write(0x0C) // kCodersUnPackSize
        out.write(encode7zUInt64(unpackedSize))
        out.write(0x00) // kEnd (UnPackInfo)

        out.write(0x00) // kEnd (StreamsInfo / EncodedHeader)
        return out.toByteArray()
    }

    private fun crc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
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
