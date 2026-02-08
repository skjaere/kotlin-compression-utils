package io.skjaere.compressionutils.validation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class ConcatenatedFileSeekableInputStreamTest {

    @Test
    fun `single file reads all bytes sequentially`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("vol1.bin").toFile()
        val data = ByteArray(100) { it.toByte() }
        file.writeBytes(data)

        ConcatenatedFileSeekableInputStream(listOf(file)).use { stream ->
            assertEquals(100L, stream.size())
            assertEquals(0L, stream.position())

            val buf = ByteArray(100)
            val read = stream.read(buf, 0, 100)
            assertEquals(100, read)
            assertEquals(data.toList(), buf.toList())
            assertEquals(100L, stream.position())
        }
    }

    @Test
    fun `single byte read works`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("vol1.bin").toFile()
        file.writeBytes(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))

        ConcatenatedFileSeekableInputStream(listOf(file)).use { stream ->
            assertEquals(0xAB, stream.read())
            assertEquals(1L, stream.position())
            assertEquals(0xCD, stream.read())
            assertEquals(2L, stream.position())
        }
    }

    @Test
    fun `returns -1 at end of stream`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("vol1.bin").toFile()
        file.writeBytes(byteArrayOf(1))

        ConcatenatedFileSeekableInputStream(listOf(file)).use { stream ->
            stream.read()
            assertEquals(-1, stream.read())
        }
    }

    @Test
    fun `bulk read returns -1 at end of stream`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("vol1.bin").toFile()
        file.writeBytes(byteArrayOf(1))

        ConcatenatedFileSeekableInputStream(listOf(file)).use { stream ->
            stream.seek(1)
            val buf = ByteArray(10)
            assertEquals(-1, stream.read(buf, 0, 10))
        }
    }

    @Test
    fun `seek and read within single file`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("vol1.bin").toFile()
        val data = ByteArray(50) { it.toByte() }
        file.writeBytes(data)

        ConcatenatedFileSeekableInputStream(listOf(file)).use { stream ->
            stream.seek(25)
            assertEquals(25L, stream.position())
            assertEquals(25, stream.read())
            assertEquals(26L, stream.position())

            stream.seek(0)
            assertEquals(0, stream.read())
        }
    }

    @Test
    fun `reads across two files seamlessly`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("vol1.bin").toFile()
        val file2 = tempDir.resolve("vol2.bin").toFile()
        file1.writeBytes(byteArrayOf(1, 2, 3))
        file2.writeBytes(byteArrayOf(4, 5, 6))

        ConcatenatedFileSeekableInputStream(listOf(file1, file2)).use { stream ->
            assertEquals(6L, stream.size())

            val buf = ByteArray(6)
            val read = stream.read(buf, 0, 6)
            assertEquals(6, read)
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), buf.toList())
        }
    }

    @Test
    fun `read crossing volume boundary`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("vol1.bin").toFile()
        val file2 = tempDir.resolve("vol2.bin").toFile()
        file1.writeBytes(byteArrayOf(10, 20, 30))
        file2.writeBytes(byteArrayOf(40, 50, 60))

        ConcatenatedFileSeekableInputStream(listOf(file1, file2)).use { stream ->
            stream.seek(1)
            val buf = ByteArray(4)
            val read = stream.read(buf, 0, 4)
            assertEquals(4, read)
            assertEquals(listOf<Byte>(20, 30, 40, 50), buf.toList())
            assertEquals(5L, stream.position())
        }
    }

    @Test
    fun `seek into second file`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("vol1.bin").toFile()
        val file2 = tempDir.resolve("vol2.bin").toFile()
        file1.writeBytes(byteArrayOf(1, 2, 3))
        file2.writeBytes(byteArrayOf(4, 5, 6))

        ConcatenatedFileSeekableInputStream(listOf(file1, file2)).use { stream ->
            stream.seek(4)
            assertEquals(4L, stream.position())
            assertEquals(5, stream.read())
        }
    }

    @Test
    fun `seek to exact boundary of first file reads from second`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("vol1.bin").toFile()
        val file2 = tempDir.resolve("vol2.bin").toFile()
        file1.writeBytes(byteArrayOf(1, 2, 3))
        file2.writeBytes(byteArrayOf(4, 5, 6))

        ConcatenatedFileSeekableInputStream(listOf(file1, file2)).use { stream ->
            stream.seek(3)
            assertEquals(4, stream.read())
        }
    }

    @Test
    fun `three files with different sizes`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("vol1.bin").toFile()
        val file2 = tempDir.resolve("vol2.bin").toFile()
        val file3 = tempDir.resolve("vol3.bin").toFile()
        file1.writeBytes(ByteArray(10) { (it + 0).toByte() })   // 0..9
        file2.writeBytes(ByteArray(5) { (it + 10).toByte() })   // 10..14
        file3.writeBytes(ByteArray(20) { (it + 15).toByte() })  // 15..34

        ConcatenatedFileSeekableInputStream(listOf(file1, file2, file3)).use { stream ->
            assertEquals(35L, stream.size())

            // Read from third file
            stream.seek(20)
            assertEquals(20, stream.read())

            // Read spanning all three files
            stream.seek(8)
            val buf = ByteArray(10)
            val read = stream.read(buf, 0, 10)
            assertEquals(10, read)
            assertEquals((8..17).map { it.toByte() }, buf.toList())
        }
    }

    @Test
    fun `single byte reads across boundary`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("vol1.bin").toFile()
        val file2 = tempDir.resolve("vol2.bin").toFile()
        file1.writeBytes(byteArrayOf(0xAA.toByte()))
        file2.writeBytes(byteArrayOf(0xBB.toByte()))

        ConcatenatedFileSeekableInputStream(listOf(file1, file2)).use { stream ->
            assertEquals(0xAA, stream.read())
            assertEquals(0xBB, stream.read())
            assertEquals(-1, stream.read())
        }
    }

    @Test
    fun `size returns total across all files`(@TempDir tempDir: Path) {
        val files = (1..4).map { i ->
            tempDir.resolve("vol$i.bin").toFile().also { it.writeBytes(ByteArray(i * 100)) }
        }

        ConcatenatedFileSeekableInputStream(files).use { stream ->
            assertEquals(1000L, stream.size()) // 100+200+300+400
        }
    }

    @Test
    fun `read with offset into buffer`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("vol1.bin").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3))

        ConcatenatedFileSeekableInputStream(listOf(file)).use { stream ->
            val buf = ByteArray(5)
            stream.read(buf, 2, 3)
            assertEquals(listOf<Byte>(0, 0, 1, 2, 3), buf.toList())
        }
    }
}
