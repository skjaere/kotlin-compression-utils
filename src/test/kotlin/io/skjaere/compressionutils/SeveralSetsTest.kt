package io.skjaere.compressionutils

import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A release can hold several archive sets: one per episode of a season pack, or a film's and its
 * subtitles'. Each set's volumes stay together, in their own order, and no file of one set is read
 * from another's volumes.
 */
class SeveralSetsTest {
    private val rar4 = RarArchiveService()

    /** [payload] packed as [inner] in RAR4 volumes of 64 KiB named [base].rar, [base].r00, ... */
    private fun rar4Set(base: String, inner: String, payload: ByteArray) =
        ArchiveGenerator.generate(payload, 64L * 1024, ContainerType.RAR4, inner).mapIndexed { v, volume ->
            VolumeMetaData(if (v == 0) "$base.rar" else "$base.r%02d".format(v - 1), volume.data.size.toLong(), volume.data)
        }

    private fun bytes(name: String, size: Int) = Random(name.hashCode()).nextBytes(size)

    /** The bytes of [entry] read from [stream] where its parts say they are. */
    private suspend fun read(stream: SeekableInputStream, entry: RarFileEntry): ByteArray =
        entry.splitParts.fold(ByteArray(0)) { all, part ->
            stream.seek(part.dataStartPosition)
            val bytes = ByteArray(part.dataSize.toInt())
            var read = 0
            while (read < bytes.size) read += stream.read(bytes, read, bytes.size - read).also { check(it > 0) }
            all + bytes
        }

    /** The volumes' bytes one after another, as a release's stream delivers them. */
    private fun concatenated(volumes: List<VolumeMetaData>) =
        ByteArraySeekableInputStream(volumes.fold(ByteArray(0)) { all, v -> all + v.first16kb!! })

    private val episodes = (1..3).associate { e -> "Show.S01E0$e" to bytes("episode $e", 150_000) }

    private fun seasonPack() = episodes.flatMap { (base, data) -> rar4Set(base, "$base.mkv", data) }

    @Test
    fun `resolveVolumes keeps each set's volumes together`() {
        val pack = seasonPack()

        val alphabetical = ArchiveService.resolveVolumes(pack.sortedBy { it.filename })

        val expected = pack.map { it.filename }
        assertEquals(expected, ArchiveService.resolveVolumes(pack).map { it.filename })
        assertEquals(expected, alphabetical.map { it.filename })
    }

    /**
     * A PAR2 index made over a folder names each file with its path in it: a volume named by it
     * still belongs to the set of the volumes named as posted.
     */
    @Test
    fun `resolveVolumes keeps a set together whose volumes the PAR2 index names with their folder`() {
        val sets = (1..2).map { e ->
            ArchiveGenerator.generate(bytes("episode $e", 150_000), 64L * 1024, ContainerType.RAR5, "Show.S01E0$e.mkv")
                .mapIndexed { v, volume -> "Show.S01E0$e.part${v + 1}.rar" to volume.data }
        }
        val hidden = sets[0][1]
        val posted = sets.flatten().map { (name, data) ->
            VolumeMetaData(if (name == hidden.first) "a8f7d6e5" else name, data.size.toLong(), data.copyOf(16 * 1024))
        }

        val resolved = ArchiveService.resolveVolumes(posted, par2File(listOf("Show.S01/${hidden.first}" to hidden.second)))

        assertEquals(
            sets.flatten().map { (name, _) -> if (name == hidden.first) "Show.S01/$name" else name },
            resolved.map { it.filename },
        )
    }

    @Test
    fun `resolveVolumes keeps volumes obfuscated one by one in their order`() {
        val names = listOf("d2b5a9c4.rar", "a8f7d6e5.rar", "c07e4f1a.rar")

        val resolved = ArchiveService.resolveVolumes(names.map { VolumeMetaData(it, 1000) })

        assertEquals(names, resolved.map { it.filename })
    }

    @Test
    fun `a RAR4 season pack lists every episode, each read whole`(): Unit = runBlocking {
        val ordered = ArchiveService.resolveVolumes(seasonPack())
        val stream = concatenated(ordered)

        val entries = assertIs<ListFilesResult.Success>(ArchiveService.listFiles(stream, ordered)).entries
            .filterIsInstance<RarFileEntry>()

        assertEquals(episodes.keys.map { "$it.mkv" }, entries.map { it.path })
        entries.forEach { entry ->
            assertContentEquals(episodes.getValue(entry.path.removeSuffix(".mkv")), read(stream, entry), entry.path)
        }
    }

    /** Nothing infers one set's file into another's volumes, even with the sets interleaved. */
    @Test
    fun `the RAR4 parser reads each file of interleaved sets from its own volumes`(): Unit = runBlocking {
        val sets = episodes.map { (base, data) -> rar4Set(base, "$base.mkv", data) }
        // As sorting by volume number alone used to give: every set's first volume, then every second, ...
        val interleaved = sets.first().indices.flatMap { v -> sets.mapNotNull { it.getOrNull(v) } }
        val stream = concatenated(interleaved)

        val entries = rar4.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = interleaved.sumOf { it.size },
            volumeSizes = interleaved.map { it.size },
        )

        assertEquals(episodes.keys.map { "$it.mkv" }, entries.map { it.path })
        entries.forEach { entry ->
            assertContentEquals(episodes.getValue(entry.path.removeSuffix(".mkv")), read(stream, entry), entry.path)
        }
    }

    @Test
    fun `a RAR4 film with a subtitle archive posted after it is read whole`(): Unit = runBlocking {
        val movie = bytes("movie", 200_000)
        val subtitles = bytes("subtitles", 5_000)
        val posted = rar4Set("Movie.2020", "Movie.2020.mkv", movie) + rar4Set("Movie.2020.subs", "Movie.2020.srt", subtitles)
        val ordered = ArchiveService.resolveVolumes(posted)
        val stream = concatenated(ordered)

        val entries = assertIs<ListFilesResult.Success>(ArchiveService.listFiles(stream, ordered)).entries
            .filterIsInstance<RarFileEntry>().associateBy { it.path }

        assertEquals(setOf("Movie.2020.mkv", "Movie.2020.srt"), entries.keys)
        assertContentEquals(movie, read(stream, entries.getValue("Movie.2020.mkv")))
        assertContentEquals(subtitles, read(stream, entries.getValue("Movie.2020.srt")))
    }

    /** The order sorting by volume number alone gave: the subtitles' archive between the film's first volumes. */
    @Test
    fun `the RAR4 parser reads a film whose subtitle archive lies among its volumes from its own`(): Unit = runBlocking {
        val movie = bytes("movie", 200_000)
        val film = rar4Set("Movie.2020", "Movie.2020.mkv", movie)
        val volumes = listOf(film.first()) + rar4Set("Movie.2020.subs", "Movie.2020.srt", bytes("subtitles", 5_000)) + film.drop(1)
        val stream = concatenated(volumes)

        val entries = rar4.listFilesFromConcatenatedStream(
            stream = stream,
            totalArchiveSize = volumes.sumOf { it.size },
            volumeSizes = volumes.map { it.size },
        )

        assertContentEquals(movie, read(stream, entries.single { it.path == "Movie.2020.mkv" }))
    }
}
