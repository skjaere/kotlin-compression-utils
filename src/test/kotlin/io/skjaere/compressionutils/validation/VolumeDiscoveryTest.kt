package io.skjaere.compressionutils.validation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class VolumeDiscoveryTest {

    @Test
    fun `modern RAR discovers all parts sorted by number`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val files = listOf(
            "archive.part1.rar",
            "archive.part2.rar",
            "archive.part3.rar"
        ).map { dir.resolve(it).also { f -> f.writeBytes(ByteArray(1)) } }

        val result = VolumeDiscovery.discoverVolumes(files[0])
        assertEquals(3, result.size)
        assertEquals("archive.part1.rar", result[0].name)
        assertEquals("archive.part2.rar", result[1].name)
        assertEquals("archive.part3.rar", result[2].name)
    }

    @Test
    fun `modern RAR handles double-digit part numbers`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val names = (1..12).map { "data.part%d.rar".format(it) }
        names.forEach { dir.resolve(it).writeBytes(ByteArray(1)) }

        val result = VolumeDiscovery.discoverVolumes(dir.resolve(names[0]))
        assertEquals(12, result.size)
        assertEquals("data.part1.rar", result[0].name)
        assertEquals("data.part10.rar", result[9].name)
        assertEquals("data.part12.rar", result[11].name)
    }

    @Test
    fun `modern RAR ignores unrelated files in same directory`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("archive.part1.rar").writeBytes(ByteArray(1))
        dir.resolve("archive.part2.rar").writeBytes(ByteArray(1))
        dir.resolve("other.part1.rar").writeBytes(ByteArray(1))
        dir.resolve("readme.txt").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("archive.part1.rar"))
        assertEquals(2, result.size)
        assertEquals("archive.part1.rar", result[0].name)
        assertEquals("archive.part2.rar", result[1].name)
    }

    @Test
    fun `old-style RAR discovers rar plus rNN extensions`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("archive.rar").writeBytes(ByteArray(1))
        dir.resolve("archive.r00").writeBytes(ByteArray(1))
        dir.resolve("archive.r01").writeBytes(ByteArray(1))
        dir.resolve("archive.r02").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("archive.rar"))
        assertEquals(4, result.size)
        assertEquals("archive.rar", result[0].name)
        assertEquals("archive.r00", result[1].name)
        assertEquals("archive.r01", result[2].name)
        assertEquals("archive.r02", result[3].name)
    }

    @Test
    fun `old-style RAR with three-digit extensions`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("big.rar").writeBytes(ByteArray(1))
        dir.resolve("big.r000").writeBytes(ByteArray(1))
        dir.resolve("big.r001").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("big.rar"))
        assertEquals(3, result.size)
        assertEquals("big.rar", result[0].name)
        assertEquals("big.r000", result[1].name)
        assertEquals("big.r001", result[2].name)
    }

    @Test
    fun `old-style RAR without continuation volumes returns single file`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("single.rar").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("single.rar"))
        assertEquals(1, result.size)
        assertEquals("single.rar", result[0].name)
    }

    @Test
    fun `split 7z discovers all parts sorted by number`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("archive.7z.001").writeBytes(ByteArray(1))
        dir.resolve("archive.7z.002").writeBytes(ByteArray(1))
        dir.resolve("archive.7z.003").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("archive.7z.001"))
        assertEquals(3, result.size)
        assertEquals("archive.7z.001", result[0].name)
        assertEquals("archive.7z.002", result[1].name)
        assertEquals("archive.7z.003", result[2].name)
    }

    @Test
    fun `split 7z ignores unrelated files`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("archive.7z.001").writeBytes(ByteArray(1))
        dir.resolve("archive.7z.002").writeBytes(ByteArray(1))
        dir.resolve("other.7z.001").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("archive.7z.001"))
        assertEquals(2, result.size)
    }

    @Test
    fun `single 7z file returns just itself`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("archive.7z").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("archive.7z"))
        assertEquals(1, result.size)
        assertEquals("archive.7z", result[0].name)
    }

    @Test
    fun `old-style RAR discovers s extensions after r extensions`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("archive.rar").writeBytes(ByteArray(1))
        // r00..r99 then s00..s02
        for (i in 0..99) dir.resolve("archive.r%02d".format(i)).writeBytes(ByteArray(1))
        for (i in 0..2) dir.resolve("archive.s%02d".format(i)).writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("archive.rar"))
        assertEquals(104, result.size)
        assertEquals("archive.rar", result[0].name)
        assertEquals("archive.r00", result[1].name)
        assertEquals("archive.r99", result[100].name)
        assertEquals("archive.s00", result[101].name)
        assertEquals("archive.s01", result[102].name)
        assertEquals("archive.s02", result[103].name)
    }

    @Test
    fun `old-style RAR handles s and t extensions in order`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("data.rar").writeBytes(ByteArray(1))
        dir.resolve("data.r00").writeBytes(ByteArray(1))
        dir.resolve("data.r01").writeBytes(ByteArray(1))
        dir.resolve("data.s00").writeBytes(ByteArray(1))
        dir.resolve("data.t00").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("data.rar"))
        assertEquals(5, result.size)
        assertEquals("data.rar", result[0].name)
        assertEquals("data.r00", result[1].name)
        assertEquals("data.r01", result[2].name)
        assertEquals("data.s00", result[3].name)
        assertEquals("data.t00", result[4].name)
    }

    @Test
    fun `unknown extension returns single file`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        dir.resolve("data.bin").writeBytes(ByteArray(1))

        val result = VolumeDiscovery.discoverVolumes(dir.resolve("data.bin"))
        assertEquals(1, result.size)
        assertEquals("data.bin", result[0].name)
    }
}
