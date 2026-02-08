package io.skjaere.compressionutils

data class VolumeMetaData(
    val filename: String,
    val size: Long,
    val first16kb: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VolumeMetaData) return false
        return filename == other.filename &&
            size == other.size &&
            (first16kb contentEquals other.first16kb)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + (first16kb?.contentHashCode() ?: 0)
        return result
    }
}
