package io.skjaere.compressionutils

import java.io.IOException

/**
 * **Internal** sentinel used by the RAR5 parser to escape its deeply-nested
 * parsing loop when a `HEAD_CRYPT` block is encountered. NOT part of the public
 * API — public callers see [ListFilesResult.Encrypted] instead, returned by the
 * top-level [ArchiveService.listFiles] (which catches this sentinel and converts).
 *
 * The reason for using an exception here at all is purely control-flow: the
 * parser's outer `while (true)` loop has many `break` / `continue` paths nested
 * across volume-boundary handling, padding detection, and per-block parsing —
 * threading a "stop, this archive is encrypted" signal through normal returns
 * would require restructuring all of those paths. A thrown sentinel that's
 * caught one frame up at the public-API boundary is the minimal disruption.
 */
internal class EncryptedRarArchiveException(
    val info: EncryptionInfo,
    /**
     * `true` when a password was supplied but failed CRC validation against the
     * first encrypted block. Caller (ArchiveService.listFiles) translates this
     * to [ListFilesResult.Encrypted.passwordIncorrect].
     */
    val passwordIncorrect: Boolean = false,
    message: String,
) : IOException(message)
