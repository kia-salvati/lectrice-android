package app.lectrice.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B2: short sheets were drawn below the screen (anchors counted twice) and a cut-short opening left the scrim behind. */
class BottomSheetTest {
    private val container = 2000f

    @Test
    fun shortSheetShowsItsWholeHeightAboveTheBottom() {
        val anchors = sheetAnchors(container, sheetHeight = 400f, halfDetent = false, halfPx = null)
        assertEquals(1600f, anchors.positionOf(SheetDetent.Full))
        assertEquals(container, anchors.positionOf(SheetDetent.Hidden))
        assertFalse(anchors.hasPositionFor(SheetDetent.Half))
    }

    @Test
    fun halfStopIsMeasuredFromTheBottomAndNeverAboveFull() {
        val anchors = sheetAnchors(container, sheetHeight = 1800f, halfDetent = true, halfPx = 700f)
        assertEquals(200f, anchors.positionOf(SheetDetent.Full))
        assertEquals(1300f, anchors.positionOf(SheetDetent.Half))
        val short = sheetAnchors(container, sheetHeight = 500f, halfDetent = true, halfPx = 700f)
        assertEquals(short.positionOf(SheetDetent.Full), short.positionOf(SheetDetent.Half))
    }

    @Test
    fun closedOnlyOnceTheClosingSlideHasFinished() {
        assertFalse(isClosed(SheetDetent.Hidden, SheetDetent.Hidden, animating = true))
        assertFalse(isClosed(SheetDetent.Full, SheetDetent.Hidden, animating = true))
        assertFalse(isClosed(SheetDetent.Hidden, SheetDetent.Full, animating = false))
        assertTrue(isClosed(SheetDetent.Hidden, SheetDetent.Hidden, animating = false))
    }
}
