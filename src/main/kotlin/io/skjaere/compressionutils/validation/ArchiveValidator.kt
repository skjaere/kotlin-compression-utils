package io.skjaere.compressionutils.validation

import io.skjaere.compressionutils.ArchiveFileEntry
import io.skjaere.compressionutils.ArchiveService
import io.skjaere.compressionutils.RarFileEntry
import io.skjaere.compressionutils.SevenZipFileEntry
import io.skjaere.compressionutils.VolumeMetaData
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.CRC32

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Validates archive parsing against 7z ground truth.
 *
 * For each non-directory, uncompressed file in an archive:
 * 1. Uses `7z l -slt` to get the expected CRC
 * 2. Uses the library to get byte offsets
 * 3. Reads raw data from those offsets and computes CRC32
 * 4. Compares the two CRCs
 */
object ArchiveValidator {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        if (args.isEmpty() || args[0].isBlank()) {
            System.err.println("Usage: ArchiveValidator <path-to-first-volume>")
            System.exit(1)
        }

        val firstVolume = File(args[0])
        if (!firstVolume.exists()) {
            System.err.println("File not found: ${firstVolume.absolutePath}")
            System.exit(1)
        }

        // Step 1: Discover volumes
        val volumes = VolumeDiscovery.discoverVolumes(firstVolume)
        println("Discovered ${volumes.size} volume(s):")
        volumes.forEach { println("  ${it.name} (${it.length()} bytes)") }
        println()

        // Step 2: PAR2 verify/repair
        val par2Data = handlePar2(firstVolume)

        // Step 3: Get ground truth CRCs from 7z
        val groundTruth = getGroundTruth(firstVolume)
        if (groundTruth.isEmpty()) {
            System.err.println("No files found in 7z output")
            System.exit(1)
        }

        val crcMap = groundTruth
            .filter { !it.isFolder && it.crc.isNotEmpty() }
            .associate { it.path to it.crc }

        println("Ground truth CRCs from 7z (${crcMap.size} files):")
        crcMap.forEach { (path, crc) -> println("  $path -> $crc") }
        println()

        // Step 4: Parse with library
        val volumeMetadata = volumes.map { VolumeMetaData(filename = it.name, size = it.length()) }
        val stream = ConcatenatedFileSeekableInputStream(volumes)
        val entries = stream.use { ArchiveService.listFiles(it, volumeMetadata, par2Data) }

        println("Library found ${entries.size} entries:")
        entries.forEach { entry ->
            when (entry) {
                is RarFileEntry -> {
                    println("  [RAR] ${entry.path} (${entry.uncompressedSize} bytes, method=${entry.compressionMethod}, split=${entry.isSplit})")
                    if (entry.splitParts.isNotEmpty()) {
                        entry.splitParts.forEach { part ->
                            println("    part: vol=${part.volumeIndex}, dataStart=${part.dataStartPosition}, dataSize=${part.dataSize}")
                        }
                    } else {
                        println("    dataPosition=${entry.dataPosition}, compressedSize=${entry.compressedSize}")
                    }
                }
                is SevenZipFileEntry -> println("  [7z]  ${entry.path} (${entry.size} bytes, method=${entry.method}, dataOffset=${entry.dataOffset})")
            }
        }
        println()

        // Step 5: Validate each file
        var failures = 0
        var passes = 0
        var skipped = 0

        for (entry in entries) {
            val result = validateEntry(entry, crcMap, volumes)
            when (result) {
                is ValidationResult.Pass -> {
                    passes++
                    println("PASS: ${result.path} (CRC=${result.crc})")
                }
                is ValidationResult.Fail -> {
                    failures++
                    println("FAIL: ${result.path} (expected=${result.expected}, got=${result.actual})")
                }
                is ValidationResult.Skipped -> {
                    skipped++
                    println("SKIP: ${result.path} (${result.reason})")
                }
            }
        }

        // Step 6: Check for ground truth files not found by the library
        val libraryPaths = entries.map { entry ->
            when (entry) {
                is RarFileEntry -> entry.path.replace('\\', '/')
                is SevenZipFileEntry -> entry.path
            }
        }.toSet()

        for ((path, crc) in crcMap) {
            if (path !in libraryPaths) {
                failures++
                println("FAIL: $path (not found by library, expected CRC=$crc)")
            }
        }

        // Step 7: Report
        println()
        println("Results: $passes passed, $failures failed, $skipped skipped")

        if (failures > 0) {
            System.exit(1)
        }
    }

    private sealed interface ValidationResult {
        data class Pass(val path: String, val crc: String) : ValidationResult
        data class Fail(val path: String, val expected: String, val actual: String) : ValidationResult
        data class Skipped(val path: String, val reason: String) : ValidationResult
    }

    private suspend fun validateEntry(
        entry: ArchiveFileEntry,
        crcMap: Map<String, String>,
        volumes: List<File>
    ): ValidationResult {
        return when (entry) {
            is RarFileEntry -> validateRarEntry(entry, crcMap, volumes)
            is SevenZipFileEntry -> validateSevenZipEntry(entry, crcMap, volumes)
        }
    }

    private suspend fun validateRarEntry(
        entry: RarFileEntry,
        crcMap: Map<String, String>,
        volumes: List<File>
    ): ValidationResult {
        if (entry.isDirectory) return ValidationResult.Skipped(entry.path, "directory")
        if (!entry.isUncompressed) return ValidationResult.Skipped(entry.path, "compressed (method=${entry.compressionMethod})")

        val normalizedPath = entry.path.replace('\\', '/')
        val expectedCrc = crcMap[normalizedPath]
            ?: return ValidationResult.Skipped(entry.path, "no ground truth CRC")

        val crc32 = CRC32()
        ConcatenatedFileSeekableInputStream(volumes).use { stream ->
            if (entry.splitParts.isNotEmpty()) {
                // Split file: read each part
                for (part in entry.splitParts) {
                    stream.seek(part.dataStartPosition)
                    readIntoCrc(stream, part.dataSize, crc32)
                }
            } else {
                // Single-part file
                stream.seek(entry.dataPosition)
                readIntoCrc(stream, entry.compressedSize, crc32)
            }
        }

        val actualCrc = "%08X".format(crc32.value)
        return if (actualCrc == expectedCrc) {
            ValidationResult.Pass(entry.path, actualCrc)
        } else {
            ValidationResult.Fail(entry.path, expectedCrc, actualCrc)
        }
    }

    private suspend fun validateSevenZipEntry(
        entry: SevenZipFileEntry,
        crcMap: Map<String, String>,
        volumes: List<File>
    ): ValidationResult {
        if (entry.isDirectory) return ValidationResult.Skipped(entry.path, "directory")
        if (entry.method != null && entry.method != "Copy" && entry.method.isNotEmpty()) {
            return ValidationResult.Skipped(entry.path, "compressed (method=${entry.method})")
        }

        val expectedCrc = crcMap[entry.path]
            ?: return ValidationResult.Skipped(entry.path, "no ground truth CRC")

        val crc32 = CRC32()
        ConcatenatedFileSeekableInputStream(volumes).use { stream ->
            stream.seek(entry.dataOffset)
            readIntoCrc(stream, entry.size, crc32)
        }

        val actualCrc = "%08X".format(crc32.value)
        return if (actualCrc == expectedCrc) {
            ValidationResult.Pass(entry.path, actualCrc)
        } else {
            ValidationResult.Fail(entry.path, expectedCrc, actualCrc)
        }
    }

    private suspend fun readIntoCrc(stream: ConcatenatedFileSeekableInputStream, bytesToRead: Long, crc32: CRC32) {
        val buffer = ByteArray(8192)
        var remaining = bytesToRead
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val bytesRead = stream.read(buffer, 0, toRead)
            if (bytesRead <= 0) break
            crc32.update(buffer, 0, bytesRead)
            remaining -= bytesRead
        }
    }

    private fun handlePar2(firstVolume: File): ByteArray? {
        val dir = firstVolume.parentFile ?: File(".")
        val par2IndexFile = dir.listFiles()
            ?.filter { it.name.lowercase().endsWith(".par2") }
            ?.filter { !it.name.lowercase().contains("vol") }
            ?.firstOrNull()
            ?: return null

        println("Found PAR2 index: ${par2IndexFile.name}")

        // Verify
        val verifyResult = runCommand("par2", "verify", par2IndexFile.absolutePath)
        if (verifyResult.exitCode != 0) {
            println("PAR2 verify failed, attempting repair...")
            val repairResult = runCommand("par2", "repair", par2IndexFile.absolutePath)
            if (repairResult.exitCode != 0) {
                System.err.println("PAR2 repair failed:")
                System.err.println(repairResult.stderr)
            } else {
                println("PAR2 repair succeeded")
            }
        } else {
            println("PAR2 verify passed")
        }
        println()

        return par2IndexFile.readBytes()
    }

    private fun getGroundTruth(firstVolume: File): List<SevenZipCliEntry> {
        val result = runCommand("7z", "l", "-slt", firstVolume.absolutePath)
        if (result.exitCode != 0) {
            System.err.println("7z failed (exit ${result.exitCode}):")
            System.err.println(result.stderr)
            System.exit(1)
        }
        return SevenZipOutputParser.parse(result.stdout)
    }

    private fun runCommand(vararg command: String): CommandResult {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CommandResult(exitCode, stdout, stderr)
    }
}
