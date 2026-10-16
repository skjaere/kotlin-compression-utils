package io.skjaere.compressionutils

import java.io.IOException

/**
 * Thrown when a 7z archive's headers lie past the end of its bytes: its signature header says where
 * its end header is (and that one where an encoded header's data is), and the archive, its volumes
 * laid end to end, ends before them, as a set that lacks a volume, or one cut short, does. The 7z
 * counterpart of [MalformedRarArchiveException]: the archive is broken as it is, not the reading of
 * it, and reading it again won't change that.
 */
class MalformedSevenZipArchiveException(message: String) : IOException(message)
