package io.skjaere.compressionutils

/**
 * Metadata extracted from a RAR4 archive file entry that can be stored
 * and later used to calculate split parts without re-reading the stream.
 */
data class Rar4FileMetadata(
    /** Filename including path within the archive */
    val filename: String,
    /** Total uncompressed file size in bytes */
    val uncompressedSize: Long,
    /** Absolute byte position where file data starts in the first volume */
    val firstPartDataStart: Long,
    /** Size of file data in the first volume */
    val firstPartDataSize: Long,
    /** Pre-calculated header size for continuation volumes */
    val continuationHeaderSize: Long,
    /** Whether this file spans multiple volumes */
    val isSplit: Boolean,
    /** Compression method (0 = store/uncompressed) */
    val compressionMethod: Int
) {
    /** Whether the file is stored uncompressed */
    val isUncompressed: Boolean get() = compressionMethod == 0

    /** Whether this is a large file (>4GB) */
    val isLargeFile: Boolean get() = uncompressedSize > 0xFFFFFFFFL

    /**
     * Calculates split parts for this file using the provided volume sizes.
     * Only valid for uncompressed (store) files.
     *
     * @param volumeSizes List of all volume sizes in order
     * @return List of SplitInfo, or empty list if file is compressed
     */
    fun calculateSplitParts(volumeSizes: List<Long>): List<SplitInfo> {
        if (!isUncompressed) return emptyList()
        return Rar4Parser.calculateSplitParts(
            uncompressedSize = uncompressedSize,
            firstPartDataStart = firstPartDataStart,
            firstPartDataSize = firstPartDataSize,
            continuationHeaderSize = continuationHeaderSize,
            volumeSizes = volumeSizes
        )
    }
}
