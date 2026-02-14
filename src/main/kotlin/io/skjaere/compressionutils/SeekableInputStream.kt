package io.skjaere.compressionutils

import java.io.Closeable
import java.io.IOException

/**
 * An input stream that supports seeking to arbitrary positions.
 * This is useful for reading archive formats that require random access,
 * such as RAR archives over HTTP with byte-range requests.
 */
interface SeekableInputStream : Closeable {
    /**
     * Reads up to len bytes of data from the input stream into an array of bytes.
     *
     * @param buffer the buffer into which the data is read
     * @param offset the start offset in array buffer at which the data is written
     * @param length the maximum number of bytes to read
     * @return the total number of bytes read into the buffer, or -1 if end of stream
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Reads a single byte from the stream.
     *
     * @return the byte read, or -1 if end of stream
     */
    suspend fun read(): Int

    /**
     * Seeks to the specified position in the stream.
     *
     * @param position the position to seek to
     */
    suspend fun seek(position: Long)

    /**
     * Returns the current position in the stream.
     *
     * @return the current byte position
     */
    fun position(): Long

    /**
     * Returns the total size of the stream if known.
     *
     * @return the size in bytes, or -1 if unknown
     */
    fun size(): Long
}
