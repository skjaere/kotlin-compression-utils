package io.skjaere.compressionutils

sealed interface ArchiveFileEntry {
    val path: String
    val size: Long
    val isDirectory: Boolean
}

data class SevenZipFileEntry(
    override val path: String,
    override val size: Long,
    val packedSize: Long,
    val dataOffset: Long,  // Exact byte position in archive where file data starts
    override val isDirectory: Boolean,
    val method: String? = null,  // Compression method
    val crc32: Long? = null  // CRC32 of uncompressed file data
) : ArchiveFileEntry

data class RarFileEntry(
    override val path: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val headerPosition: Long,
    val dataPosition: Long,
    override val isDirectory: Boolean,
    val volumeIndex: Int = 0,  // Which volume/part this file's data starts in (0-based)
    val compressionMethod: Int = -1,  // Compression method: 0=store (uncompressed), 1-5=various compression
    val splitParts: List<SplitInfo> = emptyList(),  // Information about how file is split across volumes (empty if not split)
    val crc32: Long? = null  // CRC32 of uncompressed file data
) : ArchiveFileEntry {
    override val size: Long get() = uncompressedSize

    val isUncompressed: Boolean
        get() = compressionMethod == 0

    val isSplit: Boolean
        get() = splitParts.isNotEmpty()
}

/**
 * A file entry from a nested archive whose byte offsets have been translated
 * to absolute positions in the outer stream. The [splitParts] contain pre-computed
 * [SplitInfo] with outer-stream positions, ready for direct use by the streaming pipeline.
 */
data class TranslatedFileEntry(
    override val path: String,
    override val size: Long,
    override val isDirectory: Boolean,
    val splitParts: List<SplitInfo>,
    val crc32: Long? = null
) : ArchiveFileEntry
