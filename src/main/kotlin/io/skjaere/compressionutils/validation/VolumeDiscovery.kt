package io.skjaere.compressionutils.validation

import java.io.File

/**
 * Discovers all sibling volumes given the first volume of a multi-part archive.
 */
object VolumeDiscovery {

    private val MODERN_RAR_REGEX = Regex("""(?i)(.+)\.part(\d+)\.rar$""")
    private val SPLIT_7Z_REGEX = Regex("""(?i)(.+\.7z)\.(\d{3})$""")

    fun discoverVolumes(firstVolume: File): List<File> {
        val name = firstVolume.name
        val dir = firstVolume.parentFile ?: File(".")

        // Modern RAR: name.part01.rar, name.part02.rar, ...
        MODERN_RAR_REGEX.matchEntire(name)?.let { match ->
            val baseName = match.groupValues[1]
            val pattern = Regex("""(?i)\Q$baseName\E\.part(\d+)\.rar$""")
            return dir.listFiles()
                ?.filter { pattern.matches(it.name) }
                ?.sortedBy { pattern.matchEntire(it.name)!!.groupValues[1].toInt() }
                ?: listOf(firstVolume)
        }

        // Old-style RAR: name.rar, name.r00..r99, name.s00..s99, name.t00..t99, ...
        if (name.lowercase().endsWith(".rar")) {
            val baseName = name.substring(0, name.length - 4)
            val extPattern = Regex("""(?i)\Q$baseName\E\.([a-z])(\d{2,3})$""")
            val extensions = dir.listFiles()
                ?.filter { extPattern.matches(it.name) }
                ?.sortedWith(compareBy {
                    val match = extPattern.matchEntire(it.name)!!
                    val letter = match.groupValues[1].lowercase()[0]
                    val num = match.groupValues[2].toInt()
                    (letter - 'r') * 1000 + num
                })
                ?: emptyList()
            if (extensions.isNotEmpty()) {
                return listOf(firstVolume) + extensions
            }
        }

        // Split 7z: name.7z.001, name.7z.002, ...
        SPLIT_7Z_REGEX.matchEntire(name)?.let { match ->
            val baseName = match.groupValues[1]
            val pattern = Regex("""(?i)\Q$baseName\E\.(\d{3})$""")
            return dir.listFiles()
                ?.filter { pattern.matches(it.name) }
                ?.sortedBy { pattern.matchEntire(it.name)!!.groupValues[1].toInt() }
                ?: listOf(firstVolume)
        }

        // Single file fallback
        return listOf(firstVolume)
    }
}
