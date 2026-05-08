package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ResolveVolumesTest {

    // -- volumeSortKey tests --

    @Test
    fun `volumeSortKey modern RAR part ordering`() {
        assertEquals(1, ArchiveService.volumeSortKey("archive.part1.rar"))
        assertEquals(2, ArchiveService.volumeSortKey("archive.part2.rar"))
        assertEquals(10, ArchiveService.volumeSortKey("archive.part10.rar"))
        assertEquals(100, ArchiveService.volumeSortKey("archive.part100.rar"))
    }

    @Test
    fun `volumeSortKey modern RAR is case insensitive`() {
        assertEquals(1, ArchiveService.volumeSortKey("archive.Part1.Rar"))
        assertEquals(2, ArchiveService.volumeSortKey("archive.PART2.RAR"))
    }

    @Test
    fun `volumeSortKey old-style RAR ordering`() {
        // .rar is first (0), .r00 is next (1), .r01 (2), ...
        assertEquals(0, ArchiveService.volumeSortKey("archive.rar"))
        assertEquals(1, ArchiveService.volumeSortKey("archive.r00"))
        assertEquals(2, ArchiveService.volumeSortKey("archive.r01"))
        assertEquals(100, ArchiveService.volumeSortKey("archive.r99"))
    }

    @Test
    fun `volumeSortKey old-style RAR s-extensions after r-extensions`() {
        // .sNN = (s-r)*1000 + NN + 1 = 1*1000 + NN + 1
        val r99 = ArchiveService.volumeSortKey("archive.r99")
        val s00 = ArchiveService.volumeSortKey("archive.s00")
        assert(s00 > r99) { "s00 ($s00) should sort after r99 ($r99)" }
    }

    @Test
    fun `volumeSortKey split 7z ordering`() {
        assertEquals(1, ArchiveService.volumeSortKey("archive.7z.001"))
        assertEquals(2, ArchiveService.volumeSortKey("archive.7z.002"))
        assertEquals(100, ArchiveService.volumeSortKey("archive.7z.100"))
    }

    @Test
    fun `volumeSortKey single 7z`() {
        assertEquals(0, ArchiveService.volumeSortKey("archive.7z"))
    }

    @Test
    fun `volumeSortKey unknown extension sorts last`() {
        assertEquals(Int.MAX_VALUE, ArchiveService.volumeSortKey("obfuscated_name"))
        assertEquals(Int.MAX_VALUE, ArchiveService.volumeSortKey("abc123def456"))
    }

    // -- resolveVolumes tests --

    @Test
    fun `resolveVolumes with correctly ordered volumes returns same order`() {
        val volumes = listOf(
            VolumeMetaData("archive.part1.rar", 1000),
            VolumeMetaData("archive.part2.rar", 1000),
            VolumeMetaData("archive.part3.rar", 500)
        )

        val result = ArchiveService.resolveVolumes(volumes)

        assertEquals(
            listOf("archive.part1.rar", "archive.part2.rar", "archive.part3.rar"),
            result.map { it.filename }
        )
    }

    @Test
    fun `resolveVolumes sorts out-of-order volumes`() {
        val volumes = listOf(
            VolumeMetaData("archive.part3.rar", 500),
            VolumeMetaData("archive.part1.rar", 1000),
            VolumeMetaData("archive.part2.rar", 1000)
        )

        val result = ArchiveService.resolveVolumes(volumes)

        assertEquals(
            listOf("archive.part1.rar", "archive.part2.rar", "archive.part3.rar"),
            result.map { it.filename }
        )
    }

    @Test
    fun `resolveVolumes sorts old-style RAR volumes`() {
        val volumes = listOf(
            VolumeMetaData("archive.r01", 1000),
            VolumeMetaData("archive.rar", 1000),
            VolumeMetaData("archive.r00", 1000)
        )

        val result = ArchiveService.resolveVolumes(volumes)

        assertEquals(
            listOf("archive.rar", "archive.r00", "archive.r01"),
            result.map { it.filename }
        )
    }

    @Test
    fun `resolveVolumes sorts 7z split volumes`() {
        val volumes = listOf(
            VolumeMetaData("archive.7z.003", 500),
            VolumeMetaData("archive.7z.001", 1000),
            VolumeMetaData("archive.7z.002", 1000)
        )

        val result = ArchiveService.resolveVolumes(volumes)

        assertEquals(
            listOf("archive.7z.001", "archive.7z.002", "archive.7z.003"),
            result.map { it.filename }
        )
    }

    @Test
    fun `resolveVolumes without PAR2 preserves filenames but sorts`() {
        val volumes = listOf(
            VolumeMetaData("archive.part2.rar", 1000),
            VolumeMetaData("archive.part1.rar", 1000)
        )

        val result = ArchiveService.resolveVolumes(volumes, par2Data = null)

        assertEquals(
            listOf("archive.part1.rar", "archive.part2.rar"),
            result.map { it.filename }
        )
    }

    @Test
    fun `resolveVolumes drops unresolvable bare-name files with no archive signature`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()

        // Obfuscated volumes whose first16kb doesn't match any PAR2 hash16k AND
        // whose first bytes aren't a RAR/7z signature — we have no positive
        // evidence these are archive volumes, so they're dropped to avoid
        // corrupting the concatenated stream (was the source of the
        // "Expected RAR signature... got: FF D8 FF E0" production failure when
        // .jpg/.nfo side files leaked into the volume list).
        val volumes = listOf(
            VolumeMetaData("obfuscated_name_2", 1000, ByteArray(16384) { 0x02 }),
            VolumeMetaData("obfuscated_name_1", 1000, ByteArray(16384) { 0x01 })
        )

        val result = ArchiveService.resolveVolumes(volumes, par2Data)

        assertEquals(0, result.size)
    }

    @Test
    fun `resolveVolumes keeps unresolvable bare-name files when first16kb is a RAR signature`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()

        // Bare-name files whose name PAR2 can't recover, but whose bytes
        // start with a RAR4 signature (0x52 0x61 0x72 0x21 0x1A 0x07 0x00).
        // These are kept — the byte signature is positive evidence they're
        // archive volumes.
        val rar4Header = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        val volumes = listOf(
            VolumeMetaData("obfuscated_a", 1000, rar4Header + ByteArray(16384 - rar4Header.size)),
            VolumeMetaData("obfuscated_b", 1000, rar4Header + ByteArray(16384 - rar4Header.size))
        )

        val result = ArchiveService.resolveVolumes(volumes, par2Data)

        assertEquals(2, result.size)
    }

    @Test
    fun `resolveVolumes with PAR2 resolves obfuscated filenames and sorts`() {
        // PAR2 generated from test-multivolume.part1.rar and test-multivolume.part2.rar
        val par2Data = javaClass.getResourceAsStream("/test-multivolume.par2")!!.readAllBytes()

        val part1Bytes = javaClass.getResourceAsStream("/test-multivolume.part1.rar")!!.readAllBytes()
        val part2Bytes = javaClass.getResourceAsStream("/test-multivolume.part2.rar")!!.readAllBytes()

        val part1First16kb = part1Bytes.sliceArray(0 until minOf(16384, part1Bytes.size))
        val part2First16kb = part2Bytes.sliceArray(0 until minOf(16384, part2Bytes.size))

        // Obfuscated names in reverse order
        val volumes = listOf(
            VolumeMetaData("obfuscated_2", part2Bytes.size.toLong(), part2First16kb),
            VolumeMetaData("obfuscated_1", part1Bytes.size.toLong(), part1First16kb)
        )

        val result = ArchiveService.resolveVolumes(volumes, par2Data)

        assertEquals(
            listOf("test-multivolume.part1.rar", "test-multivolume.part2.rar"),
            result.map { it.filename }
        )
    }

    @Test
    fun `resolveVolumes with known extensions skips PAR2 resolution`() {
        val par2Data = javaClass.getResourceAsStream("/test.par2")!!.readAllBytes()

        // Volumes already have known extensions — should skip PAR2 resolution
        val volumes = listOf(
            VolumeMetaData("archive.part2.rar", 1000),
            VolumeMetaData("archive.part1.rar", 1000)
        )

        val result = ArchiveService.resolveVolumes(volumes, par2Data)

        // Should just sort, not resolve
        assertEquals(
            listOf("archive.part1.rar", "archive.part2.rar"),
            result.map { it.filename }
        )
    }
}
