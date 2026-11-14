package app.lectrice.feature.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LocalReducedMotion
import app.lectrice.core.designsystem.PlayButton
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.StatusLine
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.motionSpring
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.SleepTimer
import app.lectrice.core.model.StatusPart
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

// ===== speed scrubber (file 05 §3.5) =====

/** The scrubber while a finger is down: where it sits (root coords of the ⏯ it grew from) and the chip under the finger. */
internal class ScrubberState(private val density: Density) {
    var anchor by mutableStateOf<Rect?>(null)
        private set
    var highlighted by mutableStateOf<Int?>(null)
        private set

    val open: Boolean get() = anchor != null

    fun start(anchor: Rect, current: PlaybackSpeed) {
        this.anchor = anchor
        highlighted = current.ordinal
    }

    /** True when the chip under the finger changed (haptic tick). */
    fun move(root: Offset): Boolean {
        val top = scrubberTop(anchor ?: return false)
        val chip = with(density) { ((root.y - top - SCRUB_PADDING.toPx()) / SCRUB_STEP.toPx()).toInt() }
        if (chip !in 0 until SPEED_COUNT) return false
        val index = SPEED_COUNT - 1 - chip
        if (index == highlighted) return false
        highlighted = index
        return true
    }

    fun finish(): PlaybackSpeed? {
        val picked = highlighted?.let { PlaybackSpeed.entries[it] }
        close()
        return picked
    }

    fun close() {
        anchor = null
        highlighted = null
    }

    fun scrubberTop(anchor: Rect): Float = with(density) { anchor.top - SCRUB_GAP.toPx() - SCRUB_HEIGHT.toPx() }
}

/** The six chips, 2× at the top, above the ⏯ that was long-pressed; [origin] = the host box's position in the root. */
@Composable
internal fun SpeedScrubberOverlay(state: ScrubberState, origin: Offset) {
    val c = LectriceTheme.colors
    val anchor = state.anchor ?: return
    val top = state.scrubberTop(anchor)
    val label = stringResource(R.string.scrubber_a11y)
    Column(
        Modifier
            .offset { IntOffset((anchor.left - origin.x).roundToInt(), (top - origin.y).roundToInt()) }
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg2)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(SCRUB_PADDING)
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(SCRUB_CHIP_GAP),
    ) {
        PlaybackSpeed.entries.reversed().forEach { speed ->
            val on = speed.ordinal == state.highlighted
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .height(SCRUB_CHIP)
                    .clip(shape)
                    .then(if (on) Modifier.background(c.accentContainer) else Modifier.border(1.dp, c.line, shape))
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) LectriceIcon(LectriceIcons.Check, null, tint = c.onAccentContainer, size = 18.dp)
                Text(speed.label, style = LectriceTheme.type.label, color = if (on) c.onAccentContainer else c.fg, maxLines = 1)
            }
        }
    }
}

/** What a long-press on a ⏯ (or a swipe / drag on the pill) tells its host. */
internal class PillGestureHandlers(
    val isOnPlay: (Offset) -> Boolean,
    val toRoot: (Offset) -> Offset,
    val playAnchor: () -> Rect?,
    val onScrubStart: (Rect) -> Unit,
    val onScrubMove: (Offset) -> Unit,
    val onScrubEnd: () -> Unit,
    val onScrubCancel: () -> Unit,
    val onSwipe: ((forward: Boolean) -> Unit)? = null,
    val onDragUp: (() -> Unit)? = null,
)

private enum class PillGesture { Undecided, Swipe, DragUp, Scrub, Other }

/**
 * The gesture map of README §4.1 on one surface, watched in the Initial pass so a committed swipe, drag or long-press
 * cancels the buttons underneath: tap = the child button; horizontal ≥ 48 dp = ± sentence; up > 8 dp = the sheet;
 * ⏯ held 500 ms = the speed scrubber, which then follows the finger until it lifts.
 */
internal suspend fun PointerInputScope.detectPillGestures(haptics: HapticFeedback, handlers: PillGestureHandlers) {
    val slop = viewConfiguration.touchSlop
    val swipePx = SWIPE_DISTANCE.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        val onPlay = handlers.isOnPlay(start)
        var mode = PillGesture.Undecided
        var scrubDone = false
        val longPressAt = System.currentTimeMillis() + LONG_PRESS_MS
        while (true) {
            val event = if (mode == PillGesture.Undecided && onPlay) {
                val left = longPressAt - System.currentTimeMillis()
                withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent(PointerEventPass.Initial) }
            } else {
                awaitPointerEvent(PointerEventPass.Initial)
            }
            if (event == null) {
                val anchor = handlers.playAnchor() ?: break
                mode = PillGesture.Scrub
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                handlers.onScrubStart(anchor)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - start
            val up = change.changedToUpIgnoreConsumed()
            when (mode) {
                PillGesture.Undecided -> {
                    if (up) break
                    when {
                        abs(delta.x) > slop && abs(delta.x) > abs(delta.y) && handlers.onSwipe != null -> mode = PillGesture.Swipe
                        -delta.y > slop && abs(delta.y) > abs(delta.x) && handlers.onDragUp != null -> {
                            mode = PillGesture.DragUp
                            change.consume()
                            handlers.onDragUp.invoke()
                        }
                        delta.getDistance() > slop -> mode = PillGesture.Other
                    }
                }
                PillGesture.Swipe -> {
                    change.consume()
                    if (up) {
                        if (abs(delta.x) >= swipePx) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            handlers.onSwipe?.invoke(delta.x < 0)
                        }
                        break
                    }
                }
                PillGesture.DragUp -> {
                    change.consume()
                    if (up) break
                }
                PillGesture.Scrub -> {
                    change.consume()
                    if (up) {
                        scrubDone = true
                        handlers.onScrubEnd()
                        break
                    }
                    handlers.onScrubMove(handlers.toRoot(change.position))
                }
                PillGesture.Other -> if (up) break
            }
        }
        if (mode == PillGesture.Scrub && !scrubDone) handlers.onScrubCancel()
    }
}

/** A long-press-only scrubber host for the sheet's and the Listen view's ⏯: the whole node is the button. */
internal fun Modifier.longPressScrubber(scrubber: ScrubberState, speed: PlaybackSpeed, onApply: (PlaybackSpeed) -> Unit, onUsed: () -> Unit = {}): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coords = it }.pointerInput(scrubber, speed) {
        detectPillGestures(
            haptics,
            PillGestureHandlers(
                isOnPlay = { true },
                toRoot = { coords.attachedOrNull()?.localToRoot(it) ?: it },
                playAnchor = { coords.boundsInRootOrNull() },
                onScrubStart = { anchor -> onUsed(); scrubber.start(anchor, speed) },
                onScrubMove = { if (scrubber.move(it)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                onScrubEnd = { scrubber.finish()?.let(onApply) },
                onScrubCancel = scrubber::close,
            ),
        )
    }
}

// ===== the pill (file 05 §3.1–3.4) =====

/** The pill's one-line status: text parts, an optional spinner, colour, and what a tap does. */
internal class PillStatus(
    val parts: List<StatusPart>,
    val spinner: Boolean,
    val accent: Boolean,
    val label: String,
    val sleepIcon: Boolean,
    val sleepText: String?,
    /** File 23 B3: the headphones mark before the text while the book plays from its audiobook file. */
    val audiobookMark: Boolean = false,
    /** File 24 H7: the computer mark before the text while a computer makes the audio (the audiobook mark wins). */
    val computerMark: Boolean = false,
)

@Composable
internal fun rememberPillStatus(player: PlayerInfo?, voiceMissing: Boolean, screenWidth: Dp, helper: HelperLineUi?): PillStatus? {
    if (player == null) return null
    val noVoice = voiceMissing || player.status == PlaybackStatus.VoiceMissing
    // At 200 % font on a narrow screen only the page part ("p. 3") fits; the spoken label keeps the whole location.
    val pageOnly = LocalDensity.current.fontScale >= TALL_FONT_SCALE && screenWidth < WIDE_STATUS
    val speedPart = if (player.speed != PlaybackSpeed.X1 && screenWidth >= WIDE_STATUS) stringResource(R.string.pill_speed_a11y, player.speed.label) else null
    val sleepPart = player.sleepShort?.let { stringResource(R.string.pill_sleep_a11y, it) }
    val fromFile = player.audiobook?.active == true
    return when {
        player.audiobook?.missing == true -> stringResource(R.string.pill_audiobook_missing).let { PillStatus(listOf(StatusPart(it)), false, false, it, false, null, fromFile) }
        noVoice -> PillStatus(listOf(StatusPart(stringResource(R.string.pill_no_voice))), false, true, stringResource(R.string.pill_no_voice_a11y), false, null)
        player.status == PlaybackStatus.VoiceFailed -> PillStatus(listOf(StatusPart(stringResource(R.string.pill_voice_failed))), false, false, stringResource(R.string.pill_voice_failed_a11y), false, null)
        player.status == PlaybackStatus.Preparing -> PillStatus(listOf(StatusPart(stringResource(R.string.pill_preparing))), true, false, stringResource(R.string.pill_preparing), false, null)
        player.status == PlaybackStatus.CatchingUp -> PillStatus(listOf(StatusPart(stringResource(R.string.pill_catching_up))), true, false, stringResource(R.string.pill_catching_up), false, null)
        player.status == PlaybackStatus.Finished -> PillStatus(listOf(StatusPart(stringResource(R.string.pill_finished))), false, false, stringResource(R.string.pill_finished_a11y), false, null)
        else -> {
            val location = if (pageOnly) player.location.takeLast(1) else player.location
            val parts = location + listOfNotNull(if (player.speed != PlaybackSpeed.X1 && screenWidth >= WIDE_STATUS) StatusPart(player.speed.label) else null)
            val sleepOn = player.sleep != SleepTimer.Off
            val sleepText = if (sleepOn && player.sleep is SleepTimer.Minutes && screenWidth >= WIDE_STATUS) player.sleepShort else null
            val computerMark = helper?.pillMark == true && !fromFile
            val helperSpoken = helper?.pillSpoken.orEmpty()
            val spoken = listOfNotNull(player.locationSpoken.substringBefore(". "), speedPart, sleepPart).joinToString(", ") + helperSpoken + ". " + stringResource(R.string.pill_listen)
            PillStatus(parts, false, false, spoken, sleepOn, sleepText, fromFile, computerMark)
        }
    }
}

/**
 * Expanded: ⏮ ⏯ ⏭ · status · ⋯ in a 56 dp pill (72 at 200 % font). Collapsed: a 56 dp round play button at the right.
 * The morph animates width and position with the default spring. [onBounds] reports the pill and ⏯ rects (root coords).
 */
@Composable
internal fun Pill(
    player: PlayerInfo?,
    status: PillStatus?,
    enabled: Boolean,
    collapsed: Boolean,
    screenWidth: Dp,
    tall: Boolean,
    scrubber: ScrubberState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStatusTap: () -> Unit,
    onMore: () -> Unit,
    onChapters: () -> Unit,
    onDragUp: () -> Unit,
    onExpand: () -> Unit,
    onSetSpeed: (PlaybackSpeed) -> Unit,
    onGestureUsed: () -> Unit,
    onBounds: (pill: Rect, play: Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val expandedWidth = screenWidth - PILL_SIDE * 2
    val width by animateDpAsState(if (collapsed) COLLAPSED_SIZE else expandedWidth, motionSpring(reduced), label = "pillWidth")
    val x by animateDpAsState(if (collapsed) screenWidth - COLLAPSED_SIDE - COLLAPSED_SIZE else PILL_SIDE, motionSpring(reduced), label = "pillX")
    val height = if (collapsed) COLLAPSED_SIZE else if (tall) PILL_HEIGHT_TALL else PILL_HEIGHT
    val heightAnimated by animateDpAsState(height, motionSpring(reduced), label = "pillHeight")
    val progress = ((expandedWidth - width) / (expandedWidth - COLLAPSED_SIZE)).coerceIn(0f, 1f)
    val expandedAlpha by animateFloatAsState(if (collapsed) 0f else 1f, motionSpring(reduced), label = "pillContent")
    var pillCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var playCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val playing = player?.isPlaying == true
    val speed = player?.speed ?: PlaybackSpeed.X1
    val listenLabel = stringResource(R.string.pill_listen)
    val chaptersLabel = stringResource(R.string.pill_chapters)
    val moreLabel = stringResource(R.string.pill_more)
    val speedLabel = stringResource(R.string.pill_change_speed)
    val showPlayerLabel = stringResource(R.string.pill_show_player)
    val playPauseLabel = stringResource(if (playing) R.string.pill_pause else R.string.pill_play)
    val handlers = remember(enabled, collapsed, scrubber, speed) {
        PillGestureHandlers(
            isOnPlay = { point -> enabled && (collapsed || isInside(pillCoords, playCoords, point)) },
            toRoot = { pillCoords.attachedOrNull()?.localToRoot(it) ?: it },
            playAnchor = { (if (collapsed) pillCoords else playCoords).boundsInRootOrNull() },
            onScrubStart = { anchor -> onGestureUsed(); scrubber.start(anchor, speed) },
            onScrubMove = { if (scrubber.move(it)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            onScrubEnd = { scrubber.finish()?.let(onSetSpeed) },
            onScrubCancel = scrubber::close,
            onSwipe = if (enabled && !collapsed) ({ forward -> if (forward) onNext() else onPrevious() }) else null,
            onDragUp = if (!enabled) null else if (collapsed) onExpand else ({ onGestureUsed(); onDragUp() }),
        )
    }
    Box(
        modifier
            .offset(x = x)
            .width(width)
            .height(heightAnimated)
            .onGloballyPositioned { coords ->
                pillCoords = coords
                pillBounds(coords, playCoords, collapsed)?.let { (pill, play) -> onBounds(pill, play) }
            }
            .clip(CircleShape)
            .background(c.bg2)
            .border(1.dp, c.line, CircleShape)
            .pointerInput(handlers) { detectPillGestures(haptics, handlers) }
            .semantics {
                if (enabled && !collapsed) {
                    customActions = listOf(
                        CustomAccessibilityAction(listenLabel) { onStatusTap(); true },
                        CustomAccessibilityAction(chaptersLabel) { onChapters(); true },
                        CustomAccessibilityAction(moreLabel) { onMore(); true },
                        CustomAccessibilityAction(speedLabel) { onMore(); true },
                    )
                }
            },
    ) {
        if (progress < HALF) {
            // The ⏯ leaves with this row (collapsing); its old coordinates would be detached (crash B1).
            DisposableEffect(Unit) { onDispose { playCoords = null } }
            Row(
                Modifier.fillMaxSize().alpha(expandedAlpha).padding(horizontal = PILL_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (enabled) c.fg else c.fg2
                LectriceIconButton(LectriceIcons.SkipPrevious, stringResource(R.string.pill_previous), onPrevious, enabled = enabled, tint = tint)
                PlayButton(
                    playing = playing,
                    onClick = onPlayPause,
                    contentDescription = playPauseLabel,
                    modifier = Modifier.onGloballyPositioned { playCoords = it },
                    size = 48.dp,
                    discSize = 40.dp,
                    preparing = player?.status == PlaybackStatus.Preparing || player?.status == PlaybackStatus.CatchingUp,
                )
                LectriceIconButton(LectriceIcons.SkipNext, stringResource(R.string.pill_next), onNext, enabled = enabled, tint = tint)
                if (screenWidth < NARROW_STATUS) {
                    Spacer(Modifier.weight(1f))
                    LectriceIconButton(LectriceIcons.OpenInFull, status?.label ?: listenLabel, onStatusTap, enabled = enabled && status != null, tint = c.fg2)
                } else {
                    StatusButton(status, enabled, screenWidth >= WIDE_STATUS, onStatusTap, Modifier.weight(1f))
                }
                LectriceIconButton(LectriceIcons.MoreHoriz, moreLabel, onMore, enabled = enabled, tint = tint)
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(1f - expandedAlpha)
                    .clickable(interactionSource = null, indication = ripple(bounded = false, radius = 28.dp), enabled = enabled, role = Role.Button, onClick = onPlayPause)
                    .semantics {
                        contentDescription = playPauseLabel
                        customActions = listOf(CustomAccessibilityAction(showPlayerLabel) { onExpand(); true })
                    },
                contentAlignment = Alignment.Center,
            ) {
                LectriceIcon(if (playing) LectriceIcons.Pause else LectriceIcons.PlayArrow, null, tint = c.accent)
            }
        }
    }
}

@Composable
private fun StatusButton(status: PillStatus?, enabled: Boolean, showExpandIcon: Boolean, onTap: () -> Unit, modifier: Modifier) {
    val c = LectriceTheme.colors
    Row(
        modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = null, indication = ripple(), enabled = enabled && status != null, role = Role.Button, onClick = onTap)
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) {
                if (status != null) contentDescription = status.label
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == null) return@Row
        val color = if (!enabled) c.fg2 else if (status.accent) c.accent else c.fg
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.spinner) Spinner()
            if (status.audiobookMark) LectriceIcon(LectriceIcons.Headphones, stringResource(R.string.pill_audiobook_a11y), tint = color, size = 16.dp)
            if (status.computerMark) LectriceIcon(LectriceIcons.Computer, null, tint = color, size = 16.dp)
            StatusLine(status.parts, Modifier.weight(1f, fill = false), style = LectriceTheme.type.label, color = color)
            if (status.sleepIcon) {
                Text(SEPARATOR, style = LectriceTheme.type.label, color = color, maxLines = 1)
                LectriceIcon(LectriceIcons.Bedtime, null, tint = color, size = 16.dp)
                status.sleepText?.let { Text(it, style = LectriceTheme.type.label, color = color, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
        if (showExpandIcon) LectriceIcon(LectriceIcons.OpenInFull, null, tint = c.fg2, size = 16.dp)
    }
}

private const val LONG_PRESS_MS = 500L
private const val SPEED_COUNT = 6
private const val HALF = 0.5f
private const val SEPARATOR = "·"
private val SWIPE_DISTANCE = 48.dp
private val SCRUB_CHIP = 32.dp
private val SCRUB_CHIP_GAP = 4.dp
private val SCRUB_PADDING = 8.dp
private val SCRUB_STEP = SCRUB_CHIP + SCRUB_CHIP_GAP
private val SCRUB_GAP = 8.dp
private val SCRUB_HEIGHT = SCRUB_PADDING * 2 + SCRUB_CHIP * SPEED_COUNT + SCRUB_CHIP_GAP * (SPEED_COUNT - 1)
internal val PILL_SIDE = 12.dp
internal val PILL_HEIGHT = 56.dp
internal val PILL_HEIGHT_TALL = 72.dp
internal val PILL_BOTTOM = 12.dp
private val PILL_PADDING = 8.dp
private val COLLAPSED_SIZE = 56.dp
private val COLLAPSED_SIDE = 16.dp
internal val WIDE_STATUS = 400.dp
internal val NARROW_STATUS = 360.dp
