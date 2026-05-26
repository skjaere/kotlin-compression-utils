package io.skjaere.compressionutils

import io.skjaere.compressionutils.validation.ConcatenatedFileSeekableInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the seek-loop lockup observed in prod when importing
 * old-style RAR4 multi-volume releases (e.g. the QRUS WEBRip Arrested Development
 * S04 set posted to a.b.teevee in 2014).
 *
 * The archive uses RAR 1.5 with a recovery record sub-block in every volume.
 * [Rar4Parser.inferSplitPositions] does not model recovery-record overhead, so
 * the inferred end-of-data position lands ~189KB short of the real boundary —
 * the parser then walks off the block grid, reads garbage as a block header,
 * and the "skip this block" branch seeks backward by 7 bytes, creating an
 * infinite tight loop that hammers the seekable stream.
 *
 * Fix: the inference path now verifies the inferred parts sum to the file's
 * declared size. If they don't, it falls back to per-volume parsing — slower
 * but correct, because real EOA markers and signatures drive the seeks instead
 * of a guessed end-of-data offset.
 *
 * Local-only test: requires the actual volume files on disk because synthesising
 * a recovery record is non-trivial. Skips when the directory is absent.
 */
class QrusRar4Repro {

    companion object {
        private const val EXPECTED_MKV_SIZE = 2_242_451_020L
        private const val PARSE_TIMEOUT_MS = 60_000L  // Fallback path walks 46 volumes — give it room
    }

    private val arrdev = File("/home/william/IdeaProjects/nzb-streamer-utils/arrdev")

    @Test
    fun `parses QRUS RAR4 archive without entering the seek-loop`() = runBlocking {
        assumeTrue(arrdev.exists(), "Skipping: ${arrdev.absolutePath} not present")

        val base = "arrested.development.s04e08.1080p.webrip.x264-qrus"
        val volumeFiles = listOf(File(arrdev, "$base.rar")) +
            (0..44).map { File(arrdev, "$base.r%02d".format(it)) }
        volumeFiles.forEach { assumeTrue(it.exists(), "Skipping: missing ${it.name}") }

        val volumes = volumeFiles.map { VolumeMetaData(filename = it.name, size = it.length()) }
        val totalSize = volumes.sumOf { it.size }

        val service = RarArchiveService()
        val entries = withTimeout(PARSE_TIMEOUT_MS) {
            ConcatenatedFileSeekableInputStream(volumeFiles).use { stream ->
                service.listFilesFromConcatenatedStream(
                    stream = stream,
                    totalArchiveSize = totalSize,
                    volumeSizes = volumes.map { it.size },
                    archiveName = volumeFiles.first().name,
                )
            }
        }

        assertEquals(1, entries.size, "Expected exactly one file in the archive, got: ${entries.map { it.path }}")
        val mkv = entries.single()
        assertEquals(
            "$base.mkv", mkv.path,
            "Expected the mkv to be the single file entry"
        )
        assertEquals(
            EXPECTED_MKV_SIZE, mkv.uncompressedSize,
            "Expected uncompressed size to match the real mkv (per `unrar l`)"
        )
        assertTrue(
            mkv.splitParts.isNotEmpty(),
            "Split parts must be populated so streaming can locate each chunk"
        )
        val totalSplitSize = mkv.splitParts.sumOf { it.dataSize }
        assertEquals(
            mkv.uncompressedSize, totalSplitSize,
            "Sum of split-part sizes must equal the file's uncompressed size"
        )
    }
}
