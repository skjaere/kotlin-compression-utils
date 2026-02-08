package io.skjaere.compressionutils.validation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SevenZipOutputParserTest {

    @Test
    fun `parses single file entry`() {
        val output = """
7-Zip 24.09 (x64) : Copyright (c) 1999-2024 Igor Pavlov

Scanning the drive for archives:
1 file, 224 bytes (1 KiB)

Listing archive: test.7z

--
Path = test.7z
Type = 7z
Physical Size = 224

----------
Path = testfile.txt
Size = 110
Packed Size = 110
Modified = 2025-01-15 10:30:00
Attributes = A
CRC = EDCC6BDB
Encrypted = -
Method = Copy
Block = 0
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)

        val entry = entries[0]
        assertEquals("testfile.txt", entry.path)
        assertEquals(110L, entry.size)
        assertEquals("EDCC6BDB", entry.crc)
        assertFalse(entry.isFolder)
        assertEquals("Copy", entry.method)
    }

    @Test
    fun `parses multiple file entries`() {
        val output = """
Listing archive: test-multifile.7z

----------

Path = testdir
Size = 0
Packed Size = 0
Modified = 2025-01-15 10:00:00
Attributes = D
CRC =
Method =
Block =

Path = testdir/file1.txt
Size = 19
Packed Size = 19
Modified = 2025-01-15 10:30:00
Attributes = A
CRC = FC3C4EF4
Encrypted = -
Method = Copy
Block = 0

Path = testdir/file2.txt
Size = 19
Packed Size = 19
Modified = 2025-01-15 10:30:01
Attributes = A
CRC = ED41248D
Encrypted = -
Method = Copy
Block = 0
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(3, entries.size)

        // Directory
        assertTrue(entries[0].isFolder)
        assertEquals("testdir", entries[0].path)
        assertEquals(0L, entries[0].size)

        // File 1
        assertFalse(entries[1].isFolder)
        assertEquals("testdir/file1.txt", entries[1].path)
        assertEquals(19L, entries[1].size)
        assertEquals("FC3C4EF4", entries[1].crc)

        // File 2
        assertEquals("testdir/file2.txt", entries[2].path)
        assertEquals("ED41248D", entries[2].crc)
    }

    @Test
    fun `detects folder via Folder field`() {
        val output = """
----------

Path = mydir
Size = 0
Packed Size = 0
Folder = +
Method =
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)
        assertTrue(entries[0].isFolder)
    }

    @Test
    fun `detects folder via D attribute when no Folder field`() {
        val output = """
----------

Path = somedir
Size = 0
Packed Size = 0
Attributes = D
CRC =
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)
        assertTrue(entries[0].isFolder)
    }

    @Test
    fun `non-folder with A attribute`() {
        val output = """
----------

Path = file.txt
Size = 100
Packed Size = 100
Attributes = A
CRC = AABBCCDD
Method = Copy
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)
        assertFalse(entries[0].isFolder)
    }

    @Test
    fun `empty CRC is preserved as empty string`() {
        val output = """
----------

Path = emptyfile.txt
Size = 0
Packed Size = 0
CRC =
Method =
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)
        assertEquals("", entries[0].crc)
    }

    @Test
    fun `CRC is uppercased`() {
        val output = """
----------

Path = file.txt
Size = 50
Packed Size = 50
CRC = abcdef01
Method = Copy
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals("ABCDEF01", entries[0].crc)
    }

    @Test
    fun `returns empty list when no separator found`() {
        val output = "Some random output without separator"
        val entries = SevenZipOutputParser.parse(output)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `skips blocks without Path`() {
        val output = """
----------

Size = 100
CRC = AABB
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `skips blocks without Size`() {
        val output = """
----------

Path = file.txt
CRC = AABB
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `method is null when not present`() {
        val output = """
----------

Path = file.txt
Size = 10
CRC = 12345678
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)
        assertEquals(null, entries[0].method)
    }

    @Test
    fun `parses RAR archive listing`() {
        val output = """
7-Zip 24.09 (x64) : Copyright (c) 1999-2024 Igor Pavlov

Scanning the drive for archives:
1 file, 188 bytes (1 KiB)

Listing archive: test-rar5.rar

--
Path = test-rar5.rar
Type = Rar5
Physical Size = 188
Solid = -
Blocks = 1

----------
Path = testfile.txt
Folder = -
Size = 110
Packed Size = 110
Modified = 2025-06-23 23:00:00.000000000
Created =
Accessed =
Attributes = .....A..
Encrypted = -
Solid = -
Comment =
CRC = EDCC6BDB
Host OS = Unix
Method = Store
Version = 50
        """.trimIndent()

        val entries = SevenZipOutputParser.parse(output)
        assertEquals(1, entries.size)
        assertEquals("testfile.txt", entries[0].path)
        assertEquals(110L, entries[0].size)
        assertEquals("EDCC6BDB", entries[0].crc)
        assertFalse(entries[0].isFolder)
        assertEquals("Store", entries[0].method)
    }
}
