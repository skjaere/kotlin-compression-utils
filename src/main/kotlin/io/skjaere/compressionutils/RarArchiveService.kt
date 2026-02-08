package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

class RarArchiveService {
    private val logger = LoggerFactory.getLogger(RarArchiveService::class.java)
    private val rar5Parser = Rar5Parser()
    private val rar4Parser = Rar4Parser()

    companion object {
        // RAR 5.x signature
        private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        // RAR 4.x signature
        private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)

    }

    /**
     * Lists files from a multi-part RAR archive represented as a single concatenated stream.
     * All volume parts should be concatenated together in order (part01, part02, etc.).
     *
     * @param stream A SeekableInputStream containing all RAR volumes concatenated together
     * @param totalArchiveSize Total size of the concatenated archive (optional, enables optimizations)
     * @param volumeSizes List of individual volume sizes in order (optional, enables split position calculation)
     * @return List of RarFileEntry objects containing file metadata from all volumes
     */
    fun listFilesFromConcatenatedStream(
        stream: SeekableInputStream,
        totalArchiveSize: Long? = null,
        volumeSizes: List<Long>? = null
    ): List<RarFileEntry> {
        return listFiles(
            stream = stream,
            maxFiles = null,
            volumeIndex = 0,
            archiveSize = totalArchiveSize,
            volumeSizes = volumeSizes
        )
    }

    /**
     * Reads a RAR archive from a seekable input stream and returns metadata about contained files.
     *
     * @param stream A SeekableInputStream representing the RAR archive
     * @param maxFiles Maximum number of files to read (null = read all)
     * @param volumeIndex Volume index for multi-part archives (0-based)
     * @param archiveSize Total size of this volume (optional, enables single-file optimization)
     * @return List of RarFileEntry objects containing file metadata
     */
    fun listFiles(
        stream: SeekableInputStream,
        maxFiles: Int? = null,
        volumeIndex: Int = 0,
        archiveSize: Long? = null,
        volumeSizes: List<Long>? = null
    ): List<RarFileEntry> {
        val entries = mutableListOf<RarFileEntry>()

        // Read and verify signature
        val signature = ByteArray(8)
        val sigBytesRead = stream.read(signature, 0, 8)
        if (sigBytesRead < 7) {
            throw IOException("Invalid RAR archive: too short")
        }

        val isRar5 = signature.contentEquals(RAR5_SIGNATURE)
        val isRar4 = signature.sliceArray(0..6).contentEquals(RAR4_SIGNATURE)

        when {
            isRar5 -> {
                logger.debug("Detected RAR 5.x archive (volume $volumeIndex)")
                rar5Parser.parse(stream, entries, maxFiles, volumeIndex, archiveSize, ::readBytes)
            }

            isRar4 -> {
                logger.debug("Detected RAR 4.x archive (volume $volumeIndex)")
                rar4Parser.parse(
                    stream,
                    entries,
                    maxFiles,
                    volumeIndex,
                    archiveSize,
                    ::readBytes,
                    volumeSizes
                )
            }

            else -> {
                throw IOException("Not a valid RAR archive")
            }
        }

        return entries
    }

    private fun readBytes(stream: SeekableInputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0

        while (offset < count) {
            val read = stream.read(buffer, offset, count - offset)
            if (read == -1) return null
            offset += read
        }

        return buffer
    }

    /**
     * Convenience method to list files from a regular InputStream.
     * Wraps it in a BufferedSeekableInputStream adapter.
     */
    fun listFiles(inputStream: InputStream, maxFiles: Int? = null): List<RarFileEntry> {
        return BufferedSeekableInputStream(inputStream).use { stream ->
            listFiles(stream, maxFiles)
        }
    }

    /**
     * Convenience method to list files from a file path.
     */
    fun listFiles(filePath: String, maxFiles: Int? = null): List<RarFileEntry> {
        return FileSeekableInputStream(RandomAccessFile(File(filePath), "r")).use { stream ->
            listFiles(stream, maxFiles)
        }
    }
}
