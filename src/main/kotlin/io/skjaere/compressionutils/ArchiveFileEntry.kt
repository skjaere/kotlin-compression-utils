package io.skjaere.compressionutils

sealed interface ArchiveFileEntry

data class SevenZipFileEntry(
    val path: String,
    val size: Long,
    val packedSize: Long,
    val dataOffset: Long,  // Exact byte position in archive where file data starts
    val isDirectory: Boolean,
    val method: String? = null,  // Compression method
    val crc32: Long? = null  // CRC32 of uncompressed file data
) : ArchiveFileEntry

data class RarFileEntry(
    val path: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val headerPosition: Long,
    val dataPosition: Long,
    val isDirectory: Boolean,
    val volumeIndex: Int = 0,  // Which volume/part this file's data starts in (0-based)
    val compressionMethod: Int = -1,  // Compression method: 0=store (uncompressed), 1-5=various compression
    val splitParts: List<SplitInfo> = emptyList(),  // Information about how file is split across volumes (empty if not split)
    val crc32: Long? = null  // CRC32 of uncompressed file data
) : ArchiveFileEntry {
    val isUncompressed: Boolean
        get() = compressionMethod == 0

    val isSplit: Boolean
        get() = splitParts.isNotEmpty()
}
