package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class VolumeMetaDataTest {

    @Test
    fun `properties are set correctly`() {
        val data = byteArrayOf(1, 2, 3)
        val meta = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = data)

        assertEquals("test.rar", meta.filename)
        assertEquals(1024, meta.size)
        assertEquals(data, meta.first16kb)
    }

    @Test
    fun `first16kb defaults to null`() {
        val meta = VolumeMetaData(filename = "test.rar", size = 1024)
        assertNull(meta.first16kb)
    }

    @Test
    fun `equality works with same ByteArray content`() {
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(1, 2, 3)
        val meta1 = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = data1)
        val meta2 = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = data2)

        assertEquals(meta1, meta2)
        assertEquals(meta1.hashCode(), meta2.hashCode())
    }

    @Test
    fun `equality works with different ByteArray content`() {
        val meta1 = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = byteArrayOf(1, 2, 3))
        val meta2 = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = byteArrayOf(4, 5, 6))

        assertNotEquals(meta1, meta2)
    }

    @Test
    fun `equality works with null first16kb`() {
        val meta1 = VolumeMetaData(filename = "test.rar", size = 1024)
        val meta2 = VolumeMetaData(filename = "test.rar", size = 1024)

        assertEquals(meta1, meta2)
        assertEquals(meta1.hashCode(), meta2.hashCode())
    }

    @Test
    fun `equality fails with different filenames`() {
        val meta1 = VolumeMetaData(filename = "a.rar", size = 1024)
        val meta2 = VolumeMetaData(filename = "b.rar", size = 1024)

        assertNotEquals(meta1, meta2)
    }

    @Test
    fun `equality fails with different sizes`() {
        val meta1 = VolumeMetaData(filename = "test.rar", size = 1024)
        val meta2 = VolumeMetaData(filename = "test.rar", size = 2048)

        assertNotEquals(meta1, meta2)
    }

    @Test
    fun `null vs non-null first16kb are not equal`() {
        val meta1 = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = null)
        val meta2 = VolumeMetaData(filename = "test.rar", size = 1024, first16kb = byteArrayOf(1))

        assertNotEquals(meta1, meta2)
    }
}
