package app.lectrice.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1: the pill kept the ⏯'s coordinates after the button left the composition; using them crashed the app. */
class LayoutGuardsTest {
    private val root = FakeCoordinates(Rect(0f, 0f, 400f, 800f), parent = null)
    private val pill = FakeCoordinates(Rect(12f, 700f, 388f, 756f), parent = root)
    private val play = FakeCoordinates(Rect(60f, 704f, 108f, 752f), parent = pill)
    private val gone = FakeCoordinates(Rect(60f, 704f, 108f, 752f), parent = pill, attached = false)

    @Test
    fun detachedPlayButtonGivesNoBoundsInsteadOfThrowing() {
        assertNull(gone.boundsInRootOrNull())
        assertNull(pillBounds(pill, gone, collapsed = false))
        assertFalse(isInside(pill, gone, Offset(70f, 20f)))
        assertNull(gone.attachedOrNull())
    }

    @Test
    fun collapsedPillReportsItselfAsThePlayButton() {
        assertEquals(pill.rect to pill.rect, pillBounds(pill, gone, collapsed = true))
        assertEquals(pill.rect to pill.rect, pillBounds(pill, null, collapsed = true))
    }

    @Test
    fun attachedNodesReportTheirRootBounds() {
        assertEquals(pill.rect to play.rect, pillBounds(pill, play, collapsed = false))
        assertTrue(isInside(pill, play, Offset(70f, 10f)))
        assertFalse(isInside(pill, play, Offset(300f, 10f)))
    }

    @Test
    fun detachedPillGivesNothing() {
        val detachedPill = FakeCoordinates(pill.rect, parent = root, attached = false)
        assertNull(pillBounds(detachedPill, play, collapsed = true))
        assertFalse(isInside(detachedPill, play, Offset(70f, 10f)))
    }

    /** Positions in root space (the points given to [isInside] are in the container's space); like the real thing, every position call on a detached node throws. */
    private class FakeCoordinates(val rect: Rect, private val parent: FakeCoordinates?, private val attached: Boolean = true) : LayoutCoordinates {
        private fun check() = check(attached) { "LayoutCoordinate operations are only valid when isAttached is true" }

        override val size: IntSize get() = IntSize(rect.width.toInt(), rect.height.toInt())
        override val providedAlignmentLines: Set<AlignmentLine> get() = emptySet()
        override val parentLayoutCoordinates: LayoutCoordinates? get() = parent.also { check() }
        override val parentCoordinates: LayoutCoordinates? get() = parent.also { check() }
        override val isAttached: Boolean get() = attached

        override fun windowToLocal(relativeToWindow: Offset): Offset = relativeToWindow.also { check() } - rect.topLeft
        override fun localToWindow(relativeToLocal: Offset): Offset = relativeToLocal.also { check() } + rect.topLeft
        override fun localToRoot(relativeToLocal: Offset): Offset = relativeToLocal.also { check() } + rect.topLeft

        override fun localPositionOf(sourceCoordinates: LayoutCoordinates, relativeToSource: Offset): Offset {
            check()
            return (sourceCoordinates as FakeCoordinates).localToRoot(relativeToSource) - rect.topLeft
        }

        override fun localBoundingBoxOf(sourceCoordinates: LayoutCoordinates, clipBounds: Boolean): Rect {
            check()
            val source = sourceCoordinates as FakeCoordinates
            source.check()
            return source.rect.translate(-rect.topLeft)
        }

        override fun get(alignmentLine: AlignmentLine): Int = AlignmentLine.Unspecified
    }
}
