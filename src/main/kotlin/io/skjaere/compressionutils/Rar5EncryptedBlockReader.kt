package io.skjaere.compressionutils

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and decrypts a single RAR5 encrypted block from a [SeekableInputStream].
 *
 * On disk, every block after `HEAD_CRYPT` looks like:
 * ```
 *   [16 bytes AES-CBC IV]
 *   [N × 16 bytes AES-CBC ciphertext]   // plaintext block, zero-padded to 16
 * ```
 * The plaintext is the original block exactly as it would have appeared without
 * encryption: `headerCrc(4) || headerSize(vint) || headerType(vint) || flags(vint)
 * || …body… || dataArea`. We don't have to know the full size in advance — we
 * read 16 bytes of ciphertext at a time, decrypt each, and parse vint fields
 * progressively. The CBC chain is maintained internally (each subsequent
 * ciphertext-block decryption uses the previous ciphertext block as IV).
 *
 * Returns the decrypted **header** portion only. The data area is left on disk
 * encrypted — we don't need its contents for file listing, just need to skip
 * past it (which the caller computes from `dataAreaCiphertextSize`).
 */
internal class Rar5EncryptedBlockReader(private val key: ByteArray) {

    suspend fun readBlockHeader(stream: SeekableInputStream): DecryptedBlock? {
        val blockStart = stream.position()

        val iv = ByteArray(IV_SIZE)
        if (stream.read(iv, 0, IV_SIZE) != IV_SIZE) return null

        // Decrypt enough ciphertext blocks to cover the full plaintext header.
        // We don't know the total plaintext header size until we've decoded the
        // headerSize vint, so we pull AES blocks one-by-one and reparse on each.
        val plaintext = ByteArrayOutputStreamWithBuffer()
        var prevCipher = iv
        var headerEndOffset: Int? = null

        while (headerEndOffset == null) {
            val cipherBlock = ByteArray(AES_BLOCK_SIZE)
            if (stream.read(cipherBlock, 0, AES_BLOCK_SIZE) != AES_BLOCK_SIZE) {
                // Stream ran out before we could decrypt a full plaintext header — corrupt
                // or truncated archive. Caller will surface as MalformedRarArchive.
                return null
            }
            val plaintextBlock = Rar5Crypto.decrypt(key, prevCipher, cipherBlock)
            plaintext.write(plaintextBlock)
            prevCipher = cipherBlock

            headerEndOffset = tryComputeHeaderEnd(plaintext.buffer())
        }

        val plaintextHeader = plaintext.buffer().copyOf(headerEndOffset)
        val ciphertextHeaderSize = Rar5Crypto.ciphertextLengthFor(headerEndOffset.toLong())
        return DecryptedBlock(
            blockStart = blockStart,
            plaintextHeader = plaintextHeader,
            headerPlaintextSize = headerEndOffset.toLong(),
            headerCiphertextSize = ciphertextHeaderSize,
        )
    }

    /**
     * Given partial plaintext, returns the offset where the header ends if we
     * have enough bytes to determine it, or `null` if we need more. The header
     * end is `4 (CRC) + headerSizeVintBytes + headerSize`. The data area (if
     * any) starts after that and is encrypted but not part of the "header" we
     * need to decrypt for parsing — its size is in dataAreaSize, encoded inside
     * the body, which the parser reads from the plaintext we return.
     */
    private fun tryComputeHeaderEnd(plain: ByteArray): Int? {
        if (plain.size < 4 + 1) return null
        // Skip 4-byte CRC, then read headerSize vint
        val sizeResult = readVIntFromArray(plain, 4) ?: return null
        val (headerSize, headerSizeVintBytes) = sizeResult
        val end = 4 + headerSizeVintBytes + headerSize.toInt()
        return if (plain.size >= end) end else null
    }

    private fun readVIntFromArray(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        var value = 0L
        var bytesRead = 0
        var pos = offset
        while (bytesRead < MAX_VINT_BYTES && pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl (bytesRead * 7))
            bytesRead++
            pos++
            if (b and 0x80 == 0) return value to bytesRead
        }
        return null
    }

    private class ByteArrayOutputStreamWithBuffer {
        private var buffer = ByteArray(64)
        private var size = 0

        fun write(bytes: ByteArray) {
            ensureCapacity(size + bytes.size)
            System.arraycopy(bytes, 0, buffer, size, bytes.size)
            size += bytes.size
        }

        fun buffer(): ByteArray = buffer.copyOf(size)

        private fun ensureCapacity(needed: Int) {
            if (buffer.size < needed) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, needed))
            }
        }
    }

    /**
     * Result of decrypting one block's header. The caller uses [plaintextHeader]
     * for parsing (CRC, type, flags, body), reads dataAreaSize from the body,
     * and computes `nextBlockPosition = blockStart + IV_SIZE + headerCiphertextSize
     * + ciphertextLengthFor(dataAreaSize)`.
     */
    data class DecryptedBlock(
        val blockStart: Long,
        val plaintextHeader: ByteArray,
        val headerPlaintextSize: Long,
        val headerCiphertextSize: Long,
    ) {
        @Suppress("CyclomaticComplexMethod")
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptedBlock) return false
            return blockStart == other.blockStart &&
                plaintextHeader.contentEquals(other.plaintextHeader) &&
                headerPlaintextSize == other.headerPlaintextSize &&
                headerCiphertextSize == other.headerCiphertextSize
        }

        override fun hashCode(): Int {
            var result = blockStart.hashCode()
            result = 31 * result + plaintextHeader.contentHashCode()
            result = 31 * result + headerPlaintextSize.hashCode()
            result = 31 * result + headerCiphertextSize.hashCode()
            return result
        }

        @Suppress("UNUSED_PARAMETER")
        private fun bbForByteOrder() = ByteBuffer.wrap(plaintextHeader).order(ByteOrder.LITTLE_ENDIAN)
    }

    companion object {
        const val IV_SIZE = 16
        const val AES_BLOCK_SIZE = 16
        private const val MAX_VINT_BYTES = 10
    }
}
