# kotlin-compression-utils

[![CI](https://github.com/skjaere/kotlin-compression-utils/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/skjaere/kotlin-compression-utils/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/skjaere/kotlin-compression-utils/branch/main/graph/badge.svg)](https://codecov.io/gh/skjaere/kotlin-compression-utils)
[![](https://jitpack.io/v/skjaere/kotlin-compression-utils.svg)](https://jitpack.io/#skjaere/kotlin-compression-utils)

A Kotlin library for extracting **metadata** from RAR4, RAR5, and 7zip archives.

> **Important:** This library does **not** perform archive extraction or compression. It only reads archive headers to extract file listings, byte offsets, sizes, and split/multi-volume information. This makes it suitable for scenarios where you need to inspect archive contents without extracting them, such as building file indexes or streaming specific files from remote archives.

## Supported Formats

- **RAR 4.x** - Full metadata extraction including multi-volume/split archive support
- **RAR 5.x** - Full metadata extraction including multi-volume/split archive support
- **7zip** - Metadata extraction with calculated byte offsets (accurate for uncompressed/store-mode archives)

## Requirements

- Java 25+
- Kotlin 2.3+
- Gradle 9+

## Installation

Add to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.skjaere:kotlin-compression-utils:v0.1.0")
}
```

## Usage

### Unified API (Recommended)

`ArchiveService` is the top-level entry point. It auto-detects the archive type (RAR4, RAR5, 7z) from filenames or byte signatures and dispatches to the appropriate parser. It also supports PAR2-based obfuscated filename resolution.

```kotlin
// From a single file
val entries = ArchiveService.listFiles("/path/to/archive.rar")

// From concatenated multi-volume streams with volume metadata
val volumes = listOf(
    VolumeMetaData(filename = "archive.part1.rar", size = vol1.length()),
    VolumeMetaData(filename = "archive.part2.rar", size = vol2.length()),
)
val entries = ArchiveService.listFiles(stream, volumes)

// With PAR2 data for obfuscated filenames
val entries = ArchiveService.listFiles(stream, volumes, par2Data = par2File.readBytes())
```

Returns `List<ArchiveFileEntry>` (either `RarFileEntry` or `SevenZipFileEntry`).

### Detect Archive Type

```kotlin
val bytes = file.readBytes().sliceArray(0 until 32)
val result = ArchiveTypeDetector.detect(bytes)
println(result.type)          // RAR4, RAR5, SEVENZIP, or UNKNOWN
println(result.isFirstVolume) // true if this is the first volume in a multi-volume set
```

### List Files in a RAR Archive

```kotlin
val service = RarArchiveService()

// From a file path
val entries = service.listFiles("/path/to/archive.rar")

// From an InputStream
val entries = service.listFiles(inputStream)

// From a SeekableInputStream
val entries = service.listFiles(seekableStream)

entries.forEach { entry ->
    println("${entry.path} - ${entry.uncompressedSize} bytes")
    println("  compressed: ${entry.compressedSize}, method: ${entry.compressionMethod}")
    println("  uncompressed: ${entry.isUncompressed}, split: ${entry.isSplit}")
}
```

### List Files in a Multi-Volume RAR Archive

```kotlin
val service = RarArchiveService()

// Provide a concatenated stream of all volumes in order
val entries = service.listFilesFromConcatenatedStream(
    stream = concatenatedStream,
    totalArchiveSize = totalSize,
    volumeSizes = listOf(vol1Size, vol2Size, vol3Size) // enables split position optimization
)

// Split files include position info for each volume
entries.filter { it.isSplit }.forEach { entry ->
    entry.splitParts.forEach { part ->
        println("  Volume ${part.volumeIndex}: offset=${part.dataStartPosition}, size=${part.dataSize}")
    }
}
```

### List Files in a 7zip Archive

```kotlin
val service = SevenZipArchiveService()

val entries = service.listFiles("/path/to/archive.7z")

entries.forEach { entry ->
    println("${entry.path} - ${entry.size} bytes at offset ${entry.dataOffset}")
}
```

### SeekableInputStream

The library provides a `SeekableInputStream` interface for efficient random-access reading:

```kotlin
// Wrap a file
val stream = FileSeekableInputStream(RandomAccessFile(file, "r"))

// Wrap a regular InputStream (forward-only seeking)
val stream = BufferedSeekableInputStream(inputStream)

// Implement for HTTP byte-range requests
class MyHttpStream(url: String, size: Long) : HttpRangeSeekableInputStream(url, size) {
    override fun fetchRange(fromPosition: Long, toPosition: Long): ByteArray {
        // Make HTTP GET with Range header
    }
}
```

## Archive Validation

The `validateArchive` Gradle task verifies that the library's parsed byte offsets are correct by comparing CRC32 checksums against ground truth from the `7z` CLI.

### Prerequisites

- `7z` (p7zip) must be installed and on `PATH`
- `par2` (optional) -- used for PAR2 verify/repair if `.par2` files are present alongside the archive

### How it works

1. Discovers all sibling volumes from the given first-volume path
2. Runs PAR2 verify/repair if a `.par2` index file is found in the same directory
3. Runs `7z l -slt` to get ground-truth file CRCs
4. Parses the archive with the library to obtain byte offsets
5. Reads raw data from those offsets, computes CRC32, and compares against ground truth
6. Checks that all files reported by `7z` were also found by the library
7. Reports PASS/FAIL/SKIP per file (compressed files are skipped)

### Usage

```bash
# Single RAR5 archive
./gradlew validateArchive -ParchivePath=path/to/archive.rar

# Multi-volume RAR5
./gradlew validateArchive -ParchivePath=path/to/archive.part1.rar

# Single 7z archive
./gradlew validateArchive -ParchivePath=path/to/archive.7z

# Split 7z
./gradlew validateArchive -ParchivePath=path/to/archive.7z.001
```

The task exits with code 1 if any file fails validation.

## Key Classes

| Class | Description |
|---|---|
| `ArchiveService` | Unified entry point -- auto-detects format and dispatches to the appropriate parser |
| `RarArchiveService` | Parses RAR4/RAR5 archives and returns file metadata |
| `SevenZipArchiveService` | Parses 7zip archives and returns file metadata |
| `ArchiveTypeDetector` | Detects archive format from magic bytes |
| `VolumeMetaData` | Describes an archive volume (filename, size, optional first-16KB for PAR2 resolution) |
| `RarFileEntry` | Metadata for a file in a RAR archive |
| `SevenZipFileEntry` | Metadata for a file in a 7zip archive |
| `SplitInfo` | Byte position info for split/multi-volume files |
| `SeekableInputStream` | Interface for streams supporting random access |
