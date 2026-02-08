package io.skjaere.compressionutils.generation

enum class ContainerType { RAR4, RAR5, SEVENZIP }

data class ArchiveVolume(val filename: String, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchiveVolume) return false
        return filename == other.filename && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return 31 * filename.hashCode() + data.contentHashCode()
    }
}
