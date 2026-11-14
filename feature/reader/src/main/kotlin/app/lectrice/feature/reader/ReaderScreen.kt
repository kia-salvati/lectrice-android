package app.lectrice.feature.reader

import android.app.KeyguardManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lectrice.core.designsystem.Banner
import app.lectrice.core.designsystem.BannerAction
import app.lectrice.core.designsystem.BannerKind
import app.lectrice.core.designsystem.HintTooltip
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSnackbar
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LinearProgress
import app.lectrice.core.designsystem.LocalReducedMotion
import app.lectrice.core.designsystem.ProgressLine
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.StatusBarScrim
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.motionSpring
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.PrepareScope
import app.lectrice.core.model.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private enum class ChipDirection { Up, Down }

/**
 * The reader (files 04–07, 15, 16): text, pill, banners, sheets, the Listen view and the pushed export page. Business
 * state is [state]; which sheet is open, follow-scroll, the collapsed pill and the Listen view are screen state.
 */
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    actions: ReaderActions,
    onBack: () -> Unit,
    /** The setting: only then can the window show over the lock screen, so only then are the lock marks drawn. */
    listenOverLockScreen: Boolean,
    onListenViewChanged: (Boolean) -> Unit,
    exportPage: @Composable (onBack: () -> Unit) -> Unit,
    /** File 23 B2/B4: "Use another file…" / "Find it again…"; the host opens the audio picker. */
    onPickAudiobook: () -> Unit = {},
) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    val ui = rememberReaderScreenState(onListenViewChanged)
    val scrubber = remember(density) { ScrubberState(density) }
    val listState = rememberLazyListState()
    val layouts = remember { ReaderLayouts() }

    val player = state.player
    val playing = player?.isPlaying == true
    val playingNow = rememberUpdatedState(playing)
    val content = state.content
    val ready = content as? ReaderContent.Ready
    val currentSentence = rememberUpdatedState(state.currentSentenceId)
    val currentBlock = rememberUpdatedState(state.currentBlockIndex)
    val tall = density.fontScale >= TALL_FONT_SCALE
    val band = if (tall) BAND_TALL else BAND
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }

    fun whenUnlocked(action: () -> Unit) {
        val host = activity
        if (!ui.locked || host == null || keyguard == null) return action()
        keyguard.requestDismissKeyguard(host, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                ui.locked = false
                action()
            }
        })
    }

    val scrollConnection = remember {
        object : NestedScrollConnection {
            private var accumulated = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                ui.follow = false
                ui.lastTouchMs = System.currentTimeMillis()
                accumulated = if ((accumulated < 0) != (available.y < 0)) available.y else accumulated + available.y
                val threshold = with(density) { COLLAPSE_SCROLL.toPx() }
                if (accumulated <= -threshold && playingNow.value && ui.sheet == ReaderSheet.None) ui.collapsed = true
                if (accumulated >= threshold) ui.collapsed = false
                return Offset.Zero
            }
        }
    }

    // Keyguard (file 06 §3.6): checked when the screen comes back and when the Listen view opens.
    val overLockScreen = rememberUpdatedState(listenOverLockScreen)
    fun keyguardUp() = overLockScreen.value && keyguard?.isKeyguardLocked == true
    LifecycleResumeEffect(keyguard) {
        ui.locked = keyguardUp()
        onPauseOrDispose { }
    }
    LaunchedEffect(ui.listenOpen, listenOverLockScreen) { if (ui.listenOpen) ui.locked = keyguardUp() }
    DisposableEffect(context, keyguard) {
        val stop = watchKeyguard(context) { unlocked -> ui.locked = !unlocked && keyguardUp() }
        onDispose { stop() }
    }

    // Pill collapse: 4 s without touching while playing (file 05 §3.3); pausing always expands.
    LaunchedEffect(playing, ui.sheet, ui.listenOpen, ui.lastTouchMs, ui.collapsed) {
        if (!playing) {
            ui.collapsed = false
            return@LaunchedEffect
        }
        if (ui.collapsed || ui.sheet != ReaderSheet.None || ui.listenOpen) return@LaunchedEffect
        delay(IDLE_COLLAPSE_MS)
        ui.collapsed = true
    }

    // Follow-scroll (file 04 §3.4).
    var initialDone by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentSentenceId, state.currentBlockIndex, state.followRequest, ui.follow, state.textSize, ready != null) {
        val blockIndex = state.currentBlockIndex ?: return@LaunchedEffect
        val sentenceId = state.currentSentenceId ?: return@LaunchedEffect
        if (ready == null || !ui.follow) return@LaunchedEffect
        val force = !initialDone
        scrollToSentence(listState, layouts, blockIndex, sentenceId, band, animate = initialDone && !reduced, force = force)
        initialDone = true
    }
    LaunchedEffect(state.followRequest) { if (state.followRequest > 0) ui.follow = true }
    val chipDirection by remember {
        derivedStateOf { if (ui.follow) null else sentencePosition(listState, layouts, currentBlock.value, currentSentence.value, band) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { chipDirection }.collect { if (it == null && !ui.follow && listState.layoutInfo.totalItemsCount > 0) ui.follow = true }
    }

    // Snackbar messages (shown 4 s).
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(SNACK_MS)
            actions.messageShown()
        }
    }
    LaunchedEffect(state.banner) {
        if (state.banner == null) ui.voiceFailedHidden = false
        if (state.banner !is ReaderBanner.SlowPhone) ui.tipHeight = 0.dp
    }

    val noVoice = state.voiceMissing || player?.status == PlaybackStatus.VoiceMissing
    val voiceFailed = player?.status == PlaybackStatus.VoiceFailed
    val statusTap: () -> Unit = {
        when {
            noVoice -> actions.openModelSetup()
            voiceFailed -> ui.voiceFailedHidden = false
            else -> {
                actions.pillHintDone()
                ui.openListen()
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .onGloballyPositioned { ui.origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    ui.lastTouchMs = System.currentTimeMillis()
                }
            },
    ) {
        val screenWidth = maxWidth
        val status = rememberPillStatus(player, state.voiceMissing, screenWidth, state.helper)
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val cutout = WindowInsets.displayCutout.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current
        val pillHeight = if (tall) PILL_HEIGHT_TALL else PILL_HEIGHT
        val abovePill = navInset + PILL_BOTTOM + pillHeight + 12.dp

        // Everything the open Listen view covers stays out of the accessibility tree.
        val listenCovers = state.listen != null && ui.listenFullyOpen
        Box(Modifier.fillMaxSize().then(if (listenCovers) Modifier.clearAndSetSemantics { } else Modifier)) {
            when (content) {
                ReaderContent.Loading -> LoadingView()
                ReaderContent.Recleaning -> RecleaningView()
                is ReaderContent.Failed -> FailedView(onBack)
                is ReaderContent.Ready -> ReaderText(
                    blocks = content.blocks,
                    textSize = state.textSize,
                    readSkipped = player?.readSkipped == true,
                    currentSentence = currentSentence,
                    currentBlock = currentBlock,
                    listState = listState,
                    layouts = layouts,
                    contentPadding = PaddingValues(
                        start = cutout.calculateStartPadding(layoutDirection),
                        top = statusInset + 24.dp,
                        end = cutout.calculateEndPadding(layoutDirection),
                        bottom = abovePill + 36.dp + ui.tipHeight,
                    ),
                    scrollConnection = scrollConnection,
                    onTapSentence = { ui.follow = true; actions.jumpToSentence(it) },
                    onTapBlock = { ui.follow = true; actions.tapBlock(it) },
                )
            }
            // Opaque behind the status bar, then a short fade, so the text never meets the clock.
            Column(Modifier.fillMaxWidth()) {
                StatusBarScrim()
                Box(Modifier.fillMaxWidth().height(STATUS_FADE).background(Brush.verticalGradient(listOf(c.bg, c.bg.copy(alpha = 0f)))))
            }

            if (ui.collapsed) ProgressLine(player?.bookPercent?.div(PERCENT) ?: 0f, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

            ReaderBanners(state, actions, ui, abovePill, chipDirection) {
                ui.follow = true
                scope.launch {
                    val blockIndex = state.currentBlockIndex ?: return@launch
                    val sentenceId = state.currentSentenceId ?: return@launch
                    scrollToSentence(listState, layouts, blockIndex, sentenceId, band, animate = !reduced, force = true)
                }
            }

            Pill(
                player = player,
                status = status,
                enabled = ready != null && player != null,
                collapsed = ui.collapsed,
                screenWidth = screenWidth,
                tall = tall,
                scrubber = scrubber,
                onPlayPause = actions::playPause,
                onPrevious = actions::previous,
                onNext = actions::next,
                onStatusTap = statusTap,
                onMore = { ui.sheet = ReaderSheet.Player },
                onChapters = { ui.sheet = ReaderSheet.Chapters },
                onDragUp = { ui.sheet = ReaderSheet.Player },
                onExpand = { ui.collapsed = false },
                onSetSpeed = actions::setSpeed,
                onGestureUsed = actions::pillHintDone,
                onBounds = { pill, _ -> ui.pillBounds = pill },
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = navInset + PILL_BOTTOM),
            )
        }

        ListenLayer(state, actions, ui, scrubber, ::whenUnlocked)
        ReaderSheets(state, actions, ui, scrubber, onPickAudiobook, exportPage)

        if (scrubber.open) {
            BackHandler { scrubber.close() }
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { scrubber.close() })
            SpeedScrubberOverlay(scrubber, origin = ui.origin)
        }
    }
}

/** What floats over the text: the voice-failed banner, the slow-phone tip, the back-to-current chip, the snackbar and the pill hint. */
@Composable
private fun BoxScope.ReaderBanners(state: ReaderUiState, actions: ReaderActions, ui: ReaderScreenState, abovePill: Dp, chipDirection: ChipDirection?, onBackToCurrent: () -> Unit) {
    val c = LectriceTheme.colors
    val density = LocalDensity.current
    val context = LocalContext.current
    val playing = state.player?.isPlaying == true
    val touchExploration = remember(context) { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }

    state.banner?.let { banner ->
        when (banner) {
            ReaderBanner.VoiceFailed -> if (!ui.voiceFailedHidden) {
                Banner(BannerKind.Err, LectriceIcons.Error, stringResource(R.string.banner_voice_failed), Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    BannerAction(stringResource(R.string.reader_try_again), BannerKind.Err, actions::bannerAction)
                    LectriceIconButton(LectriceIcons.Close, stringResource(R.string.banner_dismiss), { ui.voiceFailedHidden = true }, tint = c.errFg, size = 40.dp)
                }
            }
            is ReaderBanner.SlowPhone -> SlowPhoneTip(
                banner,
                actions,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = abovePill)
                    .onSizeChanged { ui.tipHeight = with(density) { it.height.toDp() } },
            )
        }
    }

    chipDirection?.let { direction ->
        BackToCurrentChip(direction, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = abovePill), onBackToCurrent)
    }

    state.message?.let { message ->
        val text = when (message) {
            ReaderMessage.ComputerLost -> stringResource(R.string.reader_snack_computer_lost)
            ReaderMessage.PrepareQueued -> stringResource(R.string.reader_snack_prepare_queued)
            ReaderMessage.BookGone -> stringResource(R.string.reader_snack_book_gone)
            is ReaderMessage.SentenceSkipped -> stringResource(R.string.reader_snack_skipped)
            is ReaderMessage.AudiobookFailed -> stringResource(message.name.audiobookMessage())
        }
        LectriceSnackbar(text, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = abovePill))
    }

    LaunchedEffect(playing, ui.collapsed, state.pillHintSeen, ui.sheet, ui.listenOpen) {
        ui.hintShown = false
        if (state.pillHintSeen || touchExploration || !playing || ui.collapsed || ui.sheet != ReaderSheet.None || ui.listenOpen) return@LaunchedEffect
        delay(HINT_DELAY_MS)
        ui.hintShown = true
    }
    if (ui.hintShown) {
        HintTooltip(stringResource(R.string.pill_hint), stringResource(R.string.hint_got_it), actions::pillHintDone, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = abovePill))
    }
}

@Composable
private fun ListenLayer(state: ReaderUiState, actions: ReaderActions, ui: ReaderScreenState, scrubber: ScrubberState, whenUnlocked: (action: () -> Unit) -> Unit) {
    if (!ui.listenOpen || state.listen == null) return
    ListenView(
        state = state,
        actions = actions,
        scrubber = scrubber,
        openProgress = ui.listenProgress.value,
        pillBounds = ui.pillBounds,
        origin = ui.origin,
        locked = ui.locked,
        backEnabled = ui.sheet == ReaderSheet.None && !scrubber.open,
        handlers = remember(ui.locked) {
            ListenHandlers(
                onClose = ui::closeListen,
                onSpeed = { ui.sheet = ReaderSheet.Speed },
                onSleep = { ui.sheet = ReaderSheet.Sleep },
                onChapters = { ui.sheet = ReaderSheet.Chapters },
                onMore = { ui.sheet = ReaderSheet.Player },
                onHintDone = actions::listenHintDone,
                whenUnlocked = whenUnlocked,
            )
        },
    )
}

@Composable
private fun ReaderSheets(state: ReaderUiState, actions: ReaderActions, ui: ReaderScreenState, scrubber: ScrubberState, onPickAudiobook: () -> Unit, exportPage: @Composable (onBack: () -> Unit) -> Unit) {
    val player = state.player
    when (ui.sheet) {
        ReaderSheet.None -> Unit
        ReaderSheet.Player -> {
            BackHandler(enabled = !ui.exportOpen) { ui.closeSheet() }
            PlayerSheet(
                state = state,
                actions = actions,
                scrubber = scrubber,
                exportOpen = ui.exportOpen,
                onOpenExport = { ui.exportOpen = true },
                onCloseExport = { ui.exportOpen = false },
                onChapters = { ui.sheet = ReaderSheet.Chapters },
                onAudioSource = { ui.exportOpen = false; ui.sheet = ReaderSheet.AudioSource },
                onLibrary = { ui.closeSheet(); ui.dropListen(); actions.openLibrary() },
                onDismiss = ui::closeSheet,
                exportPage = exportPage,
            )
        }
        ReaderSheet.Chapters -> {
            BackHandler { ui.sheet = ReaderSheet.None }
            ChaptersSheet(
                outline = (state.content as? ReaderContent.Ready)?.outline.orEmpty(),
                search = actions::searchOutline,
                onPick = { row -> ui.follow = true; actions.jumpToSentence(row.sentenceId); ui.sheet = ReaderSheet.None },
                onDismiss = { ui.sheet = ReaderSheet.None },
            )
        }
        ReaderSheet.Speed -> {
            BackHandler { ui.sheet = ReaderSheet.None }
            ListenSpeedSheet(player?.speed ?: PlaybackSpeed.X1, onPick = { actions.setSpeed(it); ui.sheet = ReaderSheet.None }, onDismiss = { ui.sheet = ReaderSheet.None })
        }
        ReaderSheet.Sleep -> {
            BackHandler { ui.sheet = ReaderSheet.None }
            ListenSleepSheet(player?.sleep ?: SleepTimer.Off, player?.hasToc == true, onPick = { actions.setSleep(it); ui.sheet = ReaderSheet.None }, onDismiss = { ui.sheet = ReaderSheet.None })
        }
        ReaderSheet.AudioSource -> {
            val audiobook = player?.audiobook
            BackHandler { ui.sheet = ReaderSheet.None }
            LaunchedEffect(audiobook == null) { if (audiobook == null) ui.sheet = ReaderSheet.None }
            if (audiobook != null) {
                AudioSourceSheet(
                    audiobook = audiobook,
                    onSelect = { actions.setAudioSource(it); ui.sheet = ReaderSheet.None },
                    onPickAudiobook = onPickAudiobook,
                    onRemoveLink = { actions.removeAudiobookLink(); ui.sheet = ReaderSheet.None },
                    onDismiss = { ui.sheet = ReaderSheet.None },
                )
            }
        }
    }
}

@Composable
private fun LoadingView() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MS)
        show = true
    }
    if (show) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
}

@Composable
private fun RecleaningView() {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.reader_recleaning), style = LectriceTheme.type.body, color = LectriceTheme.colors.fg2)
        LinearProgress(null)
    }
}

@Composable
private fun FailedView(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.reader_failed), style = LectriceTheme.type.body, color = LectriceTheme.colors.errFg)
        LectriceTextButton(stringResource(R.string.reader_try_again), onBack)
    }
}

/** R7 (file 12 §3.3): the slow-phone tip above the pill, dismissible, with "Set {best}" and the optional Prepare button. */
@Composable
private fun SlowPhoneTip(banner: ReaderBanner.SlowPhone, actions: ReaderActions, modifier: Modifier) {
    val c = LectriceTheme.colors
    val smooth = banner.best?.let { best -> if (best > PlaybackSpeed.X1) stringResource(R.string.banner_slow_phone_or, best.label) else best.label }
    val text = stringResource(R.string.banner_slow_phone, banner.speed.label) + (smooth?.let { " " + stringResource(R.string.banner_slow_phone_smooth, it) } ?: "")
    Banner(BannerKind.Warn, LectriceIcons.Warning, text, modifier) {
        banner.best?.let { best -> BannerAction(stringResource(R.string.banner_set_speed, best.label), BannerKind.Warn, actions::bannerAction) }
        if (banner.canPrepare) BannerAction(stringResource(R.string.banner_prepare), BannerKind.Warn) { actions.prepare(PrepareScope.ThisChapter); actions.dismissBanner() }
        LectriceIconButton(LectriceIcons.Close, stringResource(R.string.reader_tip_close), actions::dismissBanner, tint = c.warnFg, iconSize = TIP_CLOSE_ICON)
    }
}

/** R2: right 16, 12 above the pill; 40 dp visible in a 48 dp target; ↓ or ↑. */
@Composable
private fun BackToCurrentChip(direction: ChipDirection, modifier: Modifier, onClick: () -> Unit) {
    val c = LectriceTheme.colors
    val label = stringResource(R.string.reader_back_to_current_a11y)
    Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(c.bg2)
                .border(1.dp, c.line, CircleShape)
                .clickable(interactionSource = null, indication = ripple(), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(start = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LectriceIcon(if (direction == ChipDirection.Down) LectriceIcons.ArrowDownward else LectriceIcons.ArrowUpward, null, size = 20.dp)
            Text(stringResource(R.string.reader_back_to_current), style = LectriceTheme.type.label)
        }
    }
}

/** The viewport band (fractions of its height) inside which the current sentence may sit without a scroll. */
private class Band(val low: Float, val high: Float, val target: Float)

/** Where the current sentence's line top is relative to the band: above (↑), below (↓) or inside (null). */
private fun sentencePosition(listState: LazyListState, layouts: ReaderLayouts, blockIndex: Int?, sentenceId: Int?, band: Band): ChipDirection? {
    if (blockIndex == null || sentenceId == null) return null
    val info = listState.layoutInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return null
    val item = info.visibleItemsInfo.firstOrNull { it.index == blockIndex }
    if (item == null) return if (blockIndex < (info.visibleItemsInfo.firstOrNull()?.index ?: 0)) ChipDirection.Up else ChipDirection.Down
    val top = item.offset + (layouts.sentenceTop(blockIndex, sentenceId) ?: 0)
    return when {
        top < viewport * band.low -> ChipDirection.Up
        top > viewport * band.high -> ChipDirection.Down
        else -> null
    }
}

/** Puts the sentence's first line at the band's target (35 %) when it is outside the band, or always when [force]. */
private suspend fun scrollToSentence(listState: LazyListState, layouts: ReaderLayouts, blockIndex: Int, sentenceId: Int, band: Band, animate: Boolean, force: Boolean) {
    val info = listState.layoutInfo
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return
    var item = info.visibleItemsInfo.firstOrNull { it.index == blockIndex }
    if (item == null) {
        listState.scrollToItem(blockIndex, -(viewport * band.target).roundToInt())
        withTimeoutOrNull(LAYOUT_WAIT_MS) { snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == blockIndex } }.first { it } }
        item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == blockIndex } ?: return
    }
    val top = item.offset + (layouts.sentenceTop(blockIndex, sentenceId) ?: 0)
    if (!force && top >= viewport * band.low && top <= viewport * band.high) return
    val delta = top - viewport * band.target
    if (animate) listState.animateScrollBy(delta, motionSpring(false)) else listState.scrollBy(delta)
}

private val BAND = Band(0.2f, 0.6f, 0.35f)
private val BAND_TALL = Band(0.15f, 0.5f, 0.35f)
internal const val TALL_FONT_SCALE = 2f
private const val PERCENT = 100f
private const val IDLE_COLLAPSE_MS = 4_000L
private const val HINT_DELAY_MS = 3_000L
private const val SNACK_MS = 4_000L
private const val SPINNER_DELAY_MS = 150L
private const val LAYOUT_WAIT_MS = 500L
private val COLLAPSE_SCROLL = 24.dp
private val STATUS_FADE = 12.dp
private val TIP_CLOSE_ICON = 20.dp
