package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Wrapper that tracks how many bytes are actually read vs skipped.
 */
class InstrumentedInputStream(private val delegate: InputStream) : InputStream() {
    private val logger = LoggerFactory.getLogger(InstrumentedInputStream::class.java)

    var bytesRead = 0L
        private set
    var bytesSkipped = 0L
        private set

    override fun read(): Int {
        val result = delegate.read()
        if (result != -1) {
            bytesRead++
        }
        return result
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = delegate.read(b, off, len)
        if (result > 0) {
            bytesRead += result
        }
        return result
    }

    override fun skip(n: Long): Long {
        val result = delegate.skip(n)
        if (result > 0) {
            bytesSkipped += result
        }
        return result
    }

    override fun close() {
        delegate.close()
    }

    fun logStats(fileSize: Long) {
        val totalProcessed = bytesRead + bytesSkipped
        logger.info(
            "InputStream Statistics: " +
                "bytesRead=$bytesRead (${String.format("%.2f", bytesRead * 100.0 / fileSize)}%), " +
                "bytesSkipped=$bytesSkipped (${String.format("%.2f", bytesSkipped * 100.0 / fileSize)}%), " +
                "totalProcessed=$totalProcessed (${String.format("%.2f", totalProcessed * 100.0 / fileSize)}%), " +
                "fileSize=$fileSize"
        )
    }
}
