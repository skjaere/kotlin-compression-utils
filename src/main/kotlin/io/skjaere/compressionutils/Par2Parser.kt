package io.skjaere.compressionutils

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for PAR2 (Parity Archive Volume Set 2.0) files.
 * Extracts file descriptions including original filenames from PAR2 files.
 */
object Par2Parser {

    // PAR2 magic sequence: "PAR2\0PKT"
    private val MAGIC = byteArrayOf(
        'P'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), '2'.code.toByte(),
        0x00, 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte()
    )

    // File Description packet type: "PAR 2.0\0FileDesc"
    private val FILE_DESC_TYPE = byteArrayOf(
        'P'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), ' '.code.toByte(),
        '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(), 0x00,
        'F'.code.toByte(), 'i'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        'D'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 'c'.code.toByte()
    )

    // Main packet type: "PAR 2.0\0Main\0\0\0\0"
    private val MAIN_TYPE = byteArrayOf(
        'P'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), ' '.code.toByte(),
        '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(), 0x00,
        'M'.code.toByte(), 'a'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(),
        0x00, 0x00, 0x00, 0x00
    )

    private const val HEADER_SIZE = 64L // Magic(8) + Length(8) + Hash(16) + SetID(16) + Type(16)
    private const val FILE_DESC_BODY_FIXED_SIZE = 56 // FileID(16) + Hash(16) + Hash16k(16) + Length(8)

    /**
     * Describes a file entry found in a PAR2 file.
     */
    data class Par2FileDescription(
        /** Unique identifier for this file (MD5 of hash16k + length + filename) */
        val fileId: ByteArray,
        /** MD5 hash of the complete file */
        val fileHash: ByteArray,
        /** MD5 hash of the first 16KB of the file */
        val hash16k: ByteArray,
        /** File size in bytes */
        val fileSize: Long,
        /** Original filename */
        val filename: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Par2FileDescription) return false
            return fileId.contentEquals(other.fileId)
        }

        override fun hashCode(): Int = fileId.contentHashCode()

        /** Returns the file ID as a hex string */
        fun fileIdHex(): String = fileId.joinToString("") { "%02x".format(it) }

        /** Returns the file hash as a hex string */
        fun fileHashHex(): String = fileHash.joinToString("") { "%02x".format(it) }

        /** Returns the hash16k as a hex string */
        fun hash16kHex(): String = hash16k.joinToString("") { "%02x".format(it) }
    }

    /**
     * Result of parsing a PAR2 file.
     */
    data class Par2Info(
        /** Recovery set ID (identifies the set this PAR2 belongs to) */
        val recoverySetId: ByteArray,
        /** List of file descriptions found */
        val files: List<Par2FileDescription>
    ) {
        /** Returns the recovery set ID as a hex string */
        fun recoverySetIdHex(): String = recoverySetId.joinToString("") { "%02x".format(it) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Par2Info) return false
            return recoverySetId.contentEquals(other.recoverySetId)
        }

        override fun hashCode(): Int = recoverySetId.contentHashCode()
    }

    /**
     * Parses a PAR2 file and extracts file descriptions.
     *
     * @param input InputStream containing PAR2 data
     * @return Par2Info containing recovery set ID and file descriptions
     * @throws Par2ParseException if the file is not a valid PAR2 file
     */
    fun parse(input: InputStream): Par2Info {
        val files = mutableListOf<Par2FileDescription>()
        var recoverySetId: ByteArray? = null

        while (true) {
            // Read packet header, handling possible padding bytes between packets
            val header = ByteArray(HEADER_SIZE.toInt())
            var headerRead = 0
            var paddingSkipped = 0

            // Try to find the PAR2 magic, skipping up to 3 padding bytes
            while (paddingSkipped <= 3) {
                val firstByte = input.read()
                if (firstByte == -1) {
                    // End of file
                    return Par2Info(
                        recoverySetId = recoverySetId ?: ByteArray(16),
                        files = files
                    )
                }

                if (firstByte == 0x50) { // 'P' - potential start of PAR2 magic
                    header[0] = firstByte.toByte()
                    headerRead = input.readNBytes(header, 1, HEADER_SIZE.toInt() - 1) + 1
                    break
                } else {
                    // Skip this byte (padding)
                    paddingSkipped++
                }
            }

            if (headerRead == 0) break // Couldn't find magic after skipping padding
            if (headerRead < HEADER_SIZE) {
                throw Par2ParseException("Incomplete packet header: read $headerRead bytes, expected $HEADER_SIZE")
            }

            // Verify magic
            val readMagic = header.sliceArray(0 until 8)
            if (!readMagic.contentEquals(MAGIC)) {
                throw Par2ParseException("Invalid PAR2 magic sequence: ${readMagic.joinToString(" ") { "%02x".format(it) }}")
            }

            // Parse header fields
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(8)
            val packetLength = buffer.getLong()

            // Extract recovery set ID (first packet we see)
            if (recoverySetId == null) {
                recoverySetId = header.sliceArray(32 until 48)
            }

            // Get packet type
            val packetType = header.sliceArray(48 until 64)

            // Calculate body size
            val bodySize = (packetLength - HEADER_SIZE).toInt()
            if (bodySize < 0) {
                throw Par2ParseException("Invalid packet length: $packetLength")
            }

            // Read packet body
            val body = if (bodySize > 0) {
                val bodyBytes = ByteArray(bodySize)
                val bodyRead = input.readNBytes(bodyBytes, 0, bodySize)
                if (bodyRead < bodySize) {
                    throw Par2ParseException("Incomplete packet body: read $bodyRead bytes, expected $bodySize")
                }
                bodyBytes
            } else {
                ByteArray(0)
            }

            // Parse File Description packets
            if (packetType.contentEquals(FILE_DESC_TYPE)) {
                val fileDesc = parseFileDescription(body)
                if (fileDesc != null) {
                    files.add(fileDesc)
                }
            }
        }

        return Par2Info(
            recoverySetId = recoverySetId ?: ByteArray(16),
            files = files
        )
    }

    /**
     * Parses a PAR2 file from a byte array.
     */
    fun parse(data: ByteArray): Par2Info = parse(data.inputStream())

    /**
     * Parses only the file descriptions from a PAR2 stream, returning a map of
     * file hash (hex) to filename. Useful for mapping obscured filenames to original names.
     *
     * @param input InputStream containing PAR2 data
     * @return Map of MD5 hash (hex string) to original filename
     */
    fun extractFilenames(input: InputStream): Map<String, String> {
        return parse(input).files.associate { it.fileHashHex() to it.filename }
    }

    /**
     * Extracts a mapping from file size to filename. Useful when hash isn't available.
     *
     * @param input InputStream containing PAR2 data
     * @return Map of file size to original filename
     */
    fun extractFilenamesBySize(input: InputStream): Map<Long, String> {
        return parse(input).files.associate { it.fileSize to it.filename }
    }

    private fun parseFileDescription(body: ByteArray): Par2FileDescription? {
        if (body.size < FILE_DESC_BODY_FIXED_SIZE) {
            return null // Invalid file description packet
        }

        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)

        // Read fixed fields
        val fileId = ByteArray(16)
        buffer.get(fileId)

        val fileHash = ByteArray(16)
        buffer.get(fileHash)

        val hash16k = ByteArray(16)
        buffer.get(hash16k)

        val fileSize = buffer.getLong()

        // Read filename (remaining bytes, may have null padding for alignment)
        val filenameBytes = ByteArray(body.size - FILE_DESC_BODY_FIXED_SIZE)
        buffer.get(filenameBytes)

        // Trim null padding from filename
        val filenameLength = filenameBytes.indexOfFirst { it == 0.toByte() }
            .let { if (it == -1) filenameBytes.size else it }
        val filename = String(filenameBytes, 0, filenameLength, Charsets.UTF_8)

        return Par2FileDescription(
            fileId = fileId,
            fileHash = fileHash,
            hash16k = hash16k,
            fileSize = fileSize,
            filename = filename
        )
    }
}

/**
 * Exception thrown when PAR2 parsing fails.
 */
class Par2ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
