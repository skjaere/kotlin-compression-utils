package io.skjaere.compressionutils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.test.assertEquals

class SeekableInputStreamTest {

    @Test
    fun `BufferedSeekableInputStream tracks position on read`() = runBlocking {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(data))

        assertEquals(0L, stream.position())
        stream.read()
        assertEquals(1L, stream.position())

        val buf = ByteArray(3)
        stream.read(buf, 0, 3)
        assertEquals(4L, stream.position())
    }

    @Test
    fun `BufferedSeekableInputStream forward seek works`() = runBlocking {
        val data = ByteArray(100) { it.toByte() }
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(data))

        stream.seek(50)
        assertEquals(50L, stream.position())
        assertEquals(50, stream.read())
    }

    @Test
    fun `BufferedSeekableInputStream seek to same position is no-op`() = runBlocking {
        val data = ByteArray(10) { it.toByte() }
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(data))
        stream.seek(5)
        stream.seek(5) // Should not throw
        assertEquals(5L, stream.position())
    }

    @Test
    fun `BufferedSeekableInputStream backward seek throws`() = runBlocking {
        val data = ByteArray(100) { it.toByte() }
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(data))
        stream.seek(50)
        assertThrows<IOException> {
            runBlocking { stream.seek(10) }
        }
    }

    @Test
    fun `BufferedSeekableInputStream returns -1 for unknown size`() {
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(ByteArray(0)))
        assertEquals(-1L, stream.size())
    }

    @Test
    fun `BufferedSeekableInputStream returns -1 at end of stream`() = runBlocking {
        val data = byteArrayOf(1, 2, 3)
        val stream = BufferedSeekableInputStream(ByteArrayInputStream(data))
        stream.read()
        stream.read()
        stream.read()
        assertEquals(-1, stream.read())
    }

    @Test
    fun `FileSeekableInputStream reads and seeks correctly`(@TempDir tempDir: Path) = runBlocking {
        val file = tempDir.resolve("test.bin").toFile()
        val data = ByteArray(100) { it.toByte() }
        file.writeBytes(data)

        FileSeekableInputStream(RandomAccessFile(file, "r")).use { stream ->
            assertEquals(100L, stream.size())
            assertEquals(0L, stream.position())

            // Read first byte
            assertEquals(0, stream.read())
            assertEquals(1L, stream.position())

            // Seek forward
            stream.seek(50)
            assertEquals(50L, stream.position())
            assertEquals(50, stream.read())

            // Seek backward (supported for file streams)
            stream.seek(10)
            assertEquals(10L, stream.position())
            assertEquals(10, stream.read())

            // Read multiple bytes
            stream.seek(0)
            val buf = ByteArray(5)
            stream.read(buf, 0, 5)
            assertEquals(byteArrayOf(0, 1, 2, 3, 4).toList(), buf.toList())
        }
    }

    @Test
    fun `FileSeekableInputStream reports correct size`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("test.bin").toFile()
        file.writeBytes(ByteArray(256))

        FileSeekableInputStream(RandomAccessFile(file, "r")).use { stream ->
            assertEquals(256L, stream.size())
        }
    }
}
