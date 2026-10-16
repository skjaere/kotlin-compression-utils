package io.skjaere.compressionutils

import java.io.IOException

/**
 * The volumes of an archive set can't be read in one order, whole, as their headers number them
 * (see [ArchiveService.resolveVolumes]): a RAR5 volume's main archive header gives its place in its
 * set ([Rar5Parser.volumeNumber]), whatever name it was posted under.
 */
sealed class ArchiveVolumesException(message: String) : IOException(message)

/**
 * The set of [volumes] lacks a volume: none of them gives place [number] (0 for the first volume)
 * though one gives a later place. A missing last volume isn't seen this way.
 */
class MissingArchiveVolumeException(val number: Int, val volumes: List<String>, message: String) :
    ArchiveVolumesException(message)

/** Two or more of [volumes] give the same place [number] in their set: which goes where can't be told. */
class ConflictingArchiveVolumesException(val number: Int, val volumes: List<String>, message: String) :
    ArchiveVolumesException(message)
