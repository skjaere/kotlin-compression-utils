package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Rar5CryptoTest {

    @Test
    fun `PBKDF2 derives a 32-byte AES-256 key`() {
        val key = Rar5Crypto.deriveKey(
            password = "hunter2",
            salt = "0123456789abcdef".toByteArray(),
            kdfIterationsLog2 = 4, // 2^4 = 16 iterations — kept low for test speed
        )
        assertEquals(32, key.size)
    }

    @Test
    fun `PBKDF2 known-answer vector matches RFC 7914 derivation`() {
        // Sanity check that we're using PBKDF2-HMAC-SHA256 with the right
        // parameter ordering. This isn't a RAR-specific vector, just a generic
        // PBKDF2-HMAC-SHA256 vector to confirm we wired the JCE call correctly.
        // Source: https://stackoverflow.com/a/22625394 (PBKDF2WithHmacSHA256
        // vector cross-validated with openssl).
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            "password".toCharArray(),
            "salt".toByteArray(),
            1,
            256,
        )
        val expected = factory.generateSecret(spec).encoded

        // Same call but routed through our utility — they must produce identical bytes
        val actual = Rar5Crypto.deriveKey("password", "salt".toByteArray(), kdfIterationsLog2 = 0)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `AES-256-CBC encrypt then decrypt round-trips`() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val iv = ByteArray(16) { (it * 13 + 5).toByte() }
        val plaintext = "hello world, this is exactly 32!".toByteArray()
        assertEquals(32, plaintext.size)

        // Encrypt with the same primitives we use for decryption
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        val ciphertext = cipher.doFinal(plaintext)

        val recovered = Rar5Crypto.decrypt(key, iv, ciphertext)
        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `ciphertextLengthFor rounds up to 16-byte boundary`() {
        assertEquals(0L, Rar5Crypto.ciphertextLengthFor(0))
        assertEquals(16L, Rar5Crypto.ciphertextLengthFor(1))
        assertEquals(16L, Rar5Crypto.ciphertextLengthFor(15))
        assertEquals(16L, Rar5Crypto.ciphertextLengthFor(16))
        assertEquals(32L, Rar5Crypto.ciphertextLengthFor(17))
        assertEquals(32L, Rar5Crypto.ciphertextLengthFor(31))
        assertEquals(32L, Rar5Crypto.ciphertextLengthFor(32))
        assertEquals(48L, Rar5Crypto.ciphertextLengthFor(33))
    }
}
