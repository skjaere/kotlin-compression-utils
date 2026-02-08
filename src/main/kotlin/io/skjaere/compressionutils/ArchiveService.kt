package io.skjaere.compressionutils

import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Unified top-level API for listing files in archive volumes.
 *
 * Automatically detects the archive type (RAR4, RAR5, 7z) from filenames or
 * byte signatures and dispatches to the appropriate parser.
 *
 * Supports PAR2-based obfuscated filename resolution: if volumes have
 * non-archive filenames and PAR2 data is provided, filenames are resolved
 * using the first-16KB MD5 hash before type detection.
 */
object ArchiveService {
    private val logger = LoggerFactory.getLogger(ArchiveService::class.java)

    private val rarArchiveService = RarArchiveService()
    private val sevenZipArchiveService = SevenZipArchiveService()

    private val KNOWN_EXTENSION_REGEX = Regex(
        """(?i)\.(part\d+\.rar|rar|r\d{2,3}|s\d{2,3}|7z|7z\.\d{3})$"""
    )

    private val RAR_EXTENSION_REGEX = Regex(
        """(?i)\.(part\d+\.rar|rar|r\d{2,3}|s\d{2,3})$"""
    )

    private val SEVENZIP_EXTENSION_REGEX = Regex(
        """(?i)\.(7z|7z\.\d{3})$"""
    )

    /**
     * Lists files from archive volumes represented as a single concatenated stream.
     *
     * Automatically detects the archive type from volume filenames or byte signatures
     * and dispatches to the appropriate parser.
     *
     * @param stream A SeekableInputStream containing all archive volumes concatenated together
     * @param volumes List of VolumeMetaData for each volume
     * @param par2Data Raw PAR2 file data for resolving obfuscated filenames (optional)
     * @return List of ArchiveFileEntry objects (either RarFileEntry or SevenZipFileEntry)
     */
    fun listFiles(
        stream: SeekableInputStream,
        volumes: List<VolumeMetaData>,
        par2Data: ByteArray? = null
    ): List<ArchiveFileEntry> {
        val resolvedVolumes = if (par2Data != null && volumes.any { !fileHasKnownExtension(it.filename) }) {
            resolveObfuscatedFilenames(volumes, par2Data)
        } else {
            volumes
        }

        val archiveType = detectArchiveType(resolvedVolumes, stream)
        logger.debug("Detected archive type: {}", archiveType)

        return when (archiveType) {
            ArchiveTypeDetector.ArchiveType.RAR4,
            ArchiveTypeDetector.ArchiveType.RAR5 -> {
                rarArchiveService.listFilesFromConcatenatedStream(
                    stream = stream,
                    totalArchiveSize = resolvedVolumes.sumOf { it.size },
                    volumeSizes = resolvedVolumes.map { it.size }
                )
            }

            ArchiveTypeDetector.ArchiveType.SEVENZIP -> {
                sevenZipArchiveService.listFiles(stream)
            }

            ArchiveTypeDetector.ArchiveType.UNKNOWN -> {
                throw java.io.IOException("Unable to detect archive type from filenames or byte signatures")
            }
        }
    }

    /**
     * Convenience method to list files from a single archive file path.
     *
     * @param filePath Path to the archive file
     * @return List of ArchiveFileEntry objects
     */
    fun listFiles(filePath: String): List<ArchiveFileEntry> {
        val file = File(filePath)
        val volume = VolumeMetaData(filename = file.name, size = file.length())
        val stream = FileSeekableInputStream(RandomAccessFile(file, "r"))
        return stream.use { listFiles(it, listOf(volume)) }
    }

    /**
     * Checks if a filename has a known archive volume extension.
     */
    fun fileHasKnownExtension(filename: String): Boolean {
        return KNOWN_EXTENSION_REGEX.containsMatchIn(filename)
    }

    private fun detectArchiveType(
        volumes: List<VolumeMetaData>,
        stream: SeekableInputStream
    ): ArchiveTypeDetector.ArchiveType {
        // Try filename-based detection first
        for (volume in volumes) {
            if (RAR_EXTENSION_REGEX.containsMatchIn(volume.filename)) {
                return ArchiveTypeDetector.ArchiveType.RAR5 // Will be refined by RarArchiveService
            }
            if (SEVENZIP_EXTENSION_REGEX.containsMatchIn(volume.filename)) {
                return ArchiveTypeDetector.ArchiveType.SEVENZIP
            }
        }

        // Fall back to byte signature detection using first16kb or stream
        for (volume in volumes) {
            if (volume.first16kb != null) {
                val result = ArchiveTypeDetector.detect(volume.first16kb)
                if (result.type != ArchiveTypeDetector.ArchiveType.UNKNOWN) {
                    return result.type
                }
            }
        }

        // Last resort: detect from stream bytes
        val result = ArchiveTypeDetector.detect(stream)
        return result.type
    }

    private fun resolveObfuscatedFilenames(
        volumes: List<VolumeMetaData>,
        par2Data: ByteArray
    ): List<VolumeMetaData> {
        val par2Info = Par2Parser.parse(par2Data)
        val hash16kToFilename = par2Info.files.associate { it.hash16kHex() to it.filename }

        return volumes.map { volume ->
            if (!fileHasKnownExtension(volume.filename) && volume.first16kb != null) {
                val md5Hex = md5Hex(volume.first16kb)
                val resolvedName = hash16kToFilename[md5Hex]
                if (resolvedName != null) {
                    volume.copy(filename = resolvedName)
                } else {
                    volume
                }
            } else {
                volume
            }
        }
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
