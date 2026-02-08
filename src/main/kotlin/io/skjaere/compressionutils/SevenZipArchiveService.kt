package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.io.RandomAccessFile

/**
 * Service for parsing 7z archives using a native Kotlin parser.
 *
 * IMPORTANT: This service only supports uncompressed (store mode / Copy codec) archives.
 * Compressed archives will be rejected with an IOException.
 *
 * Calculates exact byte offsets for file data within the archive.
 */
class SevenZipArchiveService {
    private val logger = LoggerFactory.getLogger(SevenZipArchiveService::class.java)
    private val parser = SevenZipParser()

    /**
     * Lists files in a 7z archive from a SeekableInputStream.
     *
     * @param stream A SeekableInputStream representing the 7z archive
     * @return List of SevenZipFileEntry objects containing file metadata with exact byte offsets
     */
    fun listFiles(stream: SeekableInputStream): List<SevenZipFileEntry> {
        return parser.parse(stream)
    }

    /**
     * Lists files in a 7z archive from a file path.
     *
     * @param filePath Path to the 7z archive file
     * @return List of SevenZipFileEntry objects containing file metadata with exact byte offsets
     */
    fun listFiles(filePath: String): List<SevenZipFileEntry> {
        FileSeekableInputStream(RandomAccessFile(filePath, "r")).use { stream ->
            return parser.parse(stream)
        }
    }
}
