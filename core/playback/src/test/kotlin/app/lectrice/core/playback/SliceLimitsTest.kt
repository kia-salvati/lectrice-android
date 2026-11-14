package app.lectrice.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceLimitsTest {
    @Test
    fun `a normal sentence keeps its times`() {
        assertEquals(1_200L..4_800L, SliceLimits.range(1_200, 4_800))
    }

    @Test
    fun `times that go back give an empty slice`() {
        assertEquals(5_000L..5_000L, SliceLimits.range(5_000, 1_000))
    }

    @Test
    fun `a slice is never longer than the cap or before the start of the file`() {
        assertEquals(0L..SliceLimits.MAX_SLICE_MS, SliceLimits.range(-50, Long.MAX_VALUE))
        val late = Long.MAX_VALUE / 1_000_000
        assertEquals(SliceLimits.MAX_SLICE_MS, SliceLimits.range(late, Long.MAX_VALUE).let { it.last - it.first })
    }

    @Test
    fun `a format no audiobook has is refused`() {
        assertTrue(SliceLimits.formatOk(24_000, 1))
        assertTrue(SliceLimits.formatOk(48_000, 2))
        assertFalse(SliceLimits.formatOk(0, 1))
        assertFalse(SliceLimits.formatOk(24_000, 0))
        assertFalse(SliceLimits.formatOk(Int.MAX_VALUE, 2))
        assertFalse(SliceLimits.formatOk(44_100, 4_096))
    }
}
