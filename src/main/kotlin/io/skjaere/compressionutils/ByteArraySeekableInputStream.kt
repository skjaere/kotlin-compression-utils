package io.skjaere.compressionutils

/**
 * In-memory [SeekableInputStream] backed by a [ByteArray]. Used by the RAR5
 * parser to feed the existing block-parsing helpers (parseFileHeader etc.) with
 * decrypted plaintext after [Rar5EncryptedBlockReader] produces it for an
 * encrypted block — the helpers don't need to know whether the bytes came from
 * disk or from a decryption buffer.
 */
internal class ByteArraySeekableInputStream(private val data: ByteArray) : SeekableInputStream {
    private var pos = 0L

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) return -1
        val available = data.size - pos.toInt()
        val toCopy = minOf(length, available)
        System.arraycopy(data, pos.toInt(), buffer, offset, toCopy)
        pos += toCopy
        return toCopy
    }

    override suspend fun read(): Int {
        if (pos >= data.size) return -1
        return data[pos.toInt()].toInt().and(0xFF).also { pos++ }
    }

    override suspend fun seek(position: Long) {
        require(position in 0..data.size.toLong()) { "Seek out of range: $position vs size ${data.size}" }
        pos = position
    }

    override fun position(): Long = pos
    override fun size(): Long = data.size.toLong()
    override fun close() { /* no-op for in-memory */ }
}
