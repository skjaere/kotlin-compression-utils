package io.skjaere.compressionutils.generation

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object BinaryWriteUtils {

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    /** RAR4 CRC16: low 16 bits of CRC32 */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        return crc32(data, offset, length) and 0xFFFF
    }

    /** Encode a value as a RAR5 variable-length integer (vint). */
    fun encodeVInt(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v >= 0x80) {
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
        return out.toByteArray()
    }

    /**
     * Encode a value as a 7zip variable-length uint64.
     *
     * The first byte's leading 1-bits indicate the number of additional bytes:
     * - 0xxxxxxx = 1 byte total (value 0-127)
     * - 10xxxxxx + 1 byte = 2 bytes total
     * - etc.
     */
    fun encode7zUInt64(value: Long): ByteArray {
        if (value < 0x80) {
            return byteArrayOf(value.toByte())
        }

        // Determine how many extra bytes we need.
        // With numExtra extra bytes, we have (7-numExtra) bits in the first byte
        // and 8*numExtra bits in the extra bytes, for a total of 7-numExtra + 8*numExtra bits.
        var numExtra = 1
        while (numExtra < 8) {
            val shift = 7 - numExtra
            val totalBits = shift + 8 * numExtra
            if (value < (1L shl totalBits)) break
            numExtra++
        }

        val out = ByteArray(1 + numExtra)

        // The decoder reconstructs: value = firstByteBits | (byte0 << shift) | (byte1 << (shift+8)) | ...
        // where shift = 7 - numExtra
        val shift = if (numExtra < 8) 7 - numExtra else 0

        // First byte: numExtra leading 1-bits + value's low 'shift' bits
        val firstByteMask = (0xFF shl (8 - numExtra)) and 0xFF
        val firstByteBits = if (shift > 0) (value and ((1L shl shift) - 1)).toInt() else 0
        out[0] = (firstByteMask or firstByteBits).toByte()

        // Extra bytes: remaining value bits (value >> shift) in LE order
        val remaining = value ushr shift
        for (i in 0 until numExtra) {
            out[1 + i] = ((remaining ushr (8 * i)) and 0xFF).toByte()
        }

        return out
    }

    fun writeLE16(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    fun writeLE32(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        return buf.array()
    }

    fun writeLE64(value: Long): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(value)
        return buf.array()
    }

    fun ByteArrayOutputStream.writeBytes(bytes: ByteArray) {
        write(bytes)
    }
}
