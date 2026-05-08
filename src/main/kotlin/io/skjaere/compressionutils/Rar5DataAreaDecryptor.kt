package io.skjaere.compressionutils

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Decrypts arbitrary plaintext byte ranges from the data area of an encrypted
 * RAR5 block. This is the streaming-layer primitive: nzb-streamer (or any
 * caller serving Plex byte-range requests) calls [streamDataAreaPlaintext] (or
 * the array-returning [readDataAreaPlaintext] convenience wrapper) with the
 * desired plaintext range and gets back the corresponding bytes, with all the
 * AES-CBC chaining and offset math handled internally.
 *
 * **Layout the decryptor assumes** (per [SplitInfo] when [SplitInfo.encryption]
 * is non-null):
 * ```
 *   on-disk:    [16 bytes IV][AES-CBC ciphertext blocks ...]
 *   decrypted:  [CRC(4)][headerSize vint][type/flags/body ...][data area]
 *                |<------ plaintextHeaderSize ------>|
 * ```
 * The IV starts at `blockIvPosition`. The full plaintext block is
 * `plaintextHeaderSize + dataSize` bytes; ciphertext is the same rounded up to
 * a 16-byte boundary.
 *
 * **Random access**: AES-CBC decryption requires the *previous* ciphertext block
 * as the IV for any block past the first, so to start decrypting at AES-block
 * index `K` we read the previous ciphertext block (or the original IV if
 * `K==0`) into a JCE [Cipher] initialised in CBC mode, then feed ciphertext
 * blocks through `cipher.update()`. The cipher carries the chaining state
 * internally — we never have to manually compute IVs per chunk.
 *
 * **Memory bound**: streaming uses fixed-size ciphertext + plaintext buffers
 * (default [DEFAULT_CHUNK_BYTES]) regardless of how many bytes are requested.
 * Suitable for serving large byte-range requests (e.g. video seeking) without
 * proportional allocation.
 */
class Rar5DataAreaDecryptor(private val key: ByteArray) {

    /**
     * Streams `length` bytes of plaintext starting at `dataAreaPlaintextOffset`
     * (offset 0 = first byte of the data area, NOT the start of the block).
     *
     * Plaintext bytes are delivered in fixed-size chunks via [sink] as they're
     * decrypted. Memory usage stays bounded at `chunkBytes` regardless of
     * `length` — suitable for streaming gigabyte-scale ranges to a network
     * channel.
     *
     * @param sourceStream the encrypted archive stream (e.g. nzb-streamer's
     *   NntpSeekableInputStream or a plain FileSeekableInputStream).
     * @param blockIvPosition where the encrypted block starts on disk (16-byte IV
     *   followed by ciphertext). From [SplitInfo.dataStartPosition] when encrypted.
     * @param plaintextHeaderSize bytes of plaintext before the data area within
     *   this block. From [SplitEncryptionInfo.plaintextHeaderSize].
     * @param dataAreaPlaintextOffset byte offset within the data area to start
     *   reading from (0-based).
     * @param length number of plaintext bytes to read. Use [Long] so we can serve
     *   large byte ranges without overflow.
     * @param chunkBytes ciphertext+plaintext buffer size in bytes. Must be a
     *   multiple of 16. Default 64 KiB.
     * @param sink invoked once per chunk with `(buffer, offset, length)` of
     *   freshly-decrypted plaintext to emit. The buffer is reused across calls,
     *   so the sink must consume the bytes before returning.
     */
    suspend fun streamDataAreaPlaintext(
        sourceStream: SeekableInputStream,
        blockIvPosition: Long,
        plaintextHeaderSize: Long,
        dataAreaPlaintextOffset: Long,
        length: Long,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
        dataAreaIv: ByteArray? = null,
        sink: suspend (ByteArray, Int, Int) -> Unit,
    ) {
        require(length >= 0) { "length must be non-negative, got $length" }
        require(dataAreaPlaintextOffset >= 0) {
            "dataAreaPlaintextOffset must be non-negative, got $dataAreaPlaintextOffset"
        }
        require(chunkBytes > 0 && chunkBytes % AES_BLOCK_SIZE == 0) {
            "chunkBytes must be a positive multiple of $AES_BLOCK_SIZE, got $chunkBytes"
        }
        require(dataAreaIv == null || dataAreaIv.size == IV_SIZE) {
            "dataAreaIv must be $IV_SIZE bytes when present, got ${dataAreaIv?.size}"
        }
        if (length == 0L) return

        // Two encryption modes:
        // 1. Per-file IV (modern RAR5 with extra-area type 1): header and data area are
        //    separate AES-CBC streams. Header occupies the first ceil(plaintextHeaderSize/16)*16
        //    ciphertext bytes after the archive IV. Data area's ciphertext starts immediately
        //    after, with its own IV (`dataAreaIv`). Random access into the data area uses the
        //    previous data-area ciphertext block as IV (or `dataAreaIv` for block 0).
        // 2. Legacy single-stream mode (`dataAreaIv == null`): header and data area form one
        //    AES-CBC stream. Plaintext-byte K of the data area is at plaintext offset
        //    `plaintextHeaderSize + K` of the combined stream.
        val ivBuffer = ByteArray(IV_SIZE)
        val firstAesBlock: Int
        val sliceStart: Int

        if (dataAreaIv != null) {
            val firstPtOffset = dataAreaPlaintextOffset
            firstAesBlock = (firstPtOffset / AES_BLOCK_SIZE).toInt()
            sliceStart = (firstPtOffset - firstAesBlock.toLong() * AES_BLOCK_SIZE).toInt()
            // Data area ciphertext starts right after the encrypted header on disk.
            val dataAreaCiphertextBase = blockIvPosition + IV_SIZE + ciphertextLengthFor(plaintextHeaderSize)
            if (firstAesBlock == 0) {
                System.arraycopy(dataAreaIv, 0, ivBuffer, 0, IV_SIZE)
                sourceStream.seek(dataAreaCiphertextBase)
            } else {
                // Read previous ciphertext block of the data area as IV
                sourceStream.seek(dataAreaCiphertextBase + (firstAesBlock - 1).toLong() * AES_BLOCK_SIZE)
                readFully(sourceStream, ivBuffer, 0, IV_SIZE)
                // sourceStream is now at dataAreaCiphertextBase + firstAesBlock*16
            }
        } else {
            val firstPtOffset = plaintextHeaderSize + dataAreaPlaintextOffset
            firstAesBlock = (firstPtOffset / AES_BLOCK_SIZE).toInt()
            sliceStart = (firstPtOffset - firstAesBlock.toLong() * AES_BLOCK_SIZE).toInt()
            // Single-stream: the IV for our first decryption block is either the archive's
            // IV (block 0) or the previous ciphertext block (block K>0).
            val ivSourcePosition = blockIvPosition +
                if (firstAesBlock == 0) 0L else IV_SIZE + (firstAesBlock - 1).toLong() * AES_BLOCK_SIZE
            sourceStream.seek(ivSourcePosition)
            readFully(sourceStream, ivBuffer, 0, IV_SIZE)
            // sourceStream is now positioned at the first ciphertext block to decrypt
        }

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBuffer))

        val ciphertextBuffer = ByteArray(chunkBytes)
        val plaintextBuffer = ByteArray(chunkBytes)
        var emittedPlaintext = 0L
        var firstChunk = true

        while (emittedPlaintext < length) {
            // How many plaintext bytes still need to be produced + the unaligned
            // prefix to discard on the first chunk
            val plaintextRemaining = length - emittedPlaintext
            val plaintextToProduce = plaintextRemaining + (if (firstChunk) sliceStart else 0)
            val ciphertextNeeded = ciphertextLengthFor(plaintextToProduce)
            val readSize = min(chunkBytes.toLong(), ciphertextNeeded).toInt()
            check(readSize % AES_BLOCK_SIZE == 0) {
                "readSize $readSize must be a multiple of $AES_BLOCK_SIZE — bug in chunk math"
            }

            readFully(sourceStream, ciphertextBuffer, 0, readSize)

            val plaintextProduced = cipher.update(ciphertextBuffer, 0, readSize, plaintextBuffer, 0)
            check(plaintextProduced == readSize) {
                "JCE returned $plaintextProduced plaintext bytes for $readSize ciphertext bytes — " +
                    "expected equality for AES/CBC/NoPadding with full-block input"
            }

            val emitOffset = if (firstChunk) sliceStart else 0
            val emitLen = min((plaintextProduced - emitOffset).toLong(), plaintextRemaining).toInt()
            sink(plaintextBuffer, emitOffset, emitLen)
            emittedPlaintext += emitLen
            firstChunk = false
        }
    }

    /**
     * Convenience wrapper around [streamDataAreaPlaintext] that buffers the entire
     * range into a `ByteArray`. Use only for small ranges (tests, metadata) — for
     * production streaming use [streamDataAreaPlaintext] directly to avoid
     * allocating proportional to `length`.
     */
    suspend fun readDataAreaPlaintext(
        sourceStream: SeekableInputStream,
        blockIvPosition: Long,
        plaintextHeaderSize: Long,
        dataAreaPlaintextOffset: Long,
        length: Int,
        dataAreaIv: ByteArray? = null,
    ): ByteArray {
        val out = ByteArray(length)
        var written = 0
        streamDataAreaPlaintext(
            sourceStream = sourceStream,
            blockIvPosition = blockIvPosition,
            plaintextHeaderSize = plaintextHeaderSize,
            dataAreaPlaintextOffset = dataAreaPlaintextOffset,
            length = length.toLong(),
            dataAreaIv = dataAreaIv,
        ) { buffer, offset, len ->
            System.arraycopy(buffer, offset, out, written, len)
            written += len
        }
        return out
    }

    private suspend fun readFully(stream: SeekableInputStream, buf: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = stream.read(buf, offset + read, length - read)
            if (n == -1) {
                throw IllegalStateException(
                    "Unexpected end of encrypted stream: wanted $length bytes at offset $offset, got $read",
                )
            }
            read += n
        }
    }

    private fun ciphertextLengthFor(plaintextSize: Long): Long {
        val remainder = plaintextSize % AES_BLOCK_SIZE
        return if (remainder == 0L) plaintextSize else plaintextSize + (AES_BLOCK_SIZE - remainder)
    }

    companion object {
        private const val IV_SIZE = 16
        private const val AES_BLOCK_SIZE = 16
        private const val DEFAULT_CHUNK_BYTES = 64 * 1024
    }
}
