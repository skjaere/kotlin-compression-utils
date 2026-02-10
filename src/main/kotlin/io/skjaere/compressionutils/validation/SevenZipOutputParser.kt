package io.skjaere.compressionutils.validation

/**
 * Entry parsed from `7z l -slt` output.
 */
data class SevenZipCliEntry(
    val path: String,
    val size: Long,
    val crc: String,
    val isFolder: Boolean,
    val method: String?
)

/**
 * Parses the output of `7z l -slt <archive>` to extract file metadata including CRCs.
 */
object SevenZipOutputParser {

    fun parse(output: String): List<SevenZipCliEntry> {
        val entries = mutableListOf<SevenZipCliEntry>()

        // Find the separator line that precedes file entries
        val lines = output.lines()
        val separatorIndex = lines.indexOfFirst { it.startsWith("----------") }
        if (separatorIndex == -1) return entries

        // Parse blocks separated by blank lines after the separator
        val entryLines = lines.subList(separatorIndex + 1, lines.size)
        val blocks = splitIntoBlocks(entryLines)

        for (block in blocks) {
            val props = parseBlock(block)
            val path = props["Path"] ?: continue
            val sizeStr = props["Size"] ?: continue
            val size = sizeStr.toLongOrNull() ?: continue

            val crc = props["CRC"] ?: ""
            val method = props["Method"]

            val isFolder = when {
                props.containsKey("Folder") -> props["Folder"] == "+"
                props.containsKey("Attributes") -> props["Attributes"]?.startsWith("D") == true
                else -> false
            }

            entries.add(SevenZipCliEntry(path, size, crc.uppercase(), isFolder, method))
        }

        return entries
    }

    private fun splitIntoBlocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()

        for (line in lines) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) {
            blocks.add(current)
        }

        return blocks
    }

    private fun parseBlock(lines: List<String>): Map<String, String> {
        val props = mutableMapOf<String, String>()
        for (line in lines) {
            val eqIndex = line.indexOf(" = ")
            if (eqIndex != -1) {
                val key = line.substring(0, eqIndex).trim()
                val value = line.substring(eqIndex + 3).trim()
                props[key] = value
            }
        }
        return props
    }
}
