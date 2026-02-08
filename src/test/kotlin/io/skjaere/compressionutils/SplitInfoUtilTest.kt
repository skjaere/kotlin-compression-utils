package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitInfoUtilTest {

    private val testSplits = listOf(
        SplitInfo(volumeIndex = 0, dataStartPosition = 100, dataSize = 50),
        SplitInfo(volumeIndex = 1, dataStartPosition = 200, dataSize = 100),
        SplitInfo(volumeIndex = 2, dataStartPosition = 500, dataSize = 75)
    )

    @Test
    fun `fromOffset with zero returns original list`() {
        val result = testSplits.fromOffset(0)
        assertEquals(testSplits, result)
    }

    @Test
    fun `fromOffset within first split`() {
        val result = testSplits.fromOffset(20)
        assertEquals(3, result.size)
        // First split should be partial
        assertEquals(0, result[0].volumeIndex)
        assertEquals(120, result[0].dataStartPosition) // 100 + 20
        assertEquals(30, result[0].dataSize) // 50 - 20
        // Remaining splits unchanged
        assertEquals(testSplits[1], result[1])
        assertEquals(testSplits[2], result[2])
    }

    @Test
    fun `fromOffset at split boundary`() {
        // Offset 50 = exactly at end of first split (size 50), start of second
        val result = testSplits.fromOffset(50)
        assertEquals(2, result.size)
        assertEquals(testSplits[1], result[0])
        assertEquals(testSplits[2], result[1])
    }

    @Test
    fun `fromOffset within second split`() {
        // Offset 80 = 50 bytes into first split + 30 into second
        val result = testSplits.fromOffset(80)
        assertEquals(2, result.size)
        assertEquals(1, result[0].volumeIndex)
        assertEquals(230, result[0].dataStartPosition) // 200 + 30
        assertEquals(70, result[0].dataSize) // 100 - 30
        assertEquals(testSplits[2], result[1])
    }

    @Test
    fun `fromOffset within last split`() {
        // Total of first two splits = 50 + 100 = 150, so 160 is 10 bytes into last split
        val result = testSplits.fromOffset(160)
        assertEquals(1, result.size)
        assertEquals(2, result[0].volumeIndex)
        assertEquals(510, result[0].dataStartPosition) // 500 + 10
        assertEquals(65, result[0].dataSize) // 75 - 10
    }

    @Test
    fun `fromOffset out of bounds returns empty`() {
        // Total size = 50 + 100 + 75 = 225
        val result = testSplits.fromOffset(225)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fromOffset way out of bounds returns empty`() {
        val result = testSplits.fromOffset(10000)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fromOffset negative returns empty`() {
        val result = testSplits.fromOffset(-1)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fromOffset on empty list returns empty`() {
        val result = emptyList<SplitInfo>().fromOffset(0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fromOffset single split partial`() {
        val single = listOf(SplitInfo(volumeIndex = 0, dataStartPosition = 100, dataSize = 200))
        val result = single.fromOffset(50)
        assertEquals(1, result.size)
        assertEquals(0, result[0].volumeIndex)
        assertEquals(150, result[0].dataStartPosition)
        assertEquals(150, result[0].dataSize)
    }
}
