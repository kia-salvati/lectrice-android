package app.lectrice.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernorAndWindowTest {
    private val base = GovernorInputs(
        hasBook = true, playing = true, pausedSinceMs = null, sleepTimerFired = false, lowMemoryWhileStopped = false,
        heat = Heat.None, batterySaver = false, batteryLowNotCharging = false, nowMs = 1_000_000,
    )

    private fun mode(input: GovernorInputs) = ReadAheadGovernor.decide(input).mode

    @Test
    fun governorTable() {
        assertEquals(AheadMode.Full, mode(base))
        assertEquals(AheadMode.Off, mode(base.copy(hasBook = false)))
        assertEquals(AheadMode.Off, mode(base.copy(sleepTimerFired = true)))
        assertEquals(AheadMode.Full, mode(base.copy(playing = false, pausedSinceMs = base.nowMs - 59_000)))
        assertEquals(AheadMode.Off, mode(base.copy(playing = false, pausedSinceMs = base.nowMs - 60_000)))
        assertEquals(AheadMode.Reduced, mode(base.copy(batterySaver = true)))
        assertEquals(AheadMode.Off, mode(base.copy(batterySaver = true, playing = false, pausedSinceMs = base.nowMs)))
        assertEquals(AheadMode.Reduced, mode(base.copy(heat = Heat.Severe)))
        assertEquals(AheadMode.Off, mode(base.copy(heat = Heat.Critical)))
        assertEquals(AheadMode.Off, mode(base.copy(lowMemoryWhileStopped = true)))
    }

    /** File 24 §3.10: battery saver and heat reduce local synthesis but the computer keeps filling; Off stops it. */
    @Test
    fun helperRunsInFullAndReducedOnly() {
        assertTrue(ReadAheadGovernor.helperRuns(mode(base)))
        assertTrue(ReadAheadGovernor.helperRuns(mode(base.copy(batterySaver = true))))
        assertTrue(ReadAheadGovernor.helperRuns(mode(base.copy(heat = Heat.Severe))))
        assertEquals(false, ReadAheadGovernor.helperRuns(mode(base.copy(playing = false, pausedSinceMs = base.nowMs - 60_000))))
        assertEquals(false, ReadAheadGovernor.helperRuns(mode(base.copy(heat = Heat.Critical))))
        assertEquals(false, ReadAheadGovernor.helperRuns(mode(base.copy(sleepTimerFired = true))))
    }

    @Test
    fun heatEpisodesLastTwoCoolMinutes() {
        val now = 10_000_000L
        assertEquals(Heat.Critical, ReadAheadGovernor.nextHeat(Heat.None, 4, null, now))
        assertEquals(Heat.Critical, ReadAheadGovernor.nextHeat(Heat.Critical, 3, null, now))
        assertEquals(Heat.Severe, ReadAheadGovernor.nextHeat(Heat.Severe, 2, now - 119_000, now))
        assertEquals(Heat.None, ReadAheadGovernor.nextHeat(Heat.Severe, 2, now - 120_000, now))
    }

    @Test
    fun windowGrowsAndDrops() {
        val window = PlaylistWindow(orderSize = 1000)
        assertEquals(50, window.rebuildAround(100))
        assertEquals(50 to 250, window.start to window.end)
        assertTrue(window.advance(150).isEmpty)
        val change = window.advance(201)
        assertEquals(PlaylistWindow.Change(appendFrom = 250, appendCount = 200, dropFromFront = 101), change)
        assertEquals(151 to 450, window.start to window.end)
    }

    @Test
    fun windowStopsAtTheEnds() {
        val window = PlaylistWindow(orderSize = 120)
        assertEquals(10, window.rebuildAround(10))
        assertEquals(0 to 120, window.start to window.end)
        assertTrue(window.advance(100).appendCount == 0)
    }
}
