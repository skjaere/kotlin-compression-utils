package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

class InstrumentedInputStreamTest {

    @Test
    fun `tracks bytes read via single byte read`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val stream = InstrumentedInputStream(ByteArrayInputStream(data))

        stream.read()
        stream.read()
        stream.read()

        assertEquals(3L, stream.bytesRead)
        assertEquals(0L, stream.bytesSkipped)
    }

    @Test
    fun `tracks bytes read via bulk read`() {
        val data = ByteArray(100) { it.toByte() }
        val stream = InstrumentedInputStream(ByteArrayInputStream(data))

        val buf = ByteArray(50)
        stream.read(buf, 0, 50)

        assertEquals(50L, stream.bytesRead)
    }

    @Test
    fun `tracks bytes skipped`() {
        val data = ByteArray(100) { it.toByte() }
        val stream = InstrumentedInputStream(ByteArrayInputStream(data))

        stream.skip(30)
        stream.read()

        assertEquals(1L, stream.bytesRead)
        assertEquals(30L, stream.bytesSkipped)
    }

    @Test
    fun `does not count -1 returns as bytes read`() {
        val data = byteArrayOf(1)
        val stream = InstrumentedInputStream(ByteArrayInputStream(data))

        stream.read() // reads the byte
        stream.read() // returns -1

        assertEquals(1L, stream.bytesRead)
    }
}
