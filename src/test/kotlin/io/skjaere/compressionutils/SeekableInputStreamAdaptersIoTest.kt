package io.skjaere.compressionutils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The adapters do blocking I/O behind suspend functions: it must run off the caller's thread
 * and give way when the caller is cancelled.
 */
class SeekableInputStreamAdaptersIoTest {

    private val data = ByteArray(100) { it.toByte() }

    @Test
    fun `BufferedSeekableInputStream reads and skips off the caller's thread`(): Unit = runBlocking {
        val threads = ConcurrentLinkedQueue<Thread>()
        val stream = BufferedSeekableInputStream(ThreadRecordingInputStream(data, threads))

        val buf = ByteArray(4)
        assertEquals(4, stream.read(buf, 0, 4))
        assertEquals(4, stream.read())
        stream.seek(50)
        assertEquals(50, stream.read())

        assertContentEquals(byteArrayOf(0, 1, 2, 3), buf)
        assertEquals(51L, stream.position())
        assertCallsOffThread(threads)
    }

    @Test
    fun `FileSeekableInputStream reads off the caller's thread`(@TempDir tempDir: Path): Unit = runBlocking {
        val file = tempDir.resolve("test.bin").toFile().apply { writeBytes(data) }
        val threads = ConcurrentLinkedQueue<Thread>()

        FileSeekableInputStream(ThreadRecordingFile(file, threads)).use { stream ->
            val buf = ByteArray(4)
            stream.seek(10)
            assertEquals(4, stream.read(buf, 0, 4))
            assertEquals(14, stream.read())

            assertContentEquals(byteArrayOf(10, 11, 12, 13), buf)
            assertEquals(15L, stream.position())
        }
        assertCallsOffThread(threads)
    }

    @Test
    fun `HttpRangeSeekableInputStream fetches ranges off the caller's thread`(): Unit = runBlocking {
        val threads = ConcurrentLinkedQueue<Thread>()
        val stream = object : HttpRangeSeekableInputStream("http://example.invalid/a.rar", data.size.toLong()) {
            override fun fetchRange(fromPosition: Long, toPosition: Long): ByteArray {
                threads += Thread.currentThread()
                return data.copyOfRange(fromPosition.toInt(), toPosition.toInt() + 1)
            }
        }

        val buf = ByteArray(4)
        stream.seek(20)
        assertEquals(4, stream.read(buf, 0, 4))
        assertEquals(24, stream.read())

        assertContentEquals(byteArrayOf(20, 21, 22, 23), buf)
        assertEquals(25L, stream.position())
        assertCallsOffThread(threads)
    }

    @Test
    fun `adapters do their blocking calls on the dispatcher they are given`(@TempDir tempDir: Path): Unit =
        runBlocking {
            val file = tempDir.resolve("test.bin").toFile().apply { writeBytes(data) }
            val threads = ConcurrentLinkedQueue<Thread>()
            val given = ConcurrentLinkedQueue<Thread>()
            Executors.newSingleThreadExecutor { Thread(it, "given-io").also(given::add) }.asCoroutineDispatcher().use { io ->
                val buffered = BufferedSeekableInputStream(ThreadRecordingInputStream(data, threads), io)
                buffered.seek(30)
                assertEquals(30, buffered.read())

                FileSeekableInputStream(ThreadRecordingFile(file, threads), io).use { assertEquals(0, it.read()) }

                val http = object : HttpRangeSeekableInputStream("http://example.invalid/a.rar", data.size.toLong(), io) {
                    override fun fetchRange(fromPosition: Long, toPosition: Long): ByteArray {
                        threads += Thread.currentThread()
                        return data.copyOfRange(fromPosition.toInt(), toPosition.toInt() + 1)
                    }
                }
                assertEquals(0, http.read())
            }

            assertEquals(4, threads.size)
            assertTrue(threads.all { it === given.single() }, "blocking calls ran on ${threads.map { it.name }}")
        }

    @Test
    fun `cancelling a BufferedSeekableInputStream read ends the blocked read`(): Unit = runBlocking {
        val blocked = CountDownLatch(1)
        val stream = BufferedSeekableInputStream(object : InputStream() {
            override fun read(): Int = blockUntilInterrupted(blocked)
            override fun read(b: ByteArray, off: Int, len: Int): Int = blockUntilInterrupted(blocked)
        })

        assertCancelledWhileBlocked(blocked) { stream.read(ByteArray(8), 0, 8) }
    }

    @Test
    fun `cancelling a BufferedSeekableInputStream seek stops skipping a stream that ignores interrupts`(): Unit =
        runBlocking {
            val started = CountDownLatch(1)
            val giveUpAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BLOCK_LIMIT_MS)
            // Endless zeros, can't skip, never looks at interrupts; ends after BLOCK_LIMIT_MS so a
            // seek that can't be cancelled still lets go of its thread
            val stream = BufferedSeekableInputStream(object : InputStream() {
                override fun read(): Int = if (System.nanoTime() > giveUpAt) -1 else 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    started.countDown()
                    if (System.nanoTime() > giveUpAt) return -1
                    b.fill(0, off, off + len)
                    return len
                }
                override fun skip(n: Long): Long = 0
            })

            assertCancelledWhileBlocked(started) { stream.seek(Long.MAX_VALUE) }
        }

    @Test
    fun `cancelling a FileSeekableInputStream read ends the blocked read`(@TempDir tempDir: Path): Unit = runBlocking {
        val file = tempDir.resolve("test.bin").toFile().apply { writeBytes(data) }
        val blocked = CountDownLatch(1)
        val raf = object : RandomAccessFile(file, "r") {
            override fun read(): Int = blockUntilInterrupted(blocked)
            override fun read(b: ByteArray, off: Int, len: Int): Int = blockUntilInterrupted(blocked)
        }

        FileSeekableInputStream(raf).use { stream ->
            assertCancelledWhileBlocked(blocked) { stream.read(ByteArray(8), 0, 8) }
        }
    }

    @Test
    fun `cancelling an HttpRangeSeekableInputStream read ends the blocked fetch`(): Unit = runBlocking {
        val blocked = CountDownLatch(1)
        val stream = object : HttpRangeSeekableInputStream("http://example.invalid/a.rar", data.size.toLong()) {
            override fun fetchRange(fromPosition: Long, toPosition: Long): ByteArray {
                blocked.countDown()
                // A blocking HTTP client answers an interrupt with InterruptedException
                Thread.sleep(BLOCK_LIMIT_MS)
                throw IOException("never interrupted")
            }
        }

        assertCancelledWhileBlocked(blocked) { stream.read(ByteArray(8), 0, 8) }
    }

    private fun assertCallsOffThread(threads: Collection<Thread>) {
        val caller = Thread.currentThread()
        assertTrue(threads.isNotEmpty(), "the stream was never called")
        assertTrue(threads.none { it === caller }, "blocking calls ran on the caller's thread ${caller.name}")
    }

    /**
     * Runs [call] in a scope of its own, cancels it once [blocked] says the underlying stream is
     * blocked, and expects the call to end promptly as cancelled, not with the stream's error.
     */
    private suspend fun assertCancelledWhileBlocked(blocked: CountDownLatch, call: suspend () -> Unit) {
        val deferred = CoroutineScope(Dispatchers.Default).async { call() }
        assertTrue(blocked.await(5, TimeUnit.SECONDS), "the stream was never called")

        deferred.cancel()

        val ended = withTimeoutOrNull(CANCEL_TIMEOUT_MS) { deferred.join() } != null
        assertTrue(ended, "the call was still running $CANCEL_TIMEOUT_MS ms after it was cancelled")
        assertFailsWith<CancellationException> { deferred.await() }
    }

    private class ThreadRecordingInputStream(
        data: ByteArray,
        private val threads: MutableCollection<Thread>
    ) : ByteArrayInputStream(data) {
        override fun read(): Int = super.read().also { threads += Thread.currentThread() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { threads += Thread.currentThread() }
        override fun skip(n: Long): Long = super.skip(n).also { threads += Thread.currentThread() }
    }

    private class ThreadRecordingFile(
        file: File,
        private val threads: MutableCollection<Thread>
    ) : RandomAccessFile(file, "r") {
        override fun read(): Int = super.read().also { threads += Thread.currentThread() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { threads += Thread.currentThread() }
    }

    private companion object {
        const val CANCEL_TIMEOUT_MS = 2_000L

        /** How long a test stream blocks if nothing interrupts it. */
        const val BLOCK_LIMIT_MS = 10_000L

        /** Blocks like a pipe or socket read, answering an interrupt with InterruptedIOException. */
        fun blockUntilInterrupted(blocked: CountDownLatch): Int {
            blocked.countDown()
            try {
                Thread.sleep(BLOCK_LIMIT_MS)
            } catch (e: InterruptedException) {
                throw InterruptedIOException("interrupted")
            }
            throw IOException("never interrupted")
        }
    }
}
