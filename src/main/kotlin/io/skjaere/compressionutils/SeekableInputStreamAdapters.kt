package io.skjaere.compressionutils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Runs the blocking [block] on [dispatcher] and interrupts its thread if the caller is cancelled.
 * A stream that answers the interrupt with an IOException (InterruptedIOException,
 * ClosedByInterruptException) ends the call with the caller's cancellation rather than that error.
 */
private suspend fun <T> blockingIo(dispatcher: CoroutineDispatcher, block: () -> T): T =
    try {
        runInterruptible(dispatcher, block)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    }

/**
 * Adapter that wraps a regular InputStream as a SeekableInputStream.
 * Note: This doesn't actually support seeking - it simulates it by reading and discarding bytes.
 * Use FileSeekableInputStream or a custom HTTP range request implementation for true seeking.
 * Reads and skips run on [ioDispatcher]; cancelling the caller interrupts them.
 */
class BufferedSeekableInputStream @JvmOverloads constructor(
    private val inputStream: InputStream,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SeekableInputStream {
    private var currentPosition = 0L

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = blockingIo(ioDispatcher) {
        val bytesRead = inputStream.read(buffer, offset, length)
        if (bytesRead > 0) {
            currentPosition += bytesRead
        }
        bytesRead
    }

    override suspend fun read(): Int = blockingIo(ioDispatcher) {
        val byte = inputStream.read()
        if (byte != -1) {
            currentPosition++
        }
        byte
    }

    override suspend fun seek(position: Long) {
        if (position < currentPosition) {
            throw IOException("Cannot seek backwards in a non-seekable stream (current: $currentPosition, requested: $position)")
        }

        if (position == currentPosition) {
            return // Already at target position
        }

        blockingIo(ioDispatcher) {
            var remaining = position - currentPosition

            // Use skip() which is typically efficient and doesn't allocate buffers
            while (remaining > 0) {
                // A stream that ignores interrupts would otherwise skip on after a cancellation
                if (Thread.interrupted()) throw InterruptedException()
                val skipped = inputStream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    currentPosition += skipped
                } else {
                    // skip() returned 0 or negative, fall back to reading in chunks
                    // This is rare but can happen with some InputStream implementations
                    val bufferSize = minOf(remaining, 8192L).toInt()
                    val buffer = ByteArray(bufferSize)
                    val read = inputStream.read(buffer, 0, bufferSize)
                    if (read == -1) {
                        throw IOException("Reached end of stream while seeking to $position (at: $currentPosition)")
                    }
                    remaining -= read
                    currentPosition += read
                }
            }
        }
    }

    override fun position(): Long = currentPosition

    override fun size(): Long = -1 // Unknown

    override fun close() {
        inputStream.close()
    }
}

/**
 * Adapter that wraps a RandomAccessFile as a SeekableInputStream.
 * Provides true seeking capability. Reads run on [ioDispatcher], and their thread is interrupted if
 * the caller is cancelled; seeking only moves the file pointer and stays on the caller's thread.
 */
class FileSeekableInputStream @JvmOverloads constructor(
    private val file: RandomAccessFile,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SeekableInputStream {

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = blockingIo(ioDispatcher) {
        file.read(buffer, offset, length)
    }

    override suspend fun read(): Int = blockingIo(ioDispatcher) {
        file.read()
    }

    override suspend fun seek(position: Long) {
        file.seek(position)
    }

    override fun position(): Long {
        return file.filePointer
    }

    override fun size(): Long {
        return file.length()
    }

    override fun close() {
        file.close()
    }
}

/**
 * Abstract implementation for HTTP byte-range based seeking.
 * Subclasses should implement [fetchRange] to make HTTP GET requests with Range headers.
 * [fetchRange] is called on [ioDispatcher], and its thread is interrupted if the caller is cancelled.
 */
abstract class HttpRangeSeekableInputStream @JvmOverloads constructor(
    private val url: String,
    private val totalSize: Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SeekableInputStream {
    private var currentPosition = 0L

    /**
     * Subclasses should implement this to make an HTTP GET request with Range header.
     * Range: bytes=$fromPosition-$toPosition
     */
    protected abstract fun fetchRange(fromPosition: Long, toPosition: Long): ByteArray

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (currentPosition >= totalSize) {
            return -1
        }

        val toRead = minOf(length.toLong(), totalSize - currentPosition).toInt()
        return blockingIo(ioDispatcher) {
            val data = fetchRange(currentPosition, currentPosition + toRead - 1)

            System.arraycopy(data, 0, buffer, offset, data.size)
            currentPosition += data.size

            data.size
        }
    }

    override suspend fun read(): Int {
        if (currentPosition >= totalSize) {
            return -1
        }

        return blockingIo(ioDispatcher) {
            val data = fetchRange(currentPosition, currentPosition)
            currentPosition++

            data[0].toInt() and 0xFF
        }
    }

    override suspend fun seek(position: Long) {
        if (position < 0 || position > totalSize) {
            throw IOException("Invalid seek position: $position (size: $totalSize)")
        }
        currentPosition = position
    }

    override fun position(): Long = currentPosition

    override fun size(): Long = totalSize

    override fun close() {
        // Nothing to close for HTTP
    }
}
