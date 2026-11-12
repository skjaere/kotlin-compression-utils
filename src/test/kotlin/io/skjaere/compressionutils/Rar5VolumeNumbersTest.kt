package io.skjaere.compressionutils

import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A RAR5 volume's main archive header gives its place in its set, whatever name it was posted
 * under: volumes whose names give no place are read in that order, a set whose numbers skip one
 * lacks a volume, and two volumes that give one place can't be told apart.
 */
class Rar5VolumeNumbersTest {
    private val payload = Random(21).nextBytes(200_000)

    /** [payload] packed as [inner] in RAR5 volumes of 64 KiB, named [base].partN.rar. */
    private fun rar5Set(base: String, inner: String = "$base.mkv", data: ByteArray = payload) =
        ArchiveGenerator.generate(data, 64L * 1024, ContainerType.RAR5, inner).mapIndexed { v, volume ->
            VolumeMetaData("$base.part${v + 1}.rar", volume.data.size.toLong(), volume.data)
        }

    /** [volumes] posted under [names], each with its first 16 KB, as a release's are known. */
    private fun posted(volumes: List<VolumeMetaData>, names: List<String>) =
        volumes.zip(names) { volume, name -> VolumeMetaData(name, volume.size, volume.first16kb!!.copyOf(16 * 1024)) }

    /** The original names of [resolved], by their first 16 KB, among [volumes]. */
    private fun originalNames(resolved: List<VolumeMetaData>, volumes: List<VolumeMetaData>) =
        resolved.map { r -> volumes.single { it.first16kb!!.copyOf(16 * 1024).contentEquals(r.first16kb) }.filename }

    @Test
    fun `volumeNumber gives each RAR5 volume's place in its set`() {
        val set = rar5Set("Movie.2020")
        assertTrue(set.size > 2)

        assertEquals(set.indices.toList(), set.map { Rar5Parser.volumeNumber(it.first16kb!!) })
    }

    @Test
    fun `volumeNumber gives none for what isn't a RAR5 volume of a set whose main header it holds`() {
        val single = ArchiveGenerator.generate(payload.copyOf(1_000), 1, ContainerType.RAR5, "Movie.srt").single().data
        val rar4 = ArchiveGenerator.generate(payload, 64L * 1024, ContainerType.RAR4, "Movie.mkv")[1].data
        val sevenZip = ArchiveGenerator.generate(payload, 64L * 1024, ContainerType.SEVENZIP, "Movie.mkv").first().data
        val second = rar5Set("Movie.2020")[1].first16kb!!
        // Header encryption: an encryption header (AES-256, 2^15 rounds, its salt) in front of the main header.
        val encrypted = SIGNATURE + rar5Header(4, byteArrayOf(0, 0, 15) + ByteArray(16)) + second.copyOfRange(8, 64)
        val damaged = second.copyOf().also { it[13] = (it[13] + 1).toByte() }

        listOf(
            "a single-volume archive" to single, "a RAR4 volume" to rar4, "a 7z volume" to sevenZip,
            "header-encrypted" to encrypted, "cut short" to second.copyOf(12), "a damaged header" to damaged,
        ).forEach { (what, bytes) -> assertNull(Rar5Parser.volumeNumber(bytes), what) }
    }

    @Test
    fun `resolveVolumes orders RAR5 volumes whose names give no place by their headers`() {
        val set = rar5Set("Movie.2020")
        val reversed = set.reversed()
        val shuffled = set.shuffled(Random(3)).also { assertTrue(it != set) }

        listOf(reversed, shuffled).forEach { order ->
            listOf("", ".rar", ".bin").forEach { extension ->
                val names = order.indices.map { "${OBFUSCATED[it]}$extension" }

                val resolved = ArchiveService.resolveVolumes(posted(order, names))

                assertEquals(set.map { it.filename }, originalNames(resolved, set), "$extension ${order.map { it.filename }}")
            }
        }
    }

    @Test
    fun `RAR5 volumes ordered by their headers are read whole`(): Unit = runBlocking {
        val set = rar5Set("Movie.2020").reversed()
        val unnamed = set.mapIndexed { v, volume -> VolumeMetaData(OBFUSCATED[v], volume.size, volume.first16kb) }
        val ordered = ArchiveService.resolveVolumes(unnamed)
        val stream = ByteArraySeekableInputStream(ordered.fold(ByteArray(0)) { all, v -> all + v.first16kb!! })

        val entry = assertIs<ListFilesResult.Success>(ArchiveService.listFiles(stream, ordered)).entries
            .filterIsInstance<RarFileEntry>().single()

        val read = entry.splitParts.fold(ByteArray(0)) { all, part ->
            stream.seek(part.dataStartPosition)
            all + ByteArray(part.dataSize.toInt()).also { bytes -> var n = 0; while (n < bytes.size) n += stream.read(bytes, n, bytes.size - n) }
        }
        assertContentEquals(payload, read)
    }

    @Test
    fun `a set whose headers skip a volume lacks it`() {
        val set = rar5Set("Movie.2020")
        listOf(1, 0).forEach { absent ->
            val held = set.filterIndexed { v, _ -> v != absent }.reversed()

            val e = assertFailsWith<MissingArchiveVolumeException> {
                ArchiveService.resolveVolumes(posted(held, held.indices.map { OBFUSCATED[it] }))
            }

            assertEquals(absent, e.number)
        }
    }

    /** Named volumes too: a set named part1, part2, part4 lacks its third, as its names say too. */
    @Test
    fun `a named set whose headers skip a volume lacks it`() {
        val held = rar5Set("Movie.2020").filterIndexed { v, _ -> v != 2 }

        val e = assertFailsWith<MissingArchiveVolumeException> { ArchiveService.resolveVolumes(held) }

        assertEquals(2, e.number)
    }

    /** Two sets posted under names that give no place: which volume goes with which can't be told. */
    @Test
    fun `two volumes that give one place fail`() {
        val sets = rar5Set("Show.S01E01", data = Random(1).nextBytes(150_000)) + rar5Set("Show.S01E02", data = Random(2).nextBytes(150_000))

        assertFailsWith<ConflictingArchiveVolumesException> {
            ArchiveService.resolveVolumes(posted(sets, sets.indices.map { "x%08d".format(it) }))
        }
    }

    @Test
    fun `where names give another order than the headers, the headers win`() {
        val set = rar5Set("Movie.2020")
        val misnamed = posted(set, set.map { it.filename }.toMutableList().apply { add(1, removeAt(2)) })

        val resolved = ArchiveService.resolveVolumes(misnamed)

        assertEquals(set.indices.toList(), resolved.map { Rar5Parser.volumeNumber(it.first16kb!!) })
    }

    /** Volumes the PAR2 data names (part1, part3, ...) and others it doesn't: they make one set, in its order. */
    @Test
    fun `volumes names don't place join the set they complete`() {
        val set = rar5Set("Movie.2020")
        val names = set.mapIndexed { v, volume -> if (v % 2 == 0) volume.filename else OBFUSCATED[v] }

        val resolved = ArchiveService.resolveVolumes(posted(set.reversed(), names.reversed()))

        assertEquals(set.map { it.filename }, originalNames(resolved, set))
    }

    /**
     * Guard: named sets whose headers agree, a single-volume archive beside a set, RAR4 volumes, and
     * volumes known without their first 16 KB keep the order their names give, which is the same.
     */
    @Test
    fun `ordinary releases keep the order their names give`() {
        val film = rar5Set("Movie.2020")
        val subs = ArchiveGenerator.generate(payload.copyOf(5_000), 1, ContainerType.RAR5, "Movie.2020.srt")
            .map { VolumeMetaData("Movie.2020.subs.rar", it.data.size.toLong(), it.data) }
        val pack = (1..3).flatMap { e -> rar5Set("Show.S01E0$e", data = Random(e).nextBytes(150_000)) }
        val rar4 = ArchiveGenerator.generate(payload, 64L * 1024, ContainerType.RAR4, "Movie.mkv").mapIndexed { v, volume ->
            VolumeMetaData(if (v == 0) "Movie.rar" else "Movie.r%02d".format(v - 1), volume.data.size.toLong(), volume.data)
        }

        listOf(film, film + subs, pack, rar4).forEach { release ->
            listOf(release, release.reversed()).forEach { listed ->
                val byNames = ArchiveService.resolveVolumes(listed.map { VolumeMetaData(it.filename, it.size) })

                assertEquals(byNames.map { it.filename }, ArchiveService.resolveVolumes(listed).map { it.filename })
            }
        }
        assertEquals(film.map { it.filename }, ArchiveService.resolveVolumes(film.reversed()).map { it.filename })
    }

    /** prepare orders the volumes, then the header walk orders them again: the second changes nothing. */
    @Test
    fun `ordering by the headers is the same done twice`() {
        val set = rar5Set("Movie.2020")
        val subs = ArchiveGenerator.generate(payload.copyOf(5_000), 1, ContainerType.RAR5, "Movie.2020.srt")
            .map { VolumeMetaData("Movie.2020.subs.rar", it.data.size.toLong(), it.data) }
        val releases = listOf(
            posted(set.reversed(), set.indices.map { OBFUSCATED[it] }),
            subs + posted(set.reversed(), set.indices.map { "${OBFUSCATED[it]}.rar" }),
            posted(set.shuffled(Random(5)), set.shuffled(Random(5)).mapIndexed { v, volume -> if (v % 2 == 0) volume.filename else OBFUSCATED[v] }),
        )

        releases.forEach { release ->
            val once = ArchiveService.resolveVolumes(release)
            assertEquals(once, ArchiveService.resolveVolumes(once))
        }
    }

    private fun rar5Header(type: Int, body: ByteArray): ByteArray {
        val inner = byteArrayOf(type.toByte(), 0) + body
        val sized = byteArrayOf(inner.size.toByte()) + inner
        val crc = CRC32().apply { update(sized) }.value.toInt()
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc).array() + sized
    }

    private companion object {
        val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        /** Names that look like an obfuscating poster's, one per volume. */
        val OBFUSCATED = listOf("a8f7d6e5", "b91c2d3e", "c07e4f1a", "d2b5a9c4", "e6f3c8b1", "f4a1d7e9")
    }
}
