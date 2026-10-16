package io.skjaere.compressionutils.generation

import io.skjaere.compressionutils.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ArchiveGeneratorTest {

    private val testData = ByteArray(1024) { (it % 256).toByte() }
    private val testFilename = "data.bin"

    // --- 7zip single volume ---

    @Test
    fun `7zip single volume round-trip`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 1, ContainerType.SEVENZIP, testFilename)
        assertEquals(1, volumes.size)
        assertEquals("archive.7z", volumes[0].filename)

        val stream = ByteArraySeekableInputStream(volumes[0].data)
        val entries = SevenZipParser().parse(stream)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(testFilename, entry.path)
        assertEquals(testData.size.toLong(), entry.size)
        assertEquals("Copy", entry.method)

        // Verify data content
        val extractedData = volumes[0].data.copyOfRange(entry.dataOffset.toInt(), entry.dataOffset.toInt() + entry.size.toInt())
        assertContentEquals(testData, extractedData)
    }

    @Test
    fun `7zip detection works`() {
        val volumes = ArchiveGenerator.generate(testData, 1, ContainerType.SEVENZIP, testFilename)
        val result = ArchiveTypeDetector.detect(volumes[0].data)
        assertEquals(ArchiveTypeDetector.ArchiveType.SEVENZIP, result.type)
    }

    // --- 7zip multi-volume ---

    @Test
    fun `7zip multi-volume round-trip`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 3, ContainerType.SEVENZIP, testFilename)
        assertEquals(3, volumes.size)
        assertTrue(volumes[0].filename.endsWith(".001"))
        assertTrue(volumes[1].filename.endsWith(".002"))
        assertTrue(volumes[2].filename.endsWith(".003"))

        // Concatenate volumes and parse
        val concatenated = volumes.flatMap { it.data.toList() }.toByteArray()
        val stream = ByteArraySeekableInputStream(concatenated)
        val entries = SevenZipParser().parse(stream)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(testFilename, entry.path)
        assertEquals(testData.size.toLong(), entry.size)

        // Verify data
        val extractedData = concatenated.copyOfRange(entry.dataOffset.toInt(), entry.dataOffset.toInt() + entry.size.toInt())
        assertContentEquals(testData, extractedData)
    }

    // --- RAR5 single volume ---

    @Test
    fun `rar5 single volume round-trip`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 1, ContainerType.RAR5, testFilename)
        assertEquals(1, volumes.size)
        assertEquals("archive.rar", volumes[0].filename)

        val stream = ByteArraySeekableInputStream(volumes[0].data)
        val service = RarArchiveService()
        val entries = service.listFiles(stream)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(testFilename, entry.path)
        assertEquals(testData.size.toLong(), entry.uncompressedSize)
        assertEquals(0, entry.compressionMethod)

        // Verify data content
        val extractedData = volumes[0].data.copyOfRange(entry.dataPosition.toInt(), entry.dataPosition.toInt() + entry.compressedSize.toInt())
        assertContentEquals(testData, extractedData)
    }

    @Test
    fun `rar5 detection works`() {
        val volumes = ArchiveGenerator.generate(testData, 1, ContainerType.RAR5, testFilename)
        val result = ArchiveTypeDetector.detect(volumes[0].data)
        assertEquals(ArchiveTypeDetector.ArchiveType.RAR5, result.type)
    }

    // --- RAR5 multi-volume ---

    @Test
    fun `rar5 multi-volume round-trip`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 3, ContainerType.RAR5, testFilename)
        assertEquals(3, volumes.size)
        assertEquals("archive.part1.rar", volumes[0].filename)
        assertEquals("archive.part2.rar", volumes[1].filename)
        assertEquals("archive.part3.rar", volumes[2].filename)

        // Concatenate and parse
        val concatenated = volumes.flatMap { it.data.toList() }.toByteArray()
        val stream = ByteArraySeekableInputStream(concatenated)
        val service = RarArchiveService()
        val entries = service.listFilesFromConcatenatedStream(stream)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(testFilename, entry.path)
        assertEquals(testData.size.toLong(), entry.uncompressedSize)
        assertTrue(entry.isSplit)
        assertEquals(3, entry.splitParts.size)

        // Verify data by reading from split parts
        val fullData = ByteArray(testData.size)
        var destOffset = 0
        for (part in entry.splitParts) {
            val partData = concatenated.copyOfRange(
                part.dataStartPosition.toInt(),
                part.dataStartPosition.toInt() + part.dataSize.toInt()
            )
            System.arraycopy(partData, 0, fullData, destOffset, partData.size)
            destOffset += partData.size
        }
        assertContentEquals(testData, fullData)
    }

    /**
     * As RAR writes them, each volume of a RAR5 set gives its place in its main archive header: the
     * "volume" archive flag, and the volume number on all but the first (1 for the second, ...). A
     * single-volume archive's has neither. Each end-of-archive header says whether a volume follows.
     */
    @Test
    fun `rar5 volumes give their place in the set in their headers`() {
        val sets = listOf(
            ArchiveGenerator.generate(testData, 400L, ContainerType.RAR5, testFilename),
            ArchiveGenerator.generate(testData, 3, ContainerType.RAR5, testFilename),
            ArchiveGenerator.generate(listOf(ArchiveFile("a.bin", testData), ArchiveFile("b.bin", testData)), 400L, ContainerType.RAR5),
        )

        sets.forEach { volumes ->
            assertTrue(volumes.size > 1, "Expected multiple volumes")
            val expected = volumes.indices.map { v -> if (v == 0) listOf(VOLUME) else listOf(VOLUME or VOLUME_NUMBER, v.toLong()) }
            assertEquals(expected, volumes.map { rar5Body(it.data, HEAD_MAIN) })
            assertEquals(volumes.indices.map { v -> listOf(if (v < volumes.lastIndex) MORE_VOLUMES else 0L) }, volumes.map { rar5Body(it.data, HEAD_ENDARC) })
        }
        val single = ArchiveGenerator.generate(testData, 1, ContainerType.RAR5, testFilename).single()
        assertEquals(listOf(0L), rar5Body(single.data, HEAD_MAIN))
        assertEquals(listOf(0L), rar5Body(single.data, HEAD_ENDARC))
    }

    /** The vints of the body of the first header of [type] in the RAR5 volume [data]. */
    private fun rar5Body(data: ByteArray, type: Long): List<Long> {
        var position = 8
        fun vint(): Long {
            var value = 0L
            var shift = 0
            do {
                val byte = data[position++].toInt() and 0xFF
                value = value or ((byte and 0x7F).toLong() shl shift)
                shift += 7
            } while (byte and 0x80 != 0)
            return value
        }
        while (true) {
            position += 4 // CRC32
            val size = vint()
            val end = position + size.toInt()
            val headerType = vint()
            val flags = vint()
            if (flags and 0x01L != 0L) vint()
            val dataSize = if (flags and 0x02L != 0L) vint() else 0L
            if (headerType == type) return generateSequence { if (position < end) vint() else null }.toList()
            position = end + dataSize.toInt()
        }
    }

    // --- RAR4 single volume ---

    @Test
    fun `rar4 single volume round-trip`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 1, ContainerType.RAR4, testFilename)
        assertEquals(1, volumes.size)
        assertEquals("archive.rar", volumes[0].filename)

        val stream = ByteArraySeekableInputStream(volumes[0].data)
        val service = RarArchiveService()
        val entries = service.listFiles(stream)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(testFilename, entry.path)
        assertEquals(testData.size.toLong(), entry.uncompressedSize)
        assertEquals(0, entry.compressionMethod)

        // Verify data content
        val extractedData = volumes[0].data.copyOfRange(entry.dataPosition.toInt(), entry.dataPosition.toInt() + entry.compressedSize.toInt())
        assertContentEquals(testData, extractedData)
    }

    @Test
    fun `rar4 detection works`() {
        val volumes = ArchiveGenerator.generate(testData, 1, ContainerType.RAR4, testFilename)
        val result = ArchiveTypeDetector.detect(volumes[0].data)
        assertEquals(ArchiveTypeDetector.ArchiveType.RAR4, result.type)
    }

    // --- RAR4 multi-volume ---

    @Test
    fun `rar4 multi-volume round-trip`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 3, ContainerType.RAR4, testFilename)
        assertEquals(3, volumes.size)
        assertEquals("archive.rar", volumes[0].filename)
        assertEquals("archive.r00", volumes[1].filename)
        assertEquals("archive.r01", volumes[2].filename)

        // Concatenate and parse
        val concatenated = volumes.flatMap { it.data.toList() }.toByteArray()
        val volumeSizes = volumes.map { it.data.size.toLong() }
        val stream = ByteArraySeekableInputStream(concatenated)
        val service = RarArchiveService()
        val entries = service.listFilesFromConcatenatedStream(
            stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(testFilename, entry.path)
        assertEquals(testData.size.toLong(), entry.uncompressedSize)
        assertTrue(entry.isSplit)

        // Verify data by reading from split parts
        val fullData = ByteArray(testData.size)
        var destOffset = 0
        for (part in entry.splitParts) {
            val partData = concatenated.copyOfRange(
                part.dataStartPosition.toInt(),
                part.dataStartPosition.toInt() + part.dataSize.toInt()
            )
            System.arraycopy(partData, 0, fullData, destOffset, partData.size)
            destOffset += partData.size
        }
        assertContentEquals(testData, fullData)
    }

    // --- Volume-size-based generation ---

    // 7zip volume-size

    @Test
    fun `7zip volume-size single volume when size exceeds archive`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 100_000L, ContainerType.SEVENZIP, testFilename)
        assertEquals(1, volumes.size)
        assertEquals("archive.7z", volumes[0].filename)

        val stream = ByteArraySeekableInputStream(volumes[0].data)
        val entries = SevenZipParser().parse(stream)
        assertEquals(1, entries.size)
        assertEquals(testFilename, entries[0].path)
        val extractedData = volumes[0].data.copyOfRange(entries[0].dataOffset.toInt(), entries[0].dataOffset.toInt() + entries[0].size.toInt())
        assertContentEquals(testData, extractedData)
    }

    @Test
    fun `7zip volume-size multi-volume round-trip`() = runBlocking {
        val volumeSize = 400L
        val volumes = ArchiveGenerator.generate(testData, volumeSize, ContainerType.SEVENZIP, testFilename)
        assertTrue(volumes.size > 1, "Expected multiple volumes")

        // All volumes except last should be exactly volumeSize
        for (i in 0 until volumes.size - 1) {
            assertEquals(volumeSize.toInt(), volumes[i].data.size, "Volume $i should be exactly $volumeSize bytes")
        }
        assertTrue(volumes.last().data.size <= volumeSize, "Last volume should be <= $volumeSize bytes")

        // Concatenate and parse
        val concatenated = volumes.flatMap { it.data.toList() }.toByteArray()
        val stream = ByteArraySeekableInputStream(concatenated)
        val entries = SevenZipParser().parse(stream)
        assertEquals(1, entries.size)
        assertEquals(testFilename, entries[0].path)
        val extractedData = concatenated.copyOfRange(entries[0].dataOffset.toInt(), entries[0].dataOffset.toInt() + entries[0].size.toInt())
        assertContentEquals(testData, extractedData)
    }

    // RAR5 volume-size

    @Test
    fun `rar5 volume-size single volume when size exceeds archive`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 100_000L, ContainerType.RAR5, testFilename)
        assertEquals(1, volumes.size)
        assertEquals("archive.rar", volumes[0].filename)

        val stream = ByteArraySeekableInputStream(volumes[0].data)
        val entries = RarArchiveService().listFiles(stream)
        assertEquals(1, entries.size)
        assertEquals(testFilename, entries[0].path)
        val extractedData = volumes[0].data.copyOfRange(entries[0].dataPosition.toInt(), entries[0].dataPosition.toInt() + entries[0].compressedSize.toInt())
        assertContentEquals(testData, extractedData)
    }

    @Test
    fun `rar5 volume-size multi-volume round-trip`() = runBlocking {
        val volumeSize = 400L
        val volumes = ArchiveGenerator.generate(testData, volumeSize, ContainerType.RAR5, testFilename)
        assertTrue(volumes.size > 1, "Expected multiple volumes")

        // All volumes except last should be exactly volumeSize
        for (i in 0 until volumes.size - 1) {
            assertEquals(volumeSize.toInt(), volumes[i].data.size, "Volume $i should be exactly $volumeSize bytes")
        }
        assertTrue(volumes.last().data.size <= volumeSize, "Last volume should be <= $volumeSize bytes")

        // Concatenate and parse
        val concatenated = volumes.flatMap { it.data.toList() }.toByteArray()
        val stream = ByteArraySeekableInputStream(concatenated)
        val entries = RarArchiveService().listFilesFromConcatenatedStream(stream)
        assertEquals(1, entries.size)
        assertEquals(testFilename, entries[0].path)
        assertTrue(entries[0].isSplit)

        // Verify data integrity
        val fullData = ByteArray(testData.size)
        var destOffset = 0
        for (part in entries[0].splitParts) {
            val partData = concatenated.copyOfRange(
                part.dataStartPosition.toInt(),
                part.dataStartPosition.toInt() + part.dataSize.toInt()
            )
            System.arraycopy(partData, 0, fullData, destOffset, partData.size)
            destOffset += partData.size
        }
        assertContentEquals(testData, fullData)
    }

    // RAR4 volume-size

    @Test
    fun `rar4 volume-size single volume when size exceeds archive`() = runBlocking {
        val volumes = ArchiveGenerator.generate(testData, 100_000L, ContainerType.RAR4, testFilename)
        assertEquals(1, volumes.size)
        assertEquals("archive.rar", volumes[0].filename)

        val stream = ByteArraySeekableInputStream(volumes[0].data)
        val entries = RarArchiveService().listFiles(stream)
        assertEquals(1, entries.size)
        assertEquals(testFilename, entries[0].path)
        val extractedData = volumes[0].data.copyOfRange(entries[0].dataPosition.toInt(), entries[0].dataPosition.toInt() + entries[0].compressedSize.toInt())
        assertContentEquals(testData, extractedData)
    }

    @Test
    fun `rar4 volume-size multi-volume round-trip`() = runBlocking {
        val volumeSize = 400L
        val volumes = ArchiveGenerator.generate(testData, volumeSize, ContainerType.RAR4, testFilename)
        assertTrue(volumes.size > 1, "Expected multiple volumes")

        // All volumes except last should be exactly volumeSize
        for (i in 0 until volumes.size - 1) {
            assertEquals(volumeSize.toInt(), volumes[i].data.size, "Volume $i should be exactly $volumeSize bytes")
        }
        assertTrue(volumes.last().data.size <= volumeSize, "Last volume should be <= $volumeSize bytes")

        // Concatenate and parse
        val concatenated = volumes.flatMap { it.data.toList() }.toByteArray()
        val volumeSizes = volumes.map { it.data.size.toLong() }
        val stream = ByteArraySeekableInputStream(concatenated)
        val entries = RarArchiveService().listFilesFromConcatenatedStream(
            stream,
            totalArchiveSize = concatenated.size.toLong(),
            volumeSizes = volumeSizes
        )
        assertEquals(1, entries.size)
        assertEquals(testFilename, entries[0].path)
        assertTrue(entries[0].isSplit)

        // Verify data integrity
        val fullData = ByteArray(testData.size)
        var destOffset = 0
        for (part in entries[0].splitParts) {
            val partData = concatenated.copyOfRange(
                part.dataStartPosition.toInt(),
                part.dataStartPosition.toInt() + part.dataSize.toInt()
            )
            System.arraycopy(partData, 0, fullData, destOffset, partData.size)
            destOffset += partData.size
        }
        assertContentEquals(testData, fullData)
    }

    // --- Edge cases ---

    @Test
    fun `small data single volume all formats`() {
        val smallData = byteArrayOf(0x01, 0x02, 0x03)
        for (type in ContainerType.entries) {
            val volumes = ArchiveGenerator.generate(smallData, 1, type, "tiny.bin")
            assertEquals(1, volumes.size, "Failed for $type")
        }
    }

    @Test
    fun `custom filename preserved`() = runBlocking {
        val customName = "my-special-file.dat"
        for (type in ContainerType.entries) {
            val volumes = ArchiveGenerator.generate(testData, 1, type, customName)
            val stream = ByteArraySeekableInputStream(volumes[0].data)

            when (type) {
                ContainerType.SEVENZIP -> {
                    val entries = SevenZipParser().parse(stream)
                    assertEquals(customName, entries[0].path)
                }
                ContainerType.RAR4, ContainerType.RAR5 -> {
                    val entries = RarArchiveService().listFiles(stream)
                    assertEquals(customName, entries[0].path)
                }
            }
        }
    }

    private companion object {
        const val HEAD_MAIN = 1L
        const val HEAD_ENDARC = 5L
        const val VOLUME = 0x01L
        const val VOLUME_NUMBER = 0x02L
        const val MORE_VOLUMES = 0x01L
    }
}
