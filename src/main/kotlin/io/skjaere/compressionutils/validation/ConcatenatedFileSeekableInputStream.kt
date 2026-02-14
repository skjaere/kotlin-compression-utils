package io.skjaere.compressionutils.validation

import io.skjaere.compressionutils.SeekableInputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * A SeekableInputStream that maps a single logical position space across multiple files.
 * Each file is opened via RandomAccessFile for true random access.
 */
class ConcatenatedFileSeekableInputStream(files: List<File>) : SeekableInputStream {
    private val rafs: List<RandomAccessFile> = files.map { RandomAccessFile(it, "r") }
    private val fileSizes: LongArray = rafs.map { it.length() }.toLongArray()
    private val cumulativeOffsets: LongArray
    private val totalSize: Long
    private var currentPosition: Long = 0

    init {
        cumulativeOffsets = LongArray(fileSizes.size)
        var cumulative = 0L
        for (i in fileSizes.indices) {
            cumulativeOffsets[i] = cumulative
            cumulative += fileSizes[i]
        }
        totalSize = cumulative
    }

    private fun findVolumeIndex(position: Long): Int {
        // Binary search for the volume containing `position`
        var lo = 0
        var hi = cumulativeOffsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeOffsets[mid] <= position) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return lo
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (currentPosition >= totalSize) return -1
        var totalRead = 0
        var bufOffset = offset
        var remaining = length

        while (remaining > 0 && currentPosition < totalSize) {
            val volIndex = findVolumeIndex(currentPosition)
            val localPos = currentPosition - cumulativeOffsets[volIndex]
            val availableInVolume = fileSizes[volIndex] - localPos
            val toRead = minOf(remaining.toLong(), availableInVolume).toInt()

            val raf = rafs[volIndex]
            raf.seek(localPos)
            val bytesRead = raf.read(buffer, bufOffset, toRead)
            if (bytesRead <= 0) break

            totalRead += bytesRead
            bufOffset += bytesRead
            remaining -= bytesRead
            currentPosition += bytesRead
        }

        return if (totalRead == 0) -1 else totalRead
    }

    override suspend fun read(): Int {
        if (currentPosition >= totalSize) return -1
        val volIndex = findVolumeIndex(currentPosition)
        val localPos = currentPosition - cumulativeOffsets[volIndex]
        val raf = rafs[volIndex]
        raf.seek(localPos)
        val b = raf.read()
        if (b != -1) currentPosition++
        return b
    }

    override suspend fun seek(position: Long) {
        currentPosition = position
    }

    override fun position(): Long = currentPosition

    override fun size(): Long = totalSize

    override fun close() {
        for (raf in rafs) {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }
}
