package app.lectrice.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun percentRead() {
        assertNull(Progress.percentRead(null, 10))
        assertEquals(10, Progress.percentRead(0, 10))
        assertEquals(100, Progress.percentRead(12, 10))
    }

    @Test
    fun timeLeft() {
        assertEquals("under a minute left", Progress.formatTimeLeft(30.0))
        assertEquals("12 min left", Progress.formatTimeLeft(12 * 60.0))
        assertEquals("3 h 5 min left", Progress.formatTimeLeft(185 * 60.0))
        assertEquals("8 h 10 min", Progress.formatDuration(490 * 60.0))
        assertEquals("2 h", Progress.formatDuration(120 * 60.0))
    }

    @Test
    fun relativeTime() {
        val now = 1_800_000_000L
        assertEquals("just now", Progress.formatRelativeTime(now - 30, now))
        assertEquals("2 hours ago", Progress.formatRelativeTime(now - 2 * 3600 - 5, now))
        assertEquals("yesterday", Progress.formatRelativeTime(now - 25 * 3600, now))
        assertEquals("last week", Progress.formatRelativeTime(now - 8 * 86_400, now))
        assertEquals("1 minute ago", Progress.formatRelativeTime(now - 61, now))
    }

    @Test
    fun speeds() {
        assertEquals(listOf("0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"), PlaybackSpeed.entries.map { it.label })
    }
}
