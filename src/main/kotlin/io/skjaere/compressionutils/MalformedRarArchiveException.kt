package io.skjaere.compressionutils

import java.io.IOException

/**
 * Thrown when the RAR parser, after consuming an end-of-archive marker, encounters
 * bytes that are neither padding nor the start of the next volume's signature. The
 * archive is malformed (or possibly truncated / corrupted in transit). Carries the
 * archive name, the volume index where parsing stopped, and the unexpected byte
 * sequence so callers can surface a useful diagnostic without reformatting it.
 *
 * Replaces the previous `logger.warn(...) + break` behavior — that swallowed the
 * anomaly and silently returned partial entries, which made downstream parsing
 * decisions unreliable and gave operators no visibility. Now the parsing aborts
 * with a typed exception so the caller can record / report it explicitly.
 */
class MalformedRarArchiveException(
    val archiveName: String?,
    val volumeIndex: Int,
    val unexpectedBytes: ByteArray,
    message: String,
) : IOException(message)
