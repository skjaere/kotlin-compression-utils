package io.skjaere.compressionutils

object ArchiveTypeDetector {

    // RAR 4.x signature (7 bytes)
    private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private const val RAR4_ARCHIVE_FLAG_FIRSTVOLUME = 0x0100

    // RAR 5.x signature (8 bytes)
    private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    // 7z signature (6 bytes)
    private val SEVENZIP_SIGNATURE = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    // Minimum bytes needed to detect any supported archive type and inspect headers
    const val MIN_BYTES_NEEDED = 32

    /**
     * Detected archive type.
     */
    enum class ArchiveType {
        RAR4,
        RAR5,
        SEVENZIP,
        UNKNOWN
    }

    data class DetectionResult(
        val type: ArchiveType,
        /**
         * Whether the archive appears to be the first volume in a multi-volume set.
         * For RAR archives, this is determined by inspecting the first file's header flags.
         * For other formats, this is currently assumed to be true.
         * A value of 'false' indicates it is definitely NOT the first volume.
         * A value of 'true' means it is either the first volume or it could not be determined.
         */
        val isFirstVolume: Boolean
    )

    /**
     * Detects archive type from the first bytes of a stream.
     *
     * @param bytes The first bytes of the archive (at least 32 bytes recommended for volume detection)
     * @return A DetectionResult containing the detected archive type and whether it is the first volume
     */
    fun detect(bytes: ByteArray): DetectionResult {
        if (bytes.size < 8) { // Minimum signature size for RAR5
            return DetectionResult(ArchiveType.UNKNOWN, true)
        }

        // Check RAR5 first (longer signature)
        if (bytes.size >= RAR5_SIGNATURE.size &&
            bytes.sliceArray(0 until RAR5_SIGNATURE.size).contentEquals(RAR5_SIGNATURE)
        ) {
            // RAR5 volume detection is complex due to variable-length headers.
            // A robust check requires parsing vints, which is beyond simple byte matching.
            // For now, we assume true. A more sophisticated check can be added later.
            return DetectionResult(ArchiveType.RAR5, true)
        }

        // Check RAR4
        if (bytes.size >= RAR4_SIGNATURE.size &&
            bytes.sliceArray(0 until RAR4_SIGNATURE.size).contentEquals(RAR4_SIGNATURE)
        ) {
            // Check if this is the first volume by inspecting the first block header
            val headerStart = RAR4_SIGNATURE.size
            if (bytes.size >= headerStart + 7) {
                val blockHeader = bytes.sliceArray(headerStart until headerStart + 7)
                val blockType = blockHeader[2].toInt() and 0xFF
                val blockFlags = ((blockHeader[4].toInt() and 0xFF) shl 8) or (blockHeader[3].toInt() and 0xFF)

                when (blockType) {
                    0x73 -> { // Archive Header
                        val isFirstVolume = (blockFlags and RAR4_ARCHIVE_FLAG_FIRSTVOLUME) != 0
                        return DetectionResult(ArchiveType.RAR4, isFirstVolume)
                    }
                    0x74 -> { // File Header
                        val isSplitBefore = (blockFlags and 0x01) != 0
                        if (isSplitBefore) {
                            return DetectionResult(ArchiveType.RAR4, false)
                        }
                    }
                }
            }
            return DetectionResult(ArchiveType.RAR4, true)
        }

        // Check 7zip
        if (bytes.sliceArray(0 until SEVENZIP_SIGNATURE.size).contentEquals(SEVENZIP_SIGNATURE)) {
            return DetectionResult(ArchiveType.SEVENZIP, true)
        }

        return DetectionResult(ArchiveType.UNKNOWN, true)
    }

    /**
     * Detects archive type from a SeekableInputStream.
     * Reads the first bytes and resets the stream position to 0.
     *
     * @param stream The stream to read from (position will be reset to 0 after detection)
     * @return A DetectionResult containing the detected archive type and whether it is the first volume
     */
    suspend fun detect(stream: SeekableInputStream): DetectionResult {
        val originalPosition = stream.position()
        try {
            stream.seek(0)
            val buffer = ByteArray(MIN_BYTES_NEEDED)
            val bytesRead = stream.read(buffer, 0, MIN_BYTES_NEEDED)
            if (bytesRead < SEVENZIP_SIGNATURE.size) {
                return DetectionResult(ArchiveType.UNKNOWN, true)
            }
            return detect(buffer.sliceArray(0 until bytesRead))
        } finally {
            stream.seek(originalPosition)
        }
    }

    /**
     * Checks if the bytes represent a RAR archive (either RAR4 or RAR5).
     */
    fun isRar(bytes: ByteArray): Boolean {
        val type = detect(bytes).type
        return type == ArchiveType.RAR4 || type == ArchiveType.RAR5
    }

    /**
     * Checks if the bytes represent a 7zip archive.
     */
    fun isSevenZip(bytes: ByteArray): Boolean {
        return detect(bytes).type == ArchiveType.SEVENZIP
    }
}
