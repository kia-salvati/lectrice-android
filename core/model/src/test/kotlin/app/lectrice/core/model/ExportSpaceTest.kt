package app.lectrice.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportSpaceTest {
    @Test
    fun theWorkedExampleOfFile10() {
        val seconds = ExportSpace.outputSeconds((8 * 3600 + 10 * 60) * 1000L, PlaybackSpeed.X1)
        val final = ExportSpace.finalBytes(seconds)
        assertEquals(242_256_000L, final)
        assertEquals(100_000_000L, ExportSpace.margin(final))
        assertEquals(584_512_000L, ExportSpace.neededNew(final))
        assertEquals(235_200_000L, ExportSpace.shownBytes(seconds))
    }

    @Test
    fun resumeFormulas() {
        assertEquals(1_000 + 1_000 + 100_000_000L - 400, ExportSpace.neededResumeParts(1_000, 400))
        assertEquals(1_000 + 100_000_000L, ExportSpace.neededResumeAssemble(1_000))
    }

    @Test
    fun suggestionStopsAtTheLastChapterThatFits() {
        val hour = 3_600_000L
        val chapters = listOf(hour, hour, hour, hour)
        val oneHour = ExportSpace.neededNew(ExportSpace.finalBytes(ExportSpace.outputSeconds(hour, PlaybackSpeed.X1)))
        assertNull(ExportSpace.suggestEnd(chapters, 1, PlaybackSpeed.X1, oneHour - 1))
        assertEquals(1, ExportSpace.suggestEnd(chapters, 1, PlaybackSpeed.X1, oneHour))
        assertEquals(3, ExportSpace.suggestEnd(chapters, 1, PlaybackSpeed.X1, Long.MAX_VALUE))
    }

    @Test
    fun makeTimes() {
        assertEquals("a few minutes", ExportSpace.formatMakeTime(200.0))
        assertEquals("about 40 min", ExportSpace.formatMakeTime(41 * 60.0))
        assertEquals("about 2 h", ExportSpace.formatMakeTime(121 * 60.0))
        assertEquals("about 2 h 10 min", ExportSpace.formatMakeTime(128 * 60.0))
    }
}
