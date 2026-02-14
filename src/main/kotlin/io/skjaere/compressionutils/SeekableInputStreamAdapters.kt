package io.skjaere.compressionutils

import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Adapter that wraps a regular InputStream as a SeekableInputStream.
 * Note: This doesn't actually support seeking - it simulates it by reading and discarding bytes.
 * Use FileSeekableInputStream or a custom HTTP range request implementation for true seeking.
 */
class BufferedSeekableInputStream(private val inputStream: InputStream) : SeekableInputStream {
    private var currentPosition = 0L

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = inputStream.read(buffer, offset, length)
        if (bytesRead > 0) {
            currentPosition += bytesRead
        }
        return bytesRead
    }

    override suspend fun read(): Int {
        val byte = inputStream.read()
        if (byte != -1) {
            currentPosition++
        }
        return byte
    }

    override suspend fun seek(position: Long) {
        if (position < currentPosition) {
            throw IOException("Cannot seek backwards in a non-seekable stream (current: $currentPosition, requested: $position)")
        }

        if (position == currentPosition) {
            return // Already at target position
        }

        var remaining = position - currentPosition

        // Use skip() which is typically efficient and doesn't allocate buffers
        while (remaining > 0) {
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

    override fun position(): Long = currentPosition

    override fun size(): Long = -1 // Unknown

    override fun close() {
        inputStream.close()
    }
}

/**
 * Adapter that wraps a RandomAccessFile as a SeekableInputStream.
 * Provides true seeking capability.
 */
class FileSeekableInputStream(private val file: RandomAccessFile) : SeekableInputStream {

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return file.read(buffer, offset, length)
    }

    override suspend fun read(): Int {
        return file.read()
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
 */
abstract class HttpRangeSeekableInputStream(
    private val url: String,
    private val totalSize: Long
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
        val data = fetchRange(currentPosition, currentPosition + toRead - 1)

        System.arraycopy(data, 0, buffer, offset, data.size)
        currentPosition += data.size

        return data.size
    }

    override suspend fun read(): Int {
        if (currentPosition >= totalSize) {
            return -1
        }

        val data = fetchRange(currentPosition, currentPosition)
        currentPosition++

        return data[0].toInt() and 0xFF
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
