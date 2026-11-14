package app.lectrice.feature.reader

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.HintTooltip
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LocalReducedMotion
import app.lectrice.core.designsystem.SegmentedControl
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.SheetHeader
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.endFade
import app.lectrice.core.designsystem.motionSpring
import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.SleepTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the Listen view asks of its host besides playback. */
internal class ListenHandlers(
    val onClose: () -> Unit,
    val onSpeed: () -> Unit,
    val onSleep: () -> Unit,
    val onChapters: () -> Unit,
    val onMore: () -> Unit,
    val onHintDone: () -> Unit,
    /** Over the lock screen: runs [action] once the user unlocked (file 06 §3.6). */
    val whenUnlocked: (action: () -> Unit) -> Unit,
)

/**
 * The Listen view (file 06): top bar with the drag-down handle, the teleprompter, the thumb zone. [openProgress] 0–1
 * is the container transform from the pill ([pillBounds], root coords); the content fades in over its last 62 %.
 * [locked] draws the N2L marks. Back shrinks it towards the pill (predictive) before [ListenHandlers.onClose].
 */
@Composable
internal fun ListenView(
    state: ReaderUiState,
    actions: ReaderActions,
    scrubber: ScrubberState,
    openProgress: Float,
    pillBounds: Rect?,
    origin: Offset,
    locked: Boolean,
    backEnabled: Boolean,
    handlers: ListenHandlers,
) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listen = state.listen
    val player = state.player
    var backProgress by remember { mutableFloatStateOf(0f) }
    val dragY = remember { Animatable(0f) }
    val contentAlpha = ((openProgress - CONTENT_FADE_START) / (1f - CONTENT_FADE_START)).coerceIn(0f, 1f)

    PredictiveBackHandler(enabled = backEnabled) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            backProgress = 0f
            if (locked) handlers.whenUnlocked(handlers.onClose) else handlers.onClose()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullWidth = constraints.maxWidth.toFloat()
        val fullHeight = constraints.maxHeight.toFloat()
        val dragPx = with(density) { DRAG_SCALE_DISTANCE.toPx() }
        val shrink = maxOf(backProgress, (dragY.value / dragPx).coerceIn(0f, 1f))
        val pill = pillBounds?.translate(-origin.x, -origin.y)
        val stageRect = if (pill != null && openProgress < 1f) Rect(
            lerp(pill.left, 0f, openProgress),
            lerp(pill.top, 0f, openProgress),
            lerp(pill.right, fullWidth, openProgress),
            lerp(pill.bottom, fullHeight, openProgress),
        ) else Rect(0f, 0f, fullWidth, fullHeight)
        val radius = if (openProgress < 1f) lerp(PILL_RADIUS, 0.dp, openProgress) else lerp(0.dp, BACK_RADIUS, shrink)
        val landscape = maxWidth > maxHeight
        val narrow = maxWidth < WIDE_STATUS
        Box(
            Modifier
                .offset { IntOffset(stageRect.left.roundToInt(), stageRect.top.roundToInt()) }
                .size(with(density) { stageRect.width.toDp() }, with(density) { stageRect.height.toDp() })
                .graphicsLayer {
                    val scale = 1f - BACK_SCALE * shrink
                    scaleX = scale
                    scaleY = scale
                    translationY = dragY.value + with(density) { BACK_SHIFT.toPx() } * backProgress
                    transformOrigin = TransformOrigin(HALF, 1f)
                    shape = RoundedCornerShape(radius)
                    clip = true
                }
                .background(c.bg)
                .then(if (shrink > 0f) Modifier.border(1.dp, c.line, RoundedCornerShape(radius)) else Modifier),
        ) {
            if (listen != null && player != null) {
                val top: @Composable () -> Unit = {
                    ListenTopBar(
                        bookTitle = listen.bookTitle,
                        title = listen.title,
                        alpha = contentAlpha,
                        onBack = { if (locked) handlers.whenUnlocked(handlers.onClose) else handlers.onClose() },
                        onDrag = { delta -> scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) } },
                        onRelease = { velocity ->
                            val close = dragY.value > fullHeight * CLOSE_SHARE || velocity >= with(density) { FLING_VELOCITY.toPx() }
                            if (close && !locked) handlers.onClose() else scope.launch { dragY.animateTo(0f, motionSpring(reduced)) }
                        },
                    )
                }
                val prompter: @Composable ColumnScope.() -> Unit = {
                    Teleprompter(state, actions, Modifier.weight(1f).alpha(contentAlpha), hint = !state.listenHintSeen, onHintDone = handlers.onHintDone)
                }
                val zone: @Composable () -> Unit = {
                    ThumbZone(state, actions, scrubber, contentAlpha, narrow = narrow, locked = locked, handlers = handlers)
                }
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(LANDSCAPE_TEXT_SHARE).fillMaxHeight()) {
                            top()
                            prompter()
                        }
                        Column(Modifier.weight(1f - LANDSCAPE_TEXT_SHARE).fillMaxHeight(), verticalArrangement = Arrangement.Center) { zone() }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        top()
                        prompter()
                        zone()
                    }
                }
                if (locked) {
                    LectriceIcon(LectriceIcons.Lock, null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = LectriceTheme.space.s), tint = c.fg, size = LectriceTheme.size.iconS)
                    Text(
                        stringResource(R.string.listen_unlock),
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = LectriceTheme.space.xs),
                        style = LectriceTheme.type.caption,
                        color = c.fg2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenTopBar(bookTitle: String, title: String, alpha: Float, onBack: () -> Unit, onDrag: (Float) -> Unit, onRelease: (velocity: Float) -> Unit) {
    val c = LectriceTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = TOP_BAR_HEIGHT)
            .alpha(alpha)
            .padding(start = LectriceTheme.space.xs, end = LectriceTheme.space.l)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        onDrag(over)
                    } ?: return@awaitEachGesture
                    tracker.addPosition(drag.uptimeMillis, drag.position)
                    verticalDrag(drag.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        onDrag(change.positionChange().y)
                        change.consume()
                    }
                    onRelease(tracker.calculateVelocity().y)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LectriceIconButton(LectriceIcons.ExpandMore, stringResource(R.string.listen_back), onBack, Modifier.focusRequester(focus).focusable())
        Column(Modifier.weight(1f).padding(start = LectriceTheme.space.xs)) {
            Text(bookTitle, style = LectriceTheme.type.bodyS, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = LectriceTheme.type.labelL.copy(lineHeight = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Previous / current (3 dp accent bar) / next, fades top and bottom, tap = play/pause with the flash, swipe = ± sentence. */
@Composable
private fun Teleprompter(state: ReaderUiState, actions: ReaderActions, modifier: Modifier, hint: Boolean, onHintDone: () -> Unit) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listen = state.listen ?: return
    val player = state.player ?: return
    val playing = player.isPlaying
    val glide = remember { Animatable(0f) }
    val flashAlpha = remember { Animatable(0f) }
    var flashIcon by remember { mutableStateOf<ImageVector?>(null) }
    val glidePx = with(density) { GLIDE_DISTANCE.toPx() }
    val swipePx = with(density) { SWIPE_DISTANCE.toPx() }
    val slop = with(density) { SWIPE_SLOP.toPx() }
    val readSkipped = player.readSkipped
    val finished = player.status == PlaybackStatus.Finished
    val nextLabel = stringResource(R.string.pill_next)
    val previousLabel = stringResource(R.string.pill_previous)
    val playLabel = stringResource(if (playing) R.string.pill_pause else R.string.pill_play)

    fun tap() {
        if (hint) onHintDone()
        flashIcon = if (playing) LectriceIcons.PlayArrow else LectriceIcons.Pause
        actions.playPause()
        scope.launch {
            if (reduced) {
                flashAlpha.snapTo(FLASH_ALPHA)
                delay(FLASH_MS.toLong())
                flashAlpha.snapTo(0f)
            } else {
                flashAlpha.snapTo(0f)
                flashAlpha.animateTo(0f, keyframes { durationMillis = FLASH_MS; FLASH_ALPHA at FLASH_IN_MS; FLASH_ALPHA at FLASH_OUT_MS })
            }
        }
    }

    fun swipe(forward: Boolean) {
        if (hint) onHintDone()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (forward) actions.next() else actions.previous()
        if (reduced) return
        scope.launch {
            glide.snapTo(if (forward) glidePx else -glidePx)
            glide.animateTo(0f, motionSpring(reduced))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = LectriceTheme.space.xs, end = LectriceTheme.space.l)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                val fade = FADE_HEIGHT.toPx()
                drawRect(Brush.verticalGradient(listOf(c.bg, Color.Transparent), endY = fade), size = Size(size.width, fade))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.bg), startY = size.height - fade, endY = size.height), topLeft = Offset(0f, size.height - fade), size = Size(size.width, fade))
            }
            .pointerInput(playing) { detectTapGestures { tap() } }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) >= swipePx) swipe(forward = total < 0) },
                    onDragCancel = { total = 0f },
                ) { change, dx ->
                    total += dx
                    if (abs(total) > slop) change.consume()
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = playLabel
                this.role = Role.Button
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) { actions.next(); true },
                    CustomAccessibilityAction(previousLabel) { actions.previous(); true },
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, glide.value.roundToInt()) }
                .alpha(1f - GLIDE_DIM * (abs(glide.value) / glidePx).coerceIn(0f, 1f))
                .padding(start = LectriceTheme.space.m),
            verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.l, Alignment.CenterVertically),
        ) {
            if (density.fontScale >= TALL_FONT_SCALE) {
                // At a very large font the neighbours give their space to the current sentence, which scrolls if it still doesn't fit.
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { CurrentSlot(listen, readSkipped, finished, playing, whole = true) }
            } else {
                val previous = if (finished) listen.current else listen.previous
                val neighbour = LectriceTheme.type.serif(NEIGHBOUR_SP.sp, NEIGHBOUR_LINE_SP.sp)
                Box(Modifier.heightIn(max = with(density) { (NEIGHBOUR_LINE_SP * NEIGHBOUR_LINES).sp.toDp() }).clipToBounds(), contentAlignment = Alignment.BottomStart) {
                    if (previous != null) Text(previous, style = neighbour, color = c.fg2)
                }
                CurrentSlot(listen, readSkipped, finished, playing, whole = false)
                if (listen.next != null && !finished) Text(listen.next, style = neighbour, color = c.fg2, maxLines = NEIGHBOUR_LINES, overflow = TextOverflow.Ellipsis)
            }
        }
        flashIcon?.let { icon ->
            Box(
                Modifier.align(Alignment.Center).size(FLASH_SIZE).alpha(flashAlpha.value).clip(CircleShape).background(c.inverseBg),
                contentAlignment = Alignment.Center,
            ) { LectriceIcon(icon, null, tint = c.inverseFg, size = 36.dp) }
        }
        if (hint) {
            HintTooltip(stringResource(R.string.listen_hint), stringResource(R.string.hint_got_it), onHintDone, Modifier.align(Alignment.BottomCenter).padding(start = LectriceTheme.space.m, bottom = LectriceTheme.space.m))
        }
    }
}

/**
 * The current slot: the sentence (28/40 500, 24/34 past six lines), a heading at 600, or the R13c card for code and
 * tables. [whole] shows every line of the sentence instead of cutting it at six.
 */
@Composable
private fun CurrentSlot(listen: ListenInfo, readSkipped: Boolean, finished: Boolean, playing: Boolean, whole: Boolean) {
    val c = LectriceTheme.colors
    val type = LectriceTheme.type
    val isBox = listen.currentBlockKind == BlockKind.Code || listen.currentBlockKind == BlockKind.Table
    var long by remember(listen.current) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s)) {
        Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 6.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Box(Modifier.weight(1f).semantics { if (!playing) liveRegion = LiveRegionMode.Polite }) {
            when {
                finished -> Text(stringResource(R.string.listen_finished), style = type.serif(CURRENT_SP.sp, CURRENT_LINE_SP.sp, FontWeight.Medium))
                isBox && !finished -> BoxCard(listen, readSkipped)
                else -> {
                    val weight = if (listen.currentBlockKind == BlockKind.Heading) FontWeight.SemiBold else FontWeight.Medium
                    val style = if (long) type.serif(LONG_SP.sp, LONG_LINE_SP.sp, weight) else type.serif(CURRENT_SP.sp, CURRENT_LINE_SP.sp, weight)
                    BasicText(
                        listen.current,
                        style = style.copy(color = c.fg),
                        maxLines = if (long && !whole) MAX_CURRENT_LINES else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!long && it.lineCount > MAX_CURRENT_LINES) long = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxCard(listen: ListenInfo, readSkipped: Boolean) {
    val c = LectriceTheme.colors
    val scroll = rememberScrollState()
    val title = stringResource(if (listen.currentBlockKind == BlockKind.Table) R.string.listen_table_title else R.string.listen_code_title)
    val lines = remember(listen.currentBoxText, listen.current, readSkipped) {
        (if (readSkipped) listen.current else listen.currentBoxText.orEmpty()).lineSequence().take(BOX_LINES).joinToString("\n")
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg2).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(LectriceTheme.space.l)) {
        Text(title, style = LectriceTheme.type.label)
        Box(Modifier.fillMaxWidth().padding(top = LectriceTheme.space.s).endFade(scroll, c.bg2)) {
            Text(lines, Modifier.horizontalScroll(scroll), style = LectriceTheme.type.code, color = c.fg2, maxLines = BOX_LINES)
        }
        if (!readSkipped) Text(stringResource(R.string.reader_read_as, listen.current), Modifier.padding(top = LectriceTheme.space.s), style = LectriceTheme.type.caption, color = c.fg2)
    }
}

@Composable
private fun ThumbZone(state: ReaderUiState, actions: ReaderActions, scrubber: ScrubberState, alpha: Float, narrow: Boolean, locked: Boolean, handlers: ListenHandlers) {
    val c = LectriceTheme.colors
    val listen = state.listen ?: return
    val player = state.player ?: return
    val playing = player.isPlaying
    val preparing = player.status == PlaybackStatus.Preparing || player.status == PlaybackStatus.CatchingUp
    val enabled = player.status != PlaybackStatus.VoiceMissing && player.status != PlaybackStatus.VoiceFailed
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = LectriceTheme.space.l, end = LectriceTheme.space.l, bottom = if (locked) ZONE_BOTTOM + LOCKED_LIFT else ZONE_BOTTOM),
        verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.l),
    ) {
        Column(Modifier.alpha(alpha)) {
            ChapterProgress(listen.chapterFraction, listen.sectionTicks, stringResource(R.string.listen_progress_a11y))
            Row(Modifier.fillMaxWidth().padding(top = LectriceTheme.space.xs), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(listen.progress, Modifier.weight(1f, fill = false), style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (preparing) {
                        Spinner()
                        Text(stringResource(if (player.status == PlaybackStatus.Preparing) R.string.pill_preparing else R.string.pill_catching_up), style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1)
                    } else {
                        Text(listen.timeLeft, style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 24.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(SIDE_BUTTON, c.bg2, c.fg, LectriceIcons.SkipPrevious, 32.dp, stringResource(R.string.pill_previous), enabled, Modifier.alpha(alpha), border = true, onClick = actions::previous)
            Box(contentAlignment = Alignment.Center) {
                RoundButton(
                    PLAY_BUTTON,
                    if (preparing) c.accentContainer else c.accent,
                    if (preparing) c.onAccentContainer else c.onAccent,
                    if (playing) LectriceIcons.Pause else LectriceIcons.PlayArrow,
                    36.dp,
                    stringResource(if (playing) R.string.pill_pause else R.string.pill_play),
                    enabled,
                    Modifier.longPressScrubber(scrubber, player.speed, actions::setSpeed),
                    border = false,
                    onClick = actions::playPause,
                )
                if (preparing) Spinner(size = PLAY_BUTTON + 8.dp, stroke = 3.dp)
            }
            RoundButton(SIDE_BUTTON, c.bg2, c.fg, LectriceIcons.SkipNext, 32.dp, stringResource(R.string.pill_next), enabled, Modifier.alpha(alpha), border = true, onClick = actions::next)
        }
        Row(Modifier.fillMaxWidth().alpha(alpha), horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.l)) {
            SectionButton(stringResource(R.string.sheet_previous_section), LectriceIcons.KeyboardDoubleArrowLeft, iconFirst = true, enabled = enabled && player.canPreviousSection, onClick = actions::previousSection)
            SectionButton(stringResource(R.string.sheet_next_section), LectriceIcons.KeyboardDoubleArrowRight, iconFirst = false, enabled = enabled && player.canNextSection, onClick = actions::nextSection)
        }
        val sleepOn = player.sleep != SleepTimer.Off
        val sleepCaption = when (player.sleep) {
            SleepTimer.Off -> stringResource(R.string.listen_sleep)
            SleepTimer.EndOfChapter -> stringResource(R.string.listen_sleep_chapter)
            is SleepTimer.Minutes -> player.sleepShort ?: stringResource(R.string.listen_sleep)
        }
        val speedLabel = stringResource(R.string.sheet_speed_a11y, player.speed.label)
        val tools = listOf(
            Tool(stringResource(R.string.listen_speed), speedLabel, handlers.onSpeed) { Text(player.speed.label, style = LectriceTheme.type.labelL) },
            Tool(sleepCaption, stringResource(R.string.sheet_sleep), handlers.onSleep) { LectriceIcon(LectriceIcons.Bedtime, null, tint = if (sleepOn) c.accent else c.fg) },
            Tool(stringResource(R.string.listen_chapters), stringResource(R.string.listen_chapters), handlers.onChapters) { LectriceIcon(LectriceIcons.Toc, null) },
            Tool(stringResource(R.string.listen_more), stringResource(R.string.pill_more), { if (locked) handlers.whenUnlocked(handlers.onMore) else handlers.onMore() }) { LectriceIcon(LectriceIcons.MoreHoriz, null) },
        )
        // At 200 % font the four captions don't fit side by side on any phone width: a 2 × 2 grid of icon-beside-caption cells.
        val grid = LocalDensity.current.fontScale >= TALL_FONT_SCALE
        if (grid) {
            Column(Modifier.fillMaxWidth().alpha(alpha), verticalArrangement = Arrangement.spacedBy(TOOL_GRID_GAP)) {
                tools.chunked(TOOL_GRID_COLUMNS).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TOOL_GRID_GAP)) { pair.forEach { ToolCell(it) } }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().alpha(alpha), horizontalArrangement = Arrangement.SpaceAround) { tools.forEach { ToolButton(it) } }
        }
    }
}

/** One tool under the transport: its caption, what TalkBack says, and the icon (or "1×") shown above or beside it. */
private class Tool(val caption: String, val contentDescription: String, val onClick: () -> Unit, val top: @Composable () -> Unit)

@Composable
private fun ChapterProgress(fraction: Float, ticks: List<Float>, contentDescription: String) {
    val c = LectriceTheme.colors
    Canvas(Modifier.fillMaxWidth().height(12.dp).semantics { this.contentDescription = contentDescription }) {
        val trackHeight = 4.dp.toPx()
        val y = size.height / 2 - trackHeight / 2
        drawRoundRect(c.line, Offset(0f, y), Size(size.width, trackHeight), CornerRadius(trackHeight / 2))
        drawRoundRect(c.accent, Offset(0f, y), Size(size.width * fraction.coerceIn(0f, 1f), trackHeight), CornerRadius(trackHeight / 2))
        ticks.forEach { tick -> drawRect(c.fg2, Offset(size.width * tick.coerceIn(0f, 1f) - 1.dp.toPx(), y - 2.dp.toPx()), Size(2.dp.toPx(), 8.dp.toPx())) }
    }
}

@Composable
private fun RoundButton(size: Dp, background: Color, tint: Color, icon: ImageVector, iconSize: Dp, contentDescription: String, enabled: Boolean, modifier: Modifier, border: Boolean, onClick: () -> Unit) {
    val c = LectriceTheme.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .clickable(interactionSource = null, indication = ripple(color = tint), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        LectriceIcon(icon, null, tint = if (enabled) tint else c.fg2, size = iconSize)
    }
}

@Composable
private fun RowScope.SectionButton(label: String, icon: ImageVector, iconFirst: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LectriceTheme.colors
    val tint = if (enabled) c.fg else c.fg2
    Row(
        Modifier
            .weight(1f)
            .height(48.dp)
            .clip(CircleShape)
            .background(c.bg2)
            .border(1.dp, c.line, CircleShape)
            .clickable(interactionSource = null, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconFirst) LectriceIcon(icon, null, tint = tint, size = LectriceTheme.size.icon)
        Text(stringResource(R.string.listen_section), style = LectriceTheme.type.label, color = tint)
        if (!iconFirst) LectriceIcon(icon, null, tint = tint, size = LectriceTheme.size.icon)
    }
}

@Composable
private fun ToolButton(tool: Tool) {
    val c = LectriceTheme.colors
    Column(
        Modifier
            .width(TOOL_SIZE)
            .heightIn(min = TOOL_SIZE)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = null, indication = ripple(), role = Role.Button, onClick = tool.onClick)
            .semantics(mergeDescendants = true) { contentDescription = tool.contentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.xs, Alignment.CenterVertically),
    ) {
        tool.top()
        Text(tool.caption, style = LectriceTheme.type.caption, color = c.fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A grid cell of the 2 × 2 tools layout: equal width, min 48 dp high, icon left of the caption. */
@Composable
private fun RowScope.ToolCell(tool: Tool) {
    val c = LectriceTheme.colors
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = TOOL_CELL_MIN_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = null, indication = ripple(), role = Role.Button, onClick = tool.onClick)
            .semantics(mergeDescendants = true) { contentDescription = tool.contentDescription }
            .padding(horizontal = LectriceTheme.space.s),
        horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tool.top()
        Text(tool.caption, style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** R13s and the sleep sheet: a small sheet with one control; a choice applies and closes. */
@Composable
internal fun ListenSpeedSheet(speed: PlaybackSpeed, onPick: (PlaybackSpeed) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.sheet_speed)
    BottomSheet(onDismiss, contentDescription = title) {
        SheetHeader(title) { LectriceIconButton(LectriceIcons.Close, stringResource(R.string.common_close), onDismiss) }
        SegmentedControl(
            options = PlaybackSpeed.entries.map { it.label },
            selectedIndex = speed.ordinal,
            onSelect = { onPick(PlaybackSpeed.entries[it]) },
            modifier = Modifier.padding(horizontal = LectriceTheme.space.l),
            contentDescription = stringResource(R.string.sheet_speed_a11y, speed.label),
        )
        SheetBottomSpacer()
    }
}

@Composable
internal fun ListenSleepSheet(sleep: SleepTimer, hasToc: Boolean, onPick: (SleepTimer) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.sheet_sleep)
    BottomSheet(onDismiss, contentDescription = title) {
        SheetHeader(title) { LectriceIconButton(LectriceIcons.Close, stringResource(R.string.common_close), onDismiss) }
        SleepChips(sleep, hasToc, onPick, Modifier.padding(horizontal = LectriceTheme.space.l))
        Spacer(Modifier.height(LectriceTheme.space.s))
        SheetBottomSpacer()
    }
}

private const val CONTENT_FADE_START = 0.375f
private const val HALF = 0.5f
private const val BACK_SCALE = 0.1f
private const val CLOSE_SHARE = 0.3f
private const val LANDSCAPE_TEXT_SHARE = 0.55f
private const val FLASH_MS = 600
private const val FLASH_IN_MS = 120
private const val FLASH_OUT_MS = 480
private const val FLASH_ALPHA = 0.9f
private const val GLIDE_DIM = 0.6f
private const val NEIGHBOUR_SP = 20
private const val NEIGHBOUR_LINE_SP = 30
private const val NEIGHBOUR_LINES = 3
private const val CURRENT_SP = 28
private const val CURRENT_LINE_SP = 40
private const val LONG_SP = 24
private const val LONG_LINE_SP = 34
private const val MAX_CURRENT_LINES = 6
private const val BOX_LINES = 3
private const val TOOL_GRID_COLUMNS = 2
private val PILL_RADIUS = 28.dp
private val BACK_RADIUS = 32.dp
private val BACK_SHIFT = 24.dp
private val DRAG_SCALE_DISTANCE = 300.dp
private val FLING_VELOCITY = 1000.dp
private val TOP_BAR_HEIGHT = 56.dp
private val FADE_HEIGHT = 24.dp
private val GLIDE_DISTANCE = 40.dp
private val SWIPE_DISTANCE = 48.dp
private val SWIPE_SLOP = 8.dp
private val FLASH_SIZE = 72.dp
private val ZONE_BOTTOM = 40.dp
private val LOCKED_LIFT = 24.dp
private val SIDE_BUTTON = 64.dp
private val PLAY_BUTTON = 88.dp
private val TOOL_SIZE = 64.dp
private val TOOL_CELL_MIN_HEIGHT = 48.dp
private val TOOL_GRID_GAP = 8.dp
