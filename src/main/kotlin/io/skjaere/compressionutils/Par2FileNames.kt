package io.skjaere.compressionutils

import java.security.MessageDigest

/**
 * The names a release's PAR2 file descriptions give its files, which survive a poster's
 * obfuscation of the names they were posted under. A description names the file whose first
 * 16 KB (the whole file, when it is shorter) have the MD5 it gives and whose length is the one it
 * gives. Files can start alike: a DVD's VIDEO_TS.BUP is a copy of its VIDEO_TS.IFO, and every disc
 * image's first 32 KB are zeros. Their lengths may still tell them apart; where they don't either,
 * none of the descriptions with different names that give those names a file, where a lookup by
 * the MD5 alone gave every such file the last one's name.
 */
class Par2FileNames(descriptions: List<Par2Parser.Par2FileDescription>) {
    private val named = descriptions.filter { it.filename.isNotBlank() }

    // Null where descriptions with different names give the same.
    private val byHashAndLength: Map<Pair<String, Long>, String?> = named
        .groupBy({ it.hash16kHex() to it.fileSize }, { it.filename })
        .mapValues { (_, names) -> names.distinct().singleOrNull() }

    /**
     * The names by the MD5 of the first 16 KB alone, for a caller that doesn't know a file's
     * length: an MD5 that descriptions with different names give names nothing.
     */
    val byHash16k: Map<String, String> = named
        .groupBy({ it.hash16kHex() }, { it.filename })
        .mapNotNull { (hash, names) -> names.distinct().singleOrNull()?.let { hash to it } }
        .toMap()

    /**
     * The name of the file [length] bytes long whose first 16 KB have the MD5 [hash16kHex] (in hex,
     * as [Par2Parser.Par2FileDescription.hash16kHex] gives it), or null when no description gives
     * those, or several with different names do.
     */
    fun nameOf(hash16kHex: String, length: Long): String? = byHashAndLength[hash16kHex.lowercase() to length]

    /** The name of the file [length] bytes long that starts with [first16kb] (its first 16 KB, or all of a shorter file). */
    fun nameOf(first16kb: ByteArray, length: Long): String? = nameOf(md5Hex(first16kb), length)

    companion object {
        private const val FIRST_16KB = 16 * 1024

        /** The names [par2Data]'s descriptions give; none when there is none, or it doesn't parse. */
        fun parse(par2Data: ByteArray?): Par2FileNames = Par2FileNames(
            par2Data?.let {
                try {
                    Par2Parser.parse(it).files
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
        )

        /** The MD5 in hex of [first16kb]'s first 16 KB, as a PAR2 description gives a file's. */
        fun md5Hex(first16kb: ByteArray): String =
            MessageDigest.getInstance("MD5").apply { update(first16kb, 0, minOf(first16kb.size, FIRST_16KB)) }
                .digest().joinToString("") { "%02x".format(it) }
    }
}
