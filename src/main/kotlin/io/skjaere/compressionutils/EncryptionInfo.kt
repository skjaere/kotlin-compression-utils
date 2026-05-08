package io.skjaere.compressionutils

/**
 * Crypto parameters extracted from a RAR5 `HEAD_CRYPT` block. Returned via
 * [ListFilesResult.Encrypted] so callers can persist them and re-attempt parsing
 * once a password is available (Phase 3 of the encryption-support effort).
 *
 * Fields may be null if the `HEAD_CRYPT` body was truncated; the result is still
 * surfaced so callers know the archive is encrypted, just without the parameters
 * needed to actually decrypt.
 */
data class EncryptionInfo(
    val archiveName: String?,
    val volumeIndex: Int,
    /** RAR5 currently defines only `0` (AES-256). */
    val encryptionVersion: Long?,
    /** PBKDF2 iteration count is `2^kdfIterationsLog2`. */
    val kdfIterationsLog2: Int?,
    /** 16-byte PBKDF2 salt. */
    val salt: ByteArray?,
    /** Whether the `HEAD_CRYPT` block carried a password-check value. */
    val hasPasswordCheck: Boolean,
) {
    @Suppress("CyclomaticComplexMethod")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptionInfo) return false
        return archiveName == other.archiveName &&
            volumeIndex == other.volumeIndex &&
            encryptionVersion == other.encryptionVersion &&
            kdfIterationsLog2 == other.kdfIterationsLog2 &&
            (salt?.contentEquals(other.salt) ?: (other.salt == null)) &&
            hasPasswordCheck == other.hasPasswordCheck
    }

    override fun hashCode(): Int {
        var result = archiveName?.hashCode() ?: 0
        result = 31 * result + volumeIndex
        result = 31 * result + (encryptionVersion?.hashCode() ?: 0)
        result = 31 * result + (kdfIterationsLog2 ?: 0)
        result = 31 * result + (salt?.contentHashCode() ?: 0)
        result = 31 * result + hasPasswordCheck.hashCode()
        return result
    }
}
