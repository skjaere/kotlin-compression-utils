package io.skjaere.compressionutils.generation

object ArchiveGenerator {

    fun generate(
        data: ByteArray,
        numberOfVolumes: Int,
        containerType: ContainerType,
        filename: String = "data.bin"
    ): List<ArchiveVolume> {
        require(numberOfVolumes >= 1) { "numberOfVolumes must be >= 1" }
        require(data.isNotEmpty()) { "data must not be empty" }
        require(filename.isNotEmpty()) { "filename must not be empty" }

        return when (containerType) {
            ContainerType.RAR4 -> Rar4Generator.generate(data, numberOfVolumes, filename)
            ContainerType.RAR5 -> Rar5Generator.generate(data, numberOfVolumes, filename)
            ContainerType.SEVENZIP -> SevenZipGenerator.generate(data, numberOfVolumes, filename)
        }
    }

    fun generate(
        data: ByteArray,
        volumeSize: Long,
        containerType: ContainerType,
        filename: String = "data.bin"
    ): List<ArchiveVolume> {
        require(volumeSize > 0) { "volumeSize must be > 0" }
        require(data.isNotEmpty()) { "data must not be empty" }
        require(filename.isNotEmpty()) { "filename must not be empty" }

        return when (containerType) {
            ContainerType.RAR4 -> Rar4Generator.generateWithVolumeSize(data, volumeSize, filename)
            ContainerType.RAR5 -> Rar5Generator.generateWithVolumeSize(data, volumeSize, filename)
            ContainerType.SEVENZIP -> SevenZipGenerator.generateWithVolumeSize(data, volumeSize, filename)
        }
    }
}
