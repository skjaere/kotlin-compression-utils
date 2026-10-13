package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Par2ParserTest {

    @Test
    fun `parse extracts file descriptions from PAR2 data`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val result = Par2Parser.parse(par2Data)

        assertEquals(2, result.files.size, "Should find 2 file descriptions")

        val filenames = result.files.map { it.filename }.toSet()
        assertTrue("testfile.part1.rar" in filenames, "Should contain testfile.part1.rar")
        assertTrue("testfile.part2.rar" in filenames, "Should contain testfile.part2.rar")
    }

    @Test
    fun `parse from InputStream works`() {
        val input = javaClass.getResourceAsStream("/test.par2")!!
        val result = Par2Parser.parse(input)

        assertEquals(2, result.files.size)
    }

    @Test
    fun `extractFilenames returns hash to filename mapping`() {
        val input = javaClass.getResourceAsStream("/test.par2")!!
        val filenames = Par2Parser.extractFilenames(input)

        assertEquals(2, filenames.size)
        assertTrue(filenames.values.any { it == "testfile.part1.rar" })
        assertTrue(filenames.values.any { it == "testfile.part2.rar" })
    }

    @Test
    fun `parse has correct hash16k values`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val result = Par2Parser.parse(par2Data)

        val part1 = result.files.find { it.filename == "testfile.part1.rar" }!!
        val part2 = result.files.find { it.filename == "testfile.part2.rar" }!!

        // hash16k should be non-empty 16-byte arrays
        assertEquals(16, part1.hash16k.size)
        assertEquals(16, part2.hash16k.size)
        assertTrue(part1.hash16kHex().isNotEmpty())
        assertTrue(part2.hash16kHex().isNotEmpty())
    }

    @Test
    fun `parse has correct file sizes`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val result = Par2Parser.parse(par2Data)

        val part1 = result.files.find { it.filename == "testfile.part1.rar" }!!
        val part2 = result.files.find { it.filename == "testfile.part2.rar" }!!

        assertEquals(88, part1.fileSize)
        assertEquals(44, part2.fileSize)
    }

    @Test
    fun `parse sets recovery set ID`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val result = Par2Parser.parse(par2Data)

        assertEquals(16, result.recoverySetId.size)
        assertTrue(result.recoverySetIdHex().length == 32, "Recovery set ID hex should be 32 chars")
    }

    @Test
    fun `parse returns empty result for truncated header`() {
        val truncatedData = byteArrayOf(0x50, 0x41, 0x52, 0x32, 0x00, 0x50, 0x4B, 0x54, // magic
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // truncated header
        val result = Par2Parser.parse(truncatedData)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `parse throws on invalid magic`() {
        val invalidData = byteArrayOf(0x50, 0x41, 0x52, 0x32, 0x00, 0x00, 0x00, 0x00, // bad magic
            *ByteArray(56)) // rest of "header"
        assertThrows<Par2ParseException> {
            Par2Parser.parse(invalidData)
        }
    }

    @Test
    fun `parse returns empty result for empty stream`() {
        val result = Par2Parser.parse(ByteArray(0))
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `parse reads whole packets from a stream that returns one byte per read`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()

        val result = Par2Parser.parse(OneBytePerReadInputStream(par2Data))

        assertEquals(listOf("testfile.part2.rar", "testfile.part1.rar"), result.files.map { it.filename })
        assertEquals(listOf(44L, 88L), result.files.map { it.fileSize })
        assertContentEquals(Par2Parser.parse(par2Data).recoverySetId, result.recoverySetId)
    }

    @Test
    fun `parse keeps the packets before a header truncated mid-way`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val truncated = par2Data.copyOf(SECOND_FILE_DESC_OFFSET + 30)

        listOf(truncated.inputStream(), OneBytePerReadInputStream(truncated)).forEach { input ->
            assertEquals(listOf("testfile.part2.rar"), Par2Parser.parse(input).files.map { it.filename })
        }
    }

    @Test
    fun `parse keeps the packets before a body truncated mid-way`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val truncated = par2Data.copyOf(SECOND_FILE_DESC_OFFSET + 64 + 20)

        listOf(truncated.inputStream(), OneBytePerReadInputStream(truncated)).forEach { input ->
            assertEquals(listOf("testfile.part2.rar"), Par2Parser.parse(input).files.map { it.filename })
        }
    }

    @Test
    fun `parse keeps the packets before a stream that ends right after a packet's first byte`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()
        val truncated = par2Data.copyOf(SECOND_FILE_DESC_OFFSET + 1)

        listOf(truncated.inputStream(), OneBytePerReadInputStream(truncated)).forEach { input ->
            assertEquals(listOf("testfile.part2.rar"), Par2Parser.parse(input).files.map { it.filename })
        }
    }

    /** Hands out at most one byte per read, like a network stream delivering data in small pieces. */
    private class OneBytePerReadInputStream(data: ByteArray) : InputStream() {
        private val delegate = data.inputStream()

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, minOf(len, 1))
    }

    private companion object {
        // test.par2 packets: Main (0-108), FileDesc part2 (108-248), FileDesc part1 (248-388), IFSC, IFSC, Creator
        const val SECOND_FILE_DESC_OFFSET = 248
    }
}
