package app.lectrice.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

/**
 * Coordinates kept in state can outlive their node (the pill's ⏯ goes away when the pill collapses); any position call
 * on a detached node throws. These helpers answer null / false instead.
 */
internal fun LayoutCoordinates?.attachedOrNull(): LayoutCoordinates? = this?.takeIf { it.isAttached }

internal fun LayoutCoordinates?.boundsInRootOrNull(): Rect? = attachedOrNull()?.boundsInRoot()

/** Whether [point] (in [container]'s space) is on [child]; false when either is gone. */
internal fun isInside(container: LayoutCoordinates?, child: LayoutCoordinates?, point: Offset): Boolean {
    val parent = container.attachedOrNull() ?: return false
    val inner = child.attachedOrNull() ?: return false
    return parent.localBoundingBoxOf(inner, clipBounds = false).contains(point)
}

/** The pill's and the ⏯'s root rects for the reader; collapsed, the whole pill is the ⏯. Null while a node is gone. */
internal fun pillBounds(pill: LayoutCoordinates?, play: LayoutCoordinates?, collapsed: Boolean): Pair<Rect, Rect>? {
    val pillRect = pill.boundsInRootOrNull() ?: return null
    if (collapsed) return pillRect to pillRect
    val playRect = play.boundsInRootOrNull() ?: return null
    return pillRect to playRect
}
