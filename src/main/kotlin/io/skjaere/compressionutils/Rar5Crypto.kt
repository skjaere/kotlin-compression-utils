package io.skjaere.compressionutils

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * RAR5 encryption primitives: PBKDF2-HMAC-SHA256 key derivation + AES-256-CBC
 * block decryption, plus the helpers needed by the parser to advance through an
 * encrypted block stream.
 *
 * Per rar5tech.txt §4 (encryption):
 *   - Key is derived once per archive: PBKDF2 over (password, salt, 2^kdfCount)
 *     producing a 32-byte AES-256 key.
 *   - Every block after `HEAD_CRYPT` is on-disk wrapped as:
 *         [16 bytes AES-CBC IV][N×16 bytes ciphertext]
 *     where the plaintext is the original block (CRC + size vint + type/flags +
 *     body + optional data area), padded to a multiple of 16 bytes with zero
 *     bytes. The same key is reused; the IV is unique per block.
 */
object Rar5Crypto {
    private const val KEY_LENGTH_BITS = 256
    private const val AES_BLOCK_SIZE = 16

    /**
     * Derives the AES-256 archive key. Uses `PBKDF2WithHmacSHA256` which is in
     * the JDK's default JCE provider since Java 8 — no extra dependency.
     */
    fun deriveKey(password: String, salt: ByteArray, kdfIterationsLog2: Int): ByteArray {
        val iterations = 1 shl kdfIterationsLog2
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Decrypts AES-256-CBC ciphertext given the key and IV. No padding stripping —
     * RAR5 zero-pads ciphertext to a 16-byte boundary and the parser already knows
     * the true plaintext length from the block's `headerSize` vint, so we use
     * `AES/CBC/NoPadding` and let the parser truncate.
     */
    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size % AES_BLOCK_SIZE == 0) {
            "Ciphertext size ${ciphertext.size} is not a multiple of $AES_BLOCK_SIZE"
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Rounds [size] up to the next AES block boundary. Used by the parser to know
     * how many ciphertext bytes back the source stream needs to be advanced for a
     * block of `size` plaintext bytes.
     */
    fun ciphertextLengthFor(plaintextSize: Long): Long {
        val remainder = plaintextSize % AES_BLOCK_SIZE
        return if (remainder == 0L) plaintextSize else plaintextSize + (AES_BLOCK_SIZE - remainder)
    }
}
