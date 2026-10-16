package io.skjaere.compressionutils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A PAR2 file describing [files] (name to bytes) as par2cmdline's index does: a FileDesc packet for
 * each, with its file id, the MD5 of the file and of its first 16 KB, and its length, each packet
 * with its MD5 over the recovery set id onward.
 */
internal fun par2File(files: List<Pair<String, ByteArray>>): ByteArray {
    val setId = md5("set".toByteArray())
    return files.fold(ByteArray(0)) { all, (name, data) ->
        val padded = name.toByteArray(Charsets.UTF_8).let { it.copyOf((it.size + 3) and 3.inv()) }
        val hash16k = md5(data.copyOf(minOf(data.size, 16 * 1024)))
        val length = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array()
        val fileId = md5(hash16k + length + padded)
        all + par2Packet(setId, "FileDesc", fileId + md5(data) + hash16k + length + padded)
    }
}

private fun par2Packet(setId: ByteArray, type: String, body: ByteArray): ByteArray {
    val typed = setId + "PAR 2.0\u0000${type.padEnd(8, '\u0000')}".toByteArray(Charsets.ISO_8859_1) + body
    return ByteBuffer.allocate(32 + typed.size).order(ByteOrder.LITTLE_ENDIAN)
        .put("PAR2\u0000PKT".toByteArray(Charsets.ISO_8859_1))
        .putLong(32L + typed.size)
        .put(md5(typed))
        .put(typed)
        .array()
}

private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
