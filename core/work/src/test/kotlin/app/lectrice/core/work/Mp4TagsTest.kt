package app.lectrice.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class Mp4TagsTest {
    private fun box(type: String, body: ByteArray) = java.nio.ByteBuffer.allocate(body.size + 8).putInt(body.size + 8).put(type.toByteArray()).put(body).array()

    @Test
    fun moovMovesToTheEndWithChaptersAndTheOldOneIsFree() {
        val mdat = box("mdat", ByteArray(100) { 7 })
        val moov = box("moov", box("mvhd", ByteArray(20)) + box("udta", ByteArray(4)))
        val file = File.createTempFile("tags", ".m4b")
        file.writeBytes(box("ftyp", "M4A ".toByteArray()) + mdat + moov)

        Mp4Tags.write(file, "The Long Book", "Lectrice", listOf(Chapter("Opening", 0), Chapter("Rivers", 61_000)))

        RandomAccessFile(file, "r").use { raf ->
            val (offset, size) = Mp4Tags.findTopLevel(raf, "moov")!!
            assertEquals(raf.length(), offset + size)
            assertTrue(Mp4Tags.findTopLevel(raf, "free") != null)
            val newMoov = ByteArray(size.toInt()).also { raf.seek(offset); raf.readFully(it) }
            val text = String(newMoov, Charsets.ISO_8859_1)
            assertEquals(1, Regex("udta").findAll(text).count())
            assertTrue(text.contains("chpl") && text.contains("Rivers") && text.contains("mvhd"))
        }
        val mdatAt = RandomAccessFile(file, "r").use { Mp4Tags.findTopLevel(it, "mdat")!!.first }
        assertEquals(12L, mdatAt)
        file.delete()
    }
}
