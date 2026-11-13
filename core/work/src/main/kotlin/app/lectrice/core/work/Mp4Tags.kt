package app.lectrice.core.work

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** A chapter start on the file's timeline. */
data class Chapter(val title: String, val startMs: Long)

/**
 * Adds the audiobook tags MediaMuxer can't write: Nero chapter marks (`moov/udta/chpl`, read by most audiobook players)
 * and the title, artist and genre (`moov/udta/meta/ilst`). The new `moov` is appended at the end of the file and the old
 * one becomes a `free` box, so no sample offset (`stco`) changes. Pure JVM; tested without a phone.
 */
object Mp4Tags {
    private const val HEADER = 8
    private const val HUNDRED_NS_PER_MS = 10_000L
    private const val MAX_TITLE_BYTES = 255
    private const val MAX_CHAPTERS = 255
    private const val GENRE = "Audiobook"

    fun write(file: File, title: String, artist: String, chapters: List<Chapter>) {
        RandomAccessFile(file, "rw").use { raf ->
            val (moovOffset, moovSize) = findTopLevel(raf, "moov") ?: error("no moov box")
            val moov = ByteArray(moovSize.toInt())
            raf.seek(moovOffset)
            raf.readFully(moov)
            val children = moov.copyOfRange(HEADER, moov.size).let { stripUdta(it) }
            val udta = box("udta", chpl(chapters) + meta(title, artist))
            val newMoov = box("moov", children + udta)
            raf.seek(moovOffset + 4)
            raf.write("free".toByteArray(Charsets.US_ASCII))
            raf.seek(raf.length())
            raf.write(newMoov)
        }
    }

    /** Offset and size of the first top-level box of [type]. */
    fun findTopLevel(raf: RandomAccessFile, type: String): Pair<Long, Long>? {
        var offset = 0L
        while (offset + HEADER <= raf.length()) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and 0xffffffffL
            val name = ByteArray(4).also(raf::readFully).toString(Charsets.US_ASCII)
            if (size == 1L) size = raf.readLong()
            if (size == 0L) size = raf.length() - offset
            if (name == type) return offset to size
            if (size < HEADER) return null
            offset += size
        }
        return null
    }

    /** Drops an existing `udta` child so ours is the only one. */
    private fun stripUdta(children: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset + HEADER <= children.size) {
            val size = readInt(children, offset)
            val name = String(children, offset + 4, 4, Charsets.US_ASCII)
            if (size < HEADER || offset + size > children.size) break
            if (name != "udta") out.write(children, offset, size)
            offset += size
        }
        return out.toByteArray()
    }

    /** ffmpeg's layout: version 1, a reserved word, a count byte, then (start in 100 ns, title length, title) per mark. */
    private fun chpl(chapters: List<Chapter>): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { data ->
            data.writeInt(0x01000000)
            data.writeInt(0)
            val marks = chapters.take(MAX_CHAPTERS)
            data.writeByte(marks.size)
            marks.forEach { mark ->
                data.writeLong(mark.startMs * HUNDRED_NS_PER_MS)
                val title = utf8Prefix(mark.title, MAX_TITLE_BYTES)
                data.writeByte(title.size)
                data.write(title)
            }
        }
        return box("chpl", body.toByteArray())
    }

    private fun meta(title: String, artist: String): ByteArray {
        val hdlr = box("hdlr", ByteArray(8) + "mdir".toByteArray(Charsets.US_ASCII) + "appl".toByteArray(Charsets.US_ASCII) + ByteArray(9))
        val ilst = box("ilst", text("©nam", title) + text("©ART", artist) + text("©alb", title) + text("©gen", GENRE))
        return box("meta", ByteArray(4) + hdlr + ilst)
    }

    /** An iTunes text item: `<type>` holding a `data` box of type 1 (UTF-8). */
    private fun text(type: String, value: String): ByteArray {
        val data = box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + value.toByteArray(Charsets.UTF_8))
        return box(type, data)
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + HEADER)
        DataOutputStream(out).use { data ->
            data.writeInt(body.size + HEADER)
            data.write(type.toByteArray(Charsets.ISO_8859_1))
            data.write(body)
        }
        return out.toByteArray()
    }

    /** At most [max] bytes of UTF-8, never cutting a character in half. */
    private fun utf8Prefix(text: String, max: Int): ByteArray {
        var end = text.length
        while (text.substring(0, end).toByteArray(Charsets.UTF_8).size > max) end--
        return text.substring(0, end).toByteArray(Charsets.UTF_8)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
}
