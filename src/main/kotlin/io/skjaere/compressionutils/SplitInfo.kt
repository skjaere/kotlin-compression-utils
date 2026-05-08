package io.skjaere.compressionutils

/**
 * Information about a portion of a split file in a specific volume.
 * For streaming, you need to:
 * 1. Seek to dataStartPosition in the concatenated stream
 * 2. Read dataSize bytes
 * 3. Move to next SplitInfo and repeat
 *
 * For encrypted splits, [encryption] carries the crypto layout — the data is not
 * directly readable at `dataStartPosition`; use [Rar5DataAreaDecryptor] to extract
 * plaintext byte ranges from the AES-CBC encrypted block.
 */
data class SplitInfo(
    val volumeIndex: Int,           // Which volume this portion is in (0-based)
    val dataStartPosition: Long,    // For unencrypted: absolute byte position where this portion's data starts.
                                    // For encrypted: position of the encrypted block's 16-byte IV; the AES-CBC
                                    //   ciphertext follows immediately. Use [encryption] for plaintext layout.
    val dataSize: Long,             // Bytes of file data (plaintext) in this portion
    /**
     * Present iff this split lives inside a RAR5 encrypted block. Phase 4 streaming
     * uses this to map plaintext byte ranges back to encrypted byte ranges + decrypt.
     */
    val encryption: SplitEncryptionInfo? = null,
)

/**
 * Crypto-layout metadata for a single encrypted-block split. The block on disk is:
 * ```
 *   [16 bytes IV][N×16 bytes AES-CBC ciphertext]
 *      starting at SplitInfo.dataStartPosition
 * ```
 * The plaintext layout (after AES-CBC decryption) is the original RAR5 block:
 * ```
 *   [CRC(4)][headerSize vint][type vint][flags vint][...body...][dataArea]
 *      |<--------- plaintextHeaderSize ---------->|
 * ```
 * To extract plaintext byte `N` of the data area, AES-decrypt up to plaintext offset
 * `plaintextHeaderSize + N` using the block's IV-then-ciphertext-chain.
 */
data class SplitEncryptionInfo(
    /** Bytes of plaintext in this block before the data area (CRC + size vint + body). */
    val plaintextHeaderSize: Long,
    /** PBKDF2 salt from the archive's HEAD_CRYPT (16 bytes). Same for every encrypted block in the archive,
     *  but denormalised onto each split so the streaming layer doesn't need a separate plumbing path. */
    val salt: ByteArray,
    /** PBKDF2 iteration count exponent (`iterations = 1 shl kdfIterationsLog2`). Same for every encrypted
     *  block in the archive, denormalised for the same reason as [salt]. */
    val kdfIterationsLog2: Int,
    /** Byte offset within this split's plaintext data area where the streaming layer should start emitting.
     *  Zero for unsliced splits (full data area). When the streaming pipeline trims an encrypted split for a
     *  byte-range request, it advances this field instead of moving [SplitInfo.dataStartPosition] — that
     *  must stay anchored to the on-disk IV. */
    val dataAreaPlaintextOffset: Long = 0,
    /** Per-file encryption record IV (rar5tech.txt §4.3.4 extra-area type 1). When present, the data area is
     *  a separate AES-CBC stream from the header — its first ciphertext block is decrypted using this IV,
     *  and subsequent blocks chain off the previous data-area ciphertext block. Null when the file uses the
     *  legacy "header and data are one CBC stream" mode (rare; modern RAR5 always emits a per-file record
     *  when file data is encrypted). */
    val dataAreaIv: ByteArray? = null,
) {
    // ByteArray equals/hashCode by reference is the wrong contract for a data class —
    // override so SplitInfo equality works as expected (two copies of the same archive's
    // metadata should compare equal).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SplitEncryptionInfo) return false
        return plaintextHeaderSize == other.plaintextHeaderSize &&
            kdfIterationsLog2 == other.kdfIterationsLog2 &&
            dataAreaPlaintextOffset == other.dataAreaPlaintextOffset &&
            salt.contentEquals(other.salt) &&
            (dataAreaIv?.contentEquals(other.dataAreaIv) ?: (other.dataAreaIv == null))
    }

    override fun hashCode(): Int {
        var result = plaintextHeaderSize.hashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + kdfIterationsLog2
        result = 31 * result + dataAreaPlaintextOffset.hashCode()
        result = 31 * result + (dataAreaIv?.contentHashCode() ?: 0)
        return result
    }
}
