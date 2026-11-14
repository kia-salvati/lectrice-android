package app.lectrice.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SheetDetent { Hidden, Half, Full }

/**
 * A modal bottom sheet (file 05 §3.6): `bg2`, top radius 28, a 48 dp handle area, a scrim behind it. Follows the finger
 * (AnchoredDraggable), closes past 30 % of the way down or on a downward fling, and calls [onDismiss] once hidden.
 * [fullHeight] makes it screen − 48 dp tall (lists); otherwise it wraps its content. [halfDetent] adds the half stop,
 * at [halfHeight] from the bottom when given (else half the sheet). A change of [targetDetent] moves the sheet there.
 */
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fullHeight: Boolean = false,
    halfDetent: Boolean = false,
    halfHeight: Dp? = null,
    initialDetent: SheetDetent = SheetDetent.Full,
    targetDetent: SheetDetent? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val state = remember { AnchoredDraggableState(initialValue = SheetDetent.Hidden) }
    val snapSpec = remember(reduced) { motionSpring<Float>(reduced) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerHeight = constraints.maxHeight.toFloat()
        val maxSheetHeight = maxHeight - SHEET_TOP_GAP
        val offset = state.offset.takeUnless { it.isNaN() } ?: containerHeight
        val minOffset = state.anchors.takeIf { it.size > 0 }?.minPosition() ?: 0f
        val shown = ((containerHeight - offset) / (containerHeight - minOffset).coerceAtLeast(1f)).coerceIn(0f, 1f)

        fun close() = scope.launch { state.animateTo(SheetDetent.Hidden, snapSpec) }

        val closeLabel = stringResource(R.string.designsystem_close)
        Box(
            Modifier
                .fillMaxSize()
                .background(c.scrim.copy(alpha = c.scrim.alpha * shown))
                .clickable(interactionSource = null, indication = null, onClickLabel = closeLabel, role = Role.Button, onClick = ::close)
                .semantics { this.contentDescription = closeLabel },
        )
        val flingPx = with(LocalDensity.current) { FLING_VELOCITY.toPx() }
        val halfPx = with(LocalDensity.current) { halfHeight?.toPx() }
        val connection = remember(state, flingPx) {
            SheetScrollConnection(state) { velocity ->
                scope.launch {
                    val anchors = state.anchors
                    if (anchors.size == 0) return@launch
                    val target = if (abs(velocity) >= flingPx) anchors.closestAnchor(state.offset, searchUpwards = velocity > 0) else anchors.closestAnchor(state.offset)
                    state.animateTo(target ?: state.currentValue, snapSpec)
                }
            }
        }
        // Anchors are offsets from the container's top, so the sheet is laid out at the top and moved by them alone.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .then(if (fullHeight) Modifier.height(maxSheetHeight) else Modifier.heightIn(max = maxSheetHeight))
                .onSizeChanged { size -> state.updateAnchors(sheetAnchors(containerHeight, size.height.toFloat(), halfDetent, halfPx)) }
                .offset { IntOffset(0, offset.roundToInt()) }
                .nestedScroll(connection)
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Vertical,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, positionalThreshold = { distance -> distance * CLOSE_SHARE }, animationSpec = snapSpec),
                )
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.bg2)
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = 12.dp).size(32.dp, 4.dp).clip(CircleShape).background(c.fg2))
            }
            content()
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.anchors.size }.filter { it > 0 }.first()
        // In its own job: a scrim tap or a drag during the opening cancels this animation, and must not cancel the
        // dismissal below (that left an invisible scrim eating every tap, B2).
        launch { state.animateTo(initialDetent, snapSpec) }
        snapshotFlow { state.targetValue }.filter { it != SheetDetent.Hidden }.first()
        snapshotFlow { isClosed(state.targetValue, state.settledValue, state.isAnimationRunning) }.filter { it }.first()
        onDismiss()
    }
    LaunchedEffect(targetDetent) {
        if (targetDetent == null) return@LaunchedEffect
        snapshotFlow { state.anchors.size }.filter { it > 0 }.first()
        state.animateTo(targetDetent, snapSpec)
    }
}

/**
 * The sheet's stops as offsets of its top edge from the container's top: Hidden just below the container, Full with the
 * whole sheet showing, Half [halfPx] (default: half the sheet) above the bottom.
 */
internal fun sheetAnchors(containerHeight: Float, sheetHeight: Float, halfDetent: Boolean, halfPx: Float?): DraggableAnchors<SheetDetent> =
    DraggableAnchors {
        SheetDetent.Hidden at containerHeight
        SheetDetent.Full at containerHeight - sheetHeight
        if (halfDetent) SheetDetent.Half at containerHeight - minOf(halfPx ?: (sheetHeight / 2f), sheetHeight)
    }

/** Closed = heading for Hidden, settled there, and no animation left (the closing slide has finished). */
internal fun isClosed(target: SheetDetent, settled: SheetDetent, animating: Boolean): Boolean =
    target == SheetDetent.Hidden && settled == SheetDetent.Hidden && !animating

/** Sheet header: a title and an optional trailing control (close button, counter). */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = LectriceTheme.type.titleM)
        trailing?.invoke()
    }
}

/** Bottom padding inside a sheet: 24 dp + the gesture inset. */
@Composable
fun SheetBottomSpacer() {
    Spacer(Modifier.navigationBarsPadding().height(24.dp))
}

/** Lets a list inside the sheet scroll, handing leftover drags and flings to the sheet (the usual modal-sheet pattern). */
private class SheetScrollConnection(private val state: AnchoredDraggableState<SheetDetent>, private val settle: (Float) -> Unit) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(delta)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val atTop = state.anchors.size > 0 && state.offset <= state.anchors.minPosition()
        return if (available.y < 0 && !atTop) {
            settle(available.y)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle(available.y)
        return available
    }
}

private val SHEET_TOP_GAP = 48.dp
private val FLING_VELOCITY = 1000.dp
private const val CLOSE_SHARE = 0.3f
