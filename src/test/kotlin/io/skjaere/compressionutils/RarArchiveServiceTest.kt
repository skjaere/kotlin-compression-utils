package io.skjaere.compressionutils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RarArchiveServiceTest {

    private val service = RarArchiveService()

    @Test
    fun `listFiles throws on invalid data`() = runBlocking {
        val invalidData = ByteArray(10) { 0 }
        assertFailsWith<IOException> {
            service.listFiles(ByteArrayInputStream(invalidData))
        }
    }

    @Test
    fun `listFiles throws on too-short data`() = runBlocking {
        val shortData = ByteArray(3) { 0x52 }
        assertFailsWith<IOException> {
            service.listFiles(ByteArrayInputStream(shortData))
        }
    }

    @Test
    fun `listFiles parses RAR5 archive from resources`() = runBlocking {
        val stream = javaClass.getResourceAsStream("/test-rar5.rar")!!
        val entries = service.listFiles(stream)

        assertTrue(entries.isNotEmpty(), "Should find at least one file")

        val firstEntry = entries[0]
        assertEquals("testfile.txt", firstEntry.path)
        assertFalse(firstEntry.isDirectory)
        assertTrue(firstEntry.uncompressedSize > 0)
        assertEquals(0, firstEntry.compressionMethod, "Archive should be in store mode")
        assertTrue(firstEntry.isUncompressed)
    }

    @Test
    fun `listFiles with maxFiles limits results`() = runBlocking {
        val stream = javaClass.getResourceAsStream("/test-rar5.rar")!!
        val entries = service.listFiles(stream, maxFiles = 1)
        assertEquals(1, entries.size)
    }

    @Test
    fun `listFiles parses multi-volume RAR5 archive`() = runBlocking {
        val part1 = javaClass.getResourceAsStream("/test-multivolume.part1.rar")!!
        val part2 = javaClass.getResourceAsStream("/test-multivolume.part2.rar")!!

        val part1Bytes = part1.readAllBytes()
        val part2Bytes = part2.readAllBytes()

        val concatenated = ByteArrayInputStream(part1Bytes + part2Bytes)
        val stream = BufferedSeekableInputStream(concatenated)

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = (part1Bytes.size + part2Bytes.size).toLong()
        )

        assertTrue(entries.isNotEmpty(), "Should find at least one file in multi-volume archive")
        val fileEntry = entries.find { it.path == "largefile.txt" }
        assertTrue(fileEntry != null, "Should find largefile.txt")
        assertTrue(fileEntry.uncompressedSize > 0)
    }

    @Test
    fun `RarFileEntry properties work correctly`() {
        val entry = RarFileEntry(
            path = "test/file.txt",
            uncompressedSize = 1000,
            compressedSize = 1000,
            headerPosition = 20,
            dataPosition = 50,
            isDirectory = false,
            volumeIndex = 0,
            compressionMethod = 0,
            splitParts = emptyList()
        )

        assertTrue(entry.isUncompressed)
        assertFalse(entry.isSplit)

        val splitEntry = entry.copy(
            splitParts = listOf(
                SplitInfo(0, 50, 500),
                SplitInfo(1, 20, 500)
            )
        )
        assertTrue(splitEntry.isSplit)
    }

    @Test
    fun `RarFileEntry compressed entry is not uncompressed`() {
        val entry = RarFileEntry(
            path = "test.txt",
            uncompressedSize = 1000,
            compressedSize = 500,
            headerPosition = 20,
            dataPosition = 50,
            isDirectory = false,
            compressionMethod = 3
        )

        assertFalse(entry.isUncompressed)
    }

    // VolumeMetaData integration tests

    @Test
    fun `listFilesFromConcatenatedStream works with volumeSizes`() = runBlocking {
        val part1 = javaClass.getResourceAsStream("/test-multivolume.part1.rar")!!
        val part2 = javaClass.getResourceAsStream("/test-multivolume.part2.rar")!!

        val part1Bytes = part1.readAllBytes()
        val part2Bytes = part2.readAllBytes()

        val concatenated = ByteArrayInputStream(part1Bytes + part2Bytes)
        val stream = BufferedSeekableInputStream(concatenated)

        val entries = service.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = (part1Bytes.size + part2Bytes.size).toLong(),
            volumeSizes = listOf(part1Bytes.size.toLong(), part2Bytes.size.toLong())
        )

        assertTrue(entries.isNotEmpty(), "Should find at least one file in multi-volume archive")
        val fileEntry = entries.find { it.path == "largefile.txt" }
        assertTrue(fileEntry != null, "Should find largefile.txt")
        assertTrue(fileEntry.uncompressedSize > 0)
    }

    /**
     * Regression: production observed `IOException: Invalid RAR archive: too short`
     * when listing a real multi-volume RAR5 archive served via [io.skjaere.nzbstreamer]'s
     * NNTP-backed `SeekableInputStream`. The on-wire stream returns partial reads — a
     * `stream.read(buf, 0, 8)` for the 8-byte RAR signature comes back with fewer than
     * 8 bytes the first time around even though plenty more data is available.
     *
     * `InputStream.read(b, off, len)` is *explicitly* allowed to return short by the
     * platform contract — the parser must loop, the same way [readBytes] already does
     * for every other header read. Without the loop, any chunked/buffered upstream
     * (NNTP segments, ChannelInput, networked reads) will false-alarm with the
     * "too short" message even though the bytes are there.
     *
     * The local file-based tests don't catch this because [FileSeekableInputStream]
     * wraps `RandomAccessFile.read` which always satisfies the full request — so the
     * partial-read failure mode only ever shows up in production.
     */
    @Test
    fun `listFiles tolerates partial-read SeekableInputStream when reading the signature`() = runBlocking {
        // Smallest possible "valid" RAR5 archive: just the 8-byte signature followed
        // by some trailing bytes the parser can keep chewing on. We only need the
        // signature read to succeed — what happens after is the parser's existing
        // problem (it'll throw a different exception or stop). The point is to assert
        // we no longer get "too short" purely from a partial signature read.
        val rar5Signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        val payload = rar5Signature + ByteArray(64)  // pad with zeros so any follow-up reads have bytes
        val trickling = OneByteAtATimeSeekableInputStream(ByteArraySeekableInputStream(payload))

        // Either we successfully parse something (Success with possibly empty entries)
        // or the parser throws a *parsing* error further in — but NOT "Invalid RAR
        // archive: too short" from the signature read.
        val caught = runCatching {
            service.listFiles(stream = trickling)
        }.exceptionOrNull()

        assertFalse(
            caught is IOException && caught.message == "Invalid RAR archive: too short",
            "Signature read must loop on partial reads, got: $caught",
        )
    }

    /**
     * SeekableInputStream that wraps another stream but artificially limits every
     * `read(buffer, offset, length)` call to return at most 1 byte. Models the worst
     * case of chunked I/O — any code that doesn't loop will misbehave.
     */
    private class OneByteAtATimeSeekableInputStream(
        private val delegate: SeekableInputStream,
    ) : SeekableInputStream {
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            return delegate.read(buffer, offset, 1)
        }
        override suspend fun read(): Int = delegate.read()
        override suspend fun seek(position: Long) = delegate.seek(position)
        override fun position(): Long = delegate.position()
        override fun size(): Long = delegate.size()
        override fun close() = delegate.close()
    }
}
