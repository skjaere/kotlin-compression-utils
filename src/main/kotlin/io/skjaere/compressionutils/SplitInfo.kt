package io.skjaere.compressionutils

/**
 * Information about a portion of a split file in a specific volume.
 * For streaming, you need to:
 * 1. Seek to dataStartPosition in the concatenated stream
 * 2. Read dataSize bytes
 * 3. Move to next SplitInfo and repeat
 */
data class SplitInfo(
    val volumeIndex: Int,           // Which volume this portion is in (0-based)
    val dataStartPosition: Long,    // Absolute byte position in concatenated stream where this portion's data starts
    val dataSize: Long              // How many bytes of file data are in this portion
)
