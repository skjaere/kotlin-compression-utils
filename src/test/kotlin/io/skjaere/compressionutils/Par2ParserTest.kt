package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `parse throws on invalid data`() {
        val invalidData = byteArrayOf(0x50, 0x41, 0x52, 0x32, 0x00, 0x50, 0x4B, 0x54, // magic
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // truncated header
        assertThrows<Par2ParseException> {
            Par2Parser.parse(invalidData)
        }
    }

    @Test
    fun `parse returns empty result for empty stream`() {
        val result = Par2Parser.parse(ByteArray(0))
        assertTrue(result.files.isEmpty())
    }
}
