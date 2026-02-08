package io.skjaere.compressionutils

/**
 * Wrapper that compares bytes read from two streams simultaneously.
 * Logs any mismatches for debugging. Useful for testing stream implementations.
 */
class ComparingSeekableInputStream(
    private val testStream: SeekableInputStream,
    private val referenceStream: SeekableInputStream,
    private val name: String = "stream"
) : SeekableInputStream {
    private var comparisonEnabled = true
    private var mismatchCount = 0
    private val maxMismatchesToLog = 20

    override fun read(): Int {
        val testByte = testStream.read()
        if (comparisonEnabled) {
            val refByte = referenceStream.read()
            if (testByte != refByte && mismatchCount < maxMismatchesToLog) {
                println("[$name] Mismatch at position ${testStream.position() - 1}: test=0x${testByte.toString(16)}, ref=0x${refByte.toString(16)}")
                mismatchCount++
                if (mismatchCount >= maxMismatchesToLog) {
                    println("[$name] Too many mismatches, disabling comparison")
                    comparisonEnabled = false
                }
            }
        }
        return testByte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = testStream.read(buffer, offset, length)

        if (comparisonEnabled && bytesRead > 0) {
            val refData = ByteArray(length)
            val refBytesRead = referenceStream.read(refData, 0, length)

            if (bytesRead != refBytesRead) {
                println("[$name] Read size mismatch at position ${testStream.position() - bytesRead}: test=$bytesRead, ref=$refBytesRead")
            }

            // Compare bytes
            val bytesToCompare = minOf(bytesRead, refBytesRead)
            for (i in 0 until bytesToCompare) {
                if (buffer[offset + i] != refData[i] && mismatchCount < maxMismatchesToLog) {
                    val pos = testStream.position() - bytesRead + i
                    println("[$name] Byte mismatch at position $pos (offset $i in read): test=0x${buffer[offset + i].toString(16)}, ref=0x${refData[i].toString(16)}")
                    mismatchCount++
                    if (mismatchCount >= maxMismatchesToLog) {
                        println("[$name] Too many mismatches (${mismatchCount}+), disabling comparison")
                        comparisonEnabled = false
                        break
                    }
                }
            }
        }

        return bytesRead
    }

    override fun seek(position: Long) {
        testStream.seek(position)
        if (comparisonEnabled) {
            referenceStream.seek(position)
        }
    }

    override fun position(): Long = testStream.position()

    override fun size(): Long = testStream.size()

    override fun close() {
        testStream.close()
        referenceStream.close()
        if (comparisonEnabled && mismatchCount == 0) {
            println("[$name] All bytes matched!")
        } else if (comparisonEnabled) {
            println("[$name] Found $mismatchCount byte mismatches")
        }
    }
}
