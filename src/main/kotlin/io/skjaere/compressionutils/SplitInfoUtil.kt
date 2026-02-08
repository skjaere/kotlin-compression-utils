package io.skjaere.compressionutils

/**
 * Translates a global offset within the concatenated stream into a new list of SplitInfos
 * representing the stream from that offset onwards.
 *
 * This function is crucial for seeking in a virtual stream composed of multiple, non-contiguous
 * byte ranges (the SplitInfos). It finds which split contains the offset, creates a new
 * partial split starting from that offset, and includes all subsequent splits.
 *
 * @param offset The 0-indexed byte offset within the virtual concatenated stream.
 * @return A new List<SplitInfo> representing the remainder of the stream. Returns an empty
 *         list if the offset is out of bounds (greater than the total concatenated size).
 */
fun List<SplitInfo>.fromOffset(offset: Long): List<SplitInfo> {
    if (offset < 0) {
        // Negative offsets are invalid.
        return emptyList()
    }

    if (offset == 0L) {
        return this // Optimization for the common case of reading from the start.
    }

    val newSplitInfos = mutableListOf<SplitInfo>()
    var cumulativeSize = 0L
    var offsetHandled = false

    for (split in this) {
        val splitEnd = cumulativeSize + split.dataSize

        if (!offsetHandled) {
            if (offset < splitEnd) {
                // The offset falls within the current split. This is our starting point.
                offsetHandled = true
                val localOffset = offset - cumulativeSize

                // Create the first, partial SplitInfo for our new list.
                val newStartPosition = split.dataStartPosition + localOffset
                val newSize = split.dataSize - localOffset

                if (newSize > 0) {
                    newSplitInfos.add(
                        SplitInfo(
                            volumeIndex = split.volumeIndex, // Preserve the volume index
                            dataStartPosition = newStartPosition,
                            dataSize = newSize
                        )
                    )
                }
            }
        } else {
            // We are past the offset, so just add the remaining splits as they are.
            newSplitInfos.add(split)
        }

        cumulativeSize += split.dataSize
    }

    // If after checking all splits the offset was never handled, it means the offset
    // is out of bounds. The list will be empty, which is the correct result.
    return newSplitInfos
}
