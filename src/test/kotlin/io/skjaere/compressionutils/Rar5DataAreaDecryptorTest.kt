package io.skjaere.compressionutils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.assertContentEquals

class Rar5DataAreaDecryptorTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val iv = ByteArray(16) { (it * 11 + 5).toByte() }

    @Test
    fun `round-trips a full plaintext block - decrypts identically to in-bulk`() = runBlocking {
        // Construct a synthetic encrypted RAR5 block. Plaintext is 256 bytes
        // (16 AES blocks); first 80 bytes are the "header", remainder is data
        // area. We don't care that the bytes form a valid RAR5 header — the
        // decryptor only does crypto, not parsing.
        val plaintext = Random(42).nextBytes(256)
        val plaintextHeaderSize = 80L
        val dataAreaSize = (plaintext.size - plaintextHeaderSize).toInt()
        val ciphertext = aesCbcEncrypt(plaintext, key, iv)

        // Wrap [iv || ciphertext] as the on-disk source, mimicking the actual
        // encrypted-block format: [16 byte IV][N×16 byte ciphertext]
        val onDisk = iv + ciphertext
        val source = ByteArraySeekableInputStream(onDisk)

        val decryptor = Rar5DataAreaDecryptor(key)

        // Decrypt the whole data area at once
        val full = decryptor.readDataAreaPlaintext(
            sourceStream = source,
            blockIvPosition = 0,
            plaintextHeaderSize = plaintextHeaderSize,
            dataAreaPlaintextOffset = 0,
            length = dataAreaSize,
        )
        assertContentEquals(
            plaintext.copyOfRange(plaintextHeaderSize.toInt(), plaintext.size),
            full,
        )
    }

    @Test
    fun `random-access at arbitrary offsets matches in-bulk decrypt`() = runBlocking {
        // Same setup, but extract a series of small ranges from random offsets
        // and verify each matches the corresponding slice of the bulk plaintext.
        val plaintext = Random(7).nextBytes(512)
        val plaintextHeaderSize = 19L  // deliberately unaligned to AES boundary
        val dataAreaPlaintext = plaintext.copyOfRange(plaintextHeaderSize.toInt(), plaintext.size)
        val ciphertext = aesCbcEncrypt(plaintext, key, iv)
        val onDisk = iv + ciphertext
        val source = ByteArraySeekableInputStream(onDisk)
        val decryptor = Rar5DataAreaDecryptor(key)

        // Test cases: edge offsets + middle + tail, varying lengths including
        // ones that cross AES boundaries
        val ranges = listOf(
            0L to 1,           // single first byte
            0L to 16,          // exactly one AES block
            0L to 17,          // one block + 1 byte
            5L to 30,          // unaligned start, multi-block
            16L to 16,         // aligned middle
            100L to 200,       // multi-block middle
            (dataAreaPlaintext.size - 1).toLong() to 1, // last byte
            (dataAreaPlaintext.size - 50).toLong() to 50, // tail slice
        )
        for ((offset, length) in ranges) {
            val expected = dataAreaPlaintext.copyOfRange(offset.toInt(), offset.toInt() + length)
            val actual = decryptor.readDataAreaPlaintext(
                sourceStream = source,
                blockIvPosition = 0,
                plaintextHeaderSize = plaintextHeaderSize,
                dataAreaPlaintextOffset = offset,
                length = length,
            )
            assertContentEquals(expected, actual, "Mismatch at offset=$offset length=$length")
        }
    }

    @Test
    fun `streaming decrypt produces same bytes as in-bulk decrypt across chunk sizes`() = runBlocking {
        // Bulk-encrypt a known plaintext with the same key+iv; verify that
        // streamDataAreaPlaintext emits the same bytes regardless of the chunk
        // size used. This proves CBC chaining is preserved across cipher.update()
        // calls.
        val plaintext = Random(123).nextBytes(4096)
        val plaintextHeaderSize = 37L  // unaligned
        val dataAreaPlaintext = plaintext.copyOfRange(plaintextHeaderSize.toInt(), plaintext.size)
        val ciphertext = aesCbcEncrypt(plaintext, key, iv)
        val onDisk = iv + ciphertext

        val decryptor = Rar5DataAreaDecryptor(key)

        // Test ranges crossing many chunk boundaries with several chunk sizes
        val chunkSizes = listOf(16, 32, 256, 1024, 4096)
        val ranges = listOf(
            0L to dataAreaPlaintext.size.toLong(),  // whole data area
            17L to 1000L,                           // unaligned start, multi-chunk
            1L to 17L,                              // tiny range across an AES boundary
            (dataAreaPlaintext.size - 100).toLong() to 100L,  // tail
        )
        for (chunkSize in chunkSizes) {
            for ((offset, len) in ranges) {
                val source = ByteArraySeekableInputStream(onDisk)
                val collected = mutableListOf<Byte>()
                decryptor.streamDataAreaPlaintext(
                    sourceStream = source,
                    blockIvPosition = 0,
                    plaintextHeaderSize = plaintextHeaderSize,
                    dataAreaPlaintextOffset = offset,
                    length = len,
                    chunkBytes = chunkSize,
                ) { buf, off, l ->
                    for (i in off until off + l) collected.add(buf[i])
                }
                val expected = dataAreaPlaintext.copyOfRange(offset.toInt(), offset.toInt() + len.toInt())
                assertContentEquals(
                    expected,
                    collected.toByteArray(),
                    "chunkSize=$chunkSize offset=$offset len=$len",
                )
            }
        }
    }

    @Test
    fun `streaming uses bounded memory regardless of range length`() = runBlocking {
        // Encrypt a synthetic large block and stream-decrypt it with a small
        // chunk. The sink emits one byte at a time would still work, but more
        // importantly: the *largest sink-emission size* never exceeds chunkBytes.
        // This proves the streaming path doesn't materialise the whole range
        // into a single buffer before emitting.
        val plaintext = Random(55).nextBytes(64 * 1024)  // 64 KiB plaintext
        val ciphertext = aesCbcEncrypt(plaintext, key, iv)
        val source = ByteArraySeekableInputStream(iv + ciphertext)
        val decryptor = Rar5DataAreaDecryptor(key)

        val chunkBytes = 256  // tiny chunk to force many emissions
        var maxEmitSize = 0
        var totalEmitted = 0L
        decryptor.streamDataAreaPlaintext(
            sourceStream = source,
            blockIvPosition = 0,
            plaintextHeaderSize = 0,
            dataAreaPlaintextOffset = 0,
            length = plaintext.size.toLong(),
            chunkBytes = chunkBytes,
        ) { _, _, l ->
            if (l > maxEmitSize) maxEmitSize = l
            totalEmitted += l
        }
        kotlin.test.assertEquals(plaintext.size.toLong(), totalEmitted, "Should emit every byte")
        kotlin.test.assertTrue(
            maxEmitSize <= chunkBytes,
            "Largest emission $maxEmitSize must not exceed chunk size $chunkBytes",
        )
        // Sanity: with a 256-byte chunk and 64KiB range we expect ~256 emissions
        // — proves we're not just one big call
        kotlin.test.assertTrue(maxEmitSize <= chunkBytes, "Should emit in many small chunks")
    }

    @Test
    fun `repeated decrypt at same offset is deterministic`() = runBlocking {
        val plaintext = Random(99).nextBytes(128)
        val ciphertext = aesCbcEncrypt(plaintext, key, iv)
        val source = ByteArraySeekableInputStream(iv + ciphertext)
        val decryptor = Rar5DataAreaDecryptor(key)

        val first = decryptor.readDataAreaPlaintext(source, 0, 0, 32, 16)
        val second = decryptor.readDataAreaPlaintext(source, 0, 0, 32, 16)
        assertContentEquals(first, second)
    }

    private fun aesCbcEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(plaintext.size % 16 == 0) { "plaintext must be a multiple of 16 for NoPadding" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }
}
