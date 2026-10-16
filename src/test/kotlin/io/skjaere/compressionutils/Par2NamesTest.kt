package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * A PAR2 description names the file whose first 16 KB and length are the ones it gives, and none
 * when several with different names give the same: files that start alike (disc images, whose
 * first 32 KB are zeros, a DVD's VIDEO_TS.IFO and its copy VIDEO_TS.BUP) keep their own names (NR-8).
 */
class Par2NamesTest {
    /** A disc image of [size] bytes: zeros for its first 32 KB, as every ISO 9660 image's are. */
    private fun discImage(name: String, size: Int) = ByteArray(32 * 1024) + Random(name.hashCode()).nextBytes(size - 32 * 1024)

    private fun posted(name: String, data: ByteArray) = VolumeMetaData(name, data.size.toLong(), data.copyOf(16 * 1024))

    @Test
    fun `resolveVolumes names disc images that start alike by their length`() {
        val discs = listOf("Show.S01.Disc1.iso" to discImage("disc 1", 300_000), "Show.S01.Disc2.iso" to discImage("disc 2", 350_000))

        val resolved = ArchiveService.resolveVolumes(
            listOf(posted("a8f7d6e5", discs[0].second), posted("b91c2d3e", discs[1].second)),
            par2File(discs),
        )

        assertEquals(discs.map { it.first }, resolved.map { it.filename })
    }

    @Test
    fun `resolveVolumes leaves disc images that start alike and are as long under their own names`() {
        val discs = listOf("Show.S01.Disc1.iso" to discImage("disc 1", 300_000), "Show.S01.Disc2.iso" to discImage("disc 2", 300_000))

        val resolved = ArchiveService.resolveVolumes(
            listOf(posted("a8f7d6e5", discs[0].second), posted("b91c2d3e", discs[1].second)),
            par2File(discs),
        )

        assertEquals(listOf("a8f7d6e5", "b91c2d3e"), resolved.map { it.filename })
    }

    @Test
    fun `a description names the file whose first 16 KB and length it gives`() {
        val movie = Random(1).nextBytes(40_000)
        val names = Par2FileNames.parse(par2File(listOf("Movie.2020.mkv" to movie)))

        assertEquals("Movie.2020.mkv", names.nameOf(movie.copyOf(16 * 1024), movie.size.toLong()))
        assertEquals(null, names.nameOf(movie.copyOf(16 * 1024), movie.size + 1L))
        assertEquals(null, names.nameOf(Random(2).nextBytes(16 * 1024), movie.size.toLong()))
    }

    @Test
    fun `descriptions with different names that give the same first 16 KB and length name nothing`() {
        val ifo = Random(1).nextBytes(12_000)
        val names = Par2FileNames.parse(par2File(listOf("VIDEO_TS.IFO" to ifo, "VIDEO_TS.BUP" to ifo.copyOf())))

        assertEquals(null, names.nameOf(ifo, ifo.size.toLong()))
        assertEquals(emptyMap(), names.byHash16k)
    }

    /** An index and a recovery volume, or two indexes, describe a file alike: that names it. */
    @Test
    fun `descriptions with one name name the file`() {
        val movie = Random(1).nextBytes(40_000)
        val index = par2File(listOf("Movie.2020.mkv" to movie))
        val names = Par2FileNames(Par2Parser.parse(index + index).files)

        assertEquals("Movie.2020.mkv", names.nameOf(movie.copyOf(16 * 1024), movie.size.toLong()))
        assertEquals(mapOf(Par2FileNames.md5Hex(movie) to "Movie.2020.mkv"), names.byHash16k)
    }

    @Test
    fun `data that isn't PAR2 names nothing`() {
        assertEquals(emptyMap(), Par2FileNames.parse(Random(1).nextBytes(1_000)).byHash16k)
        assertEquals(emptyMap(), Par2FileNames.parse(null).byHash16k)
    }
}
