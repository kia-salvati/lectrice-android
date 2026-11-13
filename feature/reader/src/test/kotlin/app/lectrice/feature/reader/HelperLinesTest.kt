package app.lectrice.feature.reader

import app.lectrice.core.model.HelperAttention
import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.PairedComputer
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperLinesTest {
    private val laptop = PairedComputer("0123456789abcdef", "Laptop", null, null, 1, null)
    private val helper = HelperState(enabled = true, computers = listOf(laptop), active = laptop)
    private val playing = PlaybackState(readyAheadMs = 12 * 60_000L + 59_000)

    @Test
    fun linesForEveryState() {
        assertEquals("Making audio on Laptop · 12 min ready", HelperLines.of(helper.copy(link = HelperLink.Connected), playing)?.text)
        assertEquals("Laptop is busy · 12 min ready", HelperLines.of(helper.copy(link = HelperLink.Busy), playing)?.text)
        assertEquals("Looking for Laptop…", HelperLines.of(helper.copy(link = HelperLink.Searching), playing)?.text)
        assertEquals("Computer not reachable · 12 min ready", HelperLines.of(helper.copy(link = HelperLink.Lost), playing)?.text)
        val attention = HelperLines.of(helper.copy(link = HelperLink.NeedsAttention(HelperAttention.NotPaired)), playing)!!
        assertTrue(attention.needsAttention)
        assertFalse(attention.pillMark)
        assertNull(HelperLines.of(helper.copy(link = HelperLink.Off), playing))
        assertNull(HelperLines.of(helper.copy(enabled = false, link = HelperLink.Connected), playing))
    }

    @Test
    fun onlyAComputerMakingAudioMarksThePill() {
        assertTrue(HelperLines.of(helper.copy(link = HelperLink.Connected), playing)!!.pillMark)
        assertTrue(HelperLines.of(helper.copy(link = HelperLink.Busy), playing)!!.pillMark)
        assertFalse(HelperLines.of(helper.copy(link = HelperLink.Lost), playing)!!.pillMark)
    }

    @Test
    fun minutesAreListeningMinutesAtTheSpeed() {
        assertEquals("30 min ready", HelperLines.minutesReady(60 * 60_000L, 2f))
        assertEquals("under a minute ready", HelperLines.minutesReady(59_000, 1f))
        assertEquals("10 min ready", HelperLines.minutesReady(PlaybackSpeed.DEFAULT.let { 10 * 60_000L }, PlaybackSpeed.DEFAULT.rate))
    }
}
