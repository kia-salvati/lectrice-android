package app.lectrice.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class CappedCopyTest {
    /** A source that never ends, like a server that keeps sending. */
    private class Endless : InputStream() {
        override fun read(): Int = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
    }

    @Test
    fun `a stream within the cap is copied whole`() {
        val bytes = ByteArray(200_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(200_000L, CappedCopy.copy(ByteArrayInputStream(bytes), out, maxBytes = 200_000))
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }

    @Test
    fun `one byte over the cap stops the copy`() {
        val out = ByteArrayOutputStream()
        val error = assertThrows(TooLargeException::class.java) { CappedCopy.copy(ByteArrayInputStream(ByteArray(200_001)), out, maxBytes = 200_000) }
        assertEquals(200_000L, error.maxBytes)
        assertTrue(out.size() <= 200_000)
    }

    @Test
    fun `an endless stream stops at the cap`() {
        val out = ByteArrayOutputStream()
        assertThrows(TooLargeException::class.java) { CappedCopy.copy(Endless(), out, maxBytes = 1_000_000) }
        assertTrue(out.size() <= 1_000_000)
    }

    @Test
    fun `progress reports the running total`() {
        val seen = mutableListOf<Long>()
        CappedCopy.copy(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), maxBytes = 100, onProgress = seen::add)
        assertEquals(listOf(100L), seen)
    }
}
