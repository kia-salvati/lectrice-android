package app.lectrice.feature.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.BookSlider
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.ChoiceChip
import app.lectrice.core.designsystem.Divider
import app.lectrice.core.designsystem.Equalizer
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSwitch
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.ListRow
import app.lectrice.core.designsystem.LocalReducedMotion
import app.lectrice.core.designsystem.PlayButton
import app.lectrice.core.designsystem.RadioMark
import app.lectrice.core.designsystem.SectionHeader
import app.lectrice.core.designsystem.SegmentedControl
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.SheetDetent
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.motionSpring
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.PrepareScope
import app.lectrice.core.model.SleepTimer
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceSampleState

/**
 * R4 (file 05 §3.6): transport, book slider, chapter line, speed, voice, sleep, read code, export, library. Opens at
 * the half detent; the export page is pushed over it at the full detent.
 */
@Composable
internal fun PlayerSheet(
    state: ReaderUiState,
    actions: ReaderActions,
    scrubber: ScrubberState,
    exportOpen: Boolean,
    onOpenExport: () -> Unit,
    onCloseExport: () -> Unit,
    onChapters: () -> Unit,
    onAudioSource: () -> Unit,
    onLibrary: () -> Unit,
    onDismiss: () -> Unit,
    exportPage: @Composable (onBack: () -> Unit) -> Unit,
) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val halfHeight = minOf(HALF_MAX, screenHeight * HALF_SHARE)
    val handlePx = with(density) { HANDLE_HEIGHT.roundToPx() }
    BottomSheet(
        onDismiss = onDismiss,
        contentDescription = stringResource(R.string.sheet_player_a11y),
        fullHeight = true,
        halfDetent = true,
        halfHeight = halfHeight,
        initialDetent = SheetDetent.Half,
        targetDetent = if (exportOpen) SheetDetent.Full else null,
    ) {
        val reveal by animateFloatAsState(if (exportOpen) 0f else 1f, motionSpring(reduced), label = "exportPage")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Fully covered by the export page: out of the accessibility tree until the page leaves.
            val covered = exportOpen && reveal == 0f
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)) {
                PlayerContent(state, actions, scrubber, onOpenExport, onChapters, onAudioSource, onLibrary)
            }
            // Slides in from the right over the sheet, covering its handle too (the mockup's `.page`).
            if (exportOpen || reveal < 1f) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(maxHeight = constraints.maxHeight + handlePx, minHeight = 0))
                            layout(placeable.width, constraints.maxHeight) { placeable.place(0, -handlePx) }
                        }
                        .graphicsLayer { translationX = size.width * reveal }
                        .fillMaxSize()
                        .background(c.bg2),
                ) { exportPage(onCloseExport) }
            }
        }
    }
}

@Composable
private fun ColumnScope.PlayerContent(state: ReaderUiState, actions: ReaderActions, scrubber: ScrubberState, onOpenExport: () -> Unit, onChapters: () -> Unit, onAudioSource: () -> Unit, onLibrary: () -> Unit) {
    val c = LectriceTheme.colors
    val player = state.player
    val enabled = player != null && player.status != PlaybackStatus.VoiceMissing && player.status != PlaybackStatus.VoiceFailed && !state.voiceMissing
    val playing = player?.isPlaying == true
    val preparing = player?.status == PlaybackStatus.Preparing || player?.status == PlaybackStatus.CatchingUp
    val speed = player?.speed ?: PlaybackSpeed.X1
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    var prepareOpen by rememberSaveable { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        LectriceIconButton(LectriceIcons.KeyboardDoubleArrowLeft, stringResource(R.string.sheet_previous_section), actions::previousSection, enabled = enabled && player.canPreviousSection)
        LectriceIconButton(LectriceIcons.SkipPrevious, stringResource(R.string.pill_previous), actions::previous, enabled = enabled)
        PlayButton(
            playing = playing,
            onClick = actions::playPause,
            contentDescription = stringResource(if (playing) R.string.pill_pause else R.string.pill_play),
            modifier = Modifier.longPressScrubber(scrubber, speed, actions::setSpeed),
            size = 64.dp,
            discSize = 56.dp,
            preparing = preparing,
        )
        LectriceIconButton(LectriceIcons.SkipNext, stringResource(R.string.pill_next), actions::next, enabled = enabled)
        LectriceIconButton(LectriceIcons.KeyboardDoubleArrowRight, stringResource(R.string.sheet_next_section), actions::nextSection, enabled = enabled && player.canNextSection)
    }

    if (player != null) {
        BookSlider(
            fraction = player.bookFraction,
            bufferedFraction = player.bufferedFraction,
            ticks = player.chapterTicks,
            label = actions::seekLabel,
            onSeek = actions::seekTo,
            contentDescription = stringResource(R.string.sheet_slider_a11y),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(player.sliderCaption, style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1)
            Text(player.bookTimeLeft, style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onChapters)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(player.chapterLineTitle, style = LectriceTheme.type.labelL, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(player.chapterLineDetail, style = LectriceTheme.type.bodyS, color = c.fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                LectriceIcon(LectriceIcons.Toc, null, tint = c.accent)
                Text(stringResource(R.string.sheet_chapters), style = LectriceTheme.type.label, color = c.accent)
            }
        }
    }

    Text(stringResource(R.string.sheet_speed), Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), style = LectriceTheme.type.sectionHeader, color = c.fg)
    SegmentedControl(
        options = PlaybackSpeed.entries.map { it.label },
        selectedIndex = speed.ordinal,
        onSelect = { actions.setSpeed(PlaybackSpeed.entries[it]) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        contentDescription = stringResource(R.string.sheet_speed_a11y, speed.label),
    )

    val audiobook = player?.audiobook
    val fromFile = audiobook?.active == true
    val fromAudiobook = stringResource(R.string.sheet_from_audiobook)
    val voiceTitle = stringResource(R.string.sheet_voice)
    if (fromFile) {
        ListRow(voiceTitle, Modifier.alpha(DISABLED_ALPHA), subtitle = fromAudiobook, leadingIcon = LectriceIcons.RecordVoiceOver, trailing = { ExpandIcon(false) })
    } else {
        ListRow(voiceTitle, subtitle = player?.voice?.summary, leadingIcon = LectriceIcons.RecordVoiceOver, onClick = { voiceOpen = !voiceOpen }, trailing = { ExpandIcon(voiceOpen) })
    }
    if (voiceOpen && !fromFile) {
        if (state.voices.isEmpty()) {
            Text(stringResource(R.string.sheet_voice_none), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LectriceTextButton(stringResource(R.string.sheet_voice_download), actions::openModelSetup) }
        } else {
            VoiceRows(state.voices, player?.voice?.id, state.voiceSample, onPick = { actions.setVoice(it); voiceOpen = false }, onSample = actions::playVoiceSample)
        }
    }
    Divider(inset = 0.dp)

    if (audiobook != null) {
        ListRow(
            stringResource(R.string.sheet_audio),
            subtitle = if (audiobook.active) stringResource(R.string.sheet_audio_file, audiobook.followsText()) else stringResource(R.string.audio_source_generated),
            leadingIcon = LectriceIcons.Headphones,
            onClick = onAudioSource,
            trailing = { LectriceIcon(LectriceIcons.ChevronRight, null, tint = c.fg2) },
        )
        Divider(inset = 0.dp)
    }

    state.helper?.let { helper ->
        ListRow(
            stringResource(R.string.sheet_helper),
            subtitle = helper.text,
            leadingIcon = LectriceIcons.Computer,
            subtitleColor = if (helper.needsAttention) c.warnFg else c.fg2,
            onClick = if (helper.needsAttention) null else actions::openComputerHelper,
            trailing = {
                if (helper.needsAttention) {
                    LectriceTextButton(stringResource(R.string.sheet_helper_fix), actions::openComputerHelper, style = LectriceTheme.type.label)
                } else {
                    LectriceIcon(LectriceIcons.ChevronRight, null, tint = c.fg2)
                }
            },
        )
        Divider(inset = 0.dp)
    }

    ListRow(stringResource(R.string.sheet_sleep), subtitle = player?.sleepSummary, leadingIcon = LectriceIcons.Bedtime, onClick = { sleepOpen = !sleepOpen }, trailing = { ExpandIcon(sleepOpen) })
    if (sleepOpen && player != null) SleepChips(player.sleep, player.hasToc, actions::setSleep, Modifier.padding(horizontal = 16.dp))
    Divider(inset = 0.dp)

    val readCode = stringResource(R.string.sheet_read_code)
    ListRow(
        readCode,
        if (fromFile) Modifier.alpha(DISABLED_ALPHA) else Modifier,
        subtitle = if (fromFile) fromAudiobook else null,
        leadingIcon = LectriceIcons.MenuBook,
        trailing = { LectriceSwitch(player?.readSkipped == true, actions::setReadSkipped, contentDescription = readCode, enabled = !fromFile) },
    )
    Divider(inset = 0.dp)

    state.prepare?.let { prepare ->
        ListRow(
            prepare.onComputer?.let { stringResource(R.string.sheet_prepare_on, it) } ?: stringResource(R.string.sheet_prepare),
            subtitle = prepare.summary.text(),
            leadingIcon = if (prepare.onComputer != null) LectriceIcons.Bolt else LectriceIcons.BatteryFull,
            onClick = { prepareOpen = !prepareOpen },
            trailing = { ExpandIcon(prepareOpen) },
        )
        if (prepareOpen) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prepare.active) {
                    LectriceTextButton(stringResource(R.string.sheet_prepare_cancel), { actions.cancelPrepare(); prepareOpen = false })
                } else {
                    ChoiceChip(stringResource(R.string.sheet_prepare_this_chapter), false, { actions.prepare(PrepareScope.ThisChapter); prepareOpen = false })
                    ChoiceChip(stringResource(R.string.sheet_prepare_next_three), false, { actions.prepare(PrepareScope.NextThreeChapters); prepareOpen = false })
                    ChoiceChip(stringResource(R.string.sheet_prepare_as_much), false, { actions.prepare(PrepareScope.AsMuchAsFits); prepareOpen = false })
                }
            }
        }
        Divider(inset = 0.dp)
    }

    val exportEnabled = !state.voiceMissing
    ListRow(
        stringResource(R.string.sheet_export),
        subtitle = stringResource(if (exportEnabled) R.string.sheet_export_sub else R.string.sheet_export_needs_voice),
        leadingIcon = LectriceIcons.Download,
        titleColor = if (exportEnabled) c.fg else c.fg2,
        onClick = if (exportEnabled) onOpenExport else null,
        trailing = { LectriceIcon(LectriceIcons.ChevronRight, null, tint = c.fg2) },
    )
    Divider(inset = 0.dp)
    ListRow(stringResource(R.string.sheet_library), leadingIcon = LectriceIcons.MenuBook, onClick = onLibrary, trailing = { LectriceIcon(LectriceIcons.ChevronRight, null, tint = c.fg2) })
    SheetBottomSpacer()
}

@Composable
internal fun ExpandIcon(open: Boolean) {
    LectriceIcon(if (open) LectriceIcons.ExpandLess else LectriceIcons.ExpandMore, null, tint = LectriceTheme.colors.fg2)
}

/**
 * File 16 §2: the current voice first, then the rest of its group, then "All N voices" expands to every voice grouped
 * by language and gender. Each row has a 48 dp sample button.
 */
@Composable
internal fun VoiceRows(voices: List<Voice>, selectedId: String?, sample: VoiceSampleState, onPick: (String) -> Unit, onSample: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val current = voices.firstOrNull { it.id == selectedId } ?: voices.first()
    if (all) {
        voices.groupBy { it.longSummary }.forEach { (group, members) ->
            SectionHeader(group)
            members.forEach { VoiceRow(it, it.id == current.id, sample.phaseOf(it.id), onPick, onSample) }
        }
    } else {
        val shown = listOf(current) + voices.filter { it.longSummary == current.longSummary && it.id != current.id }.take(GROUP_PREVIEW - 1)
        shown.forEach { VoiceRow(it, it.id == current.id, sample.phaseOf(it.id), onPick, onSample) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LectriceTextButton(stringResource(R.string.sheet_voice_all, voices.size), { all = true })
        }
    }
}

@Composable
private fun VoiceRow(voice: Voice, selected: Boolean, samplePhase: VoiceSampleState.Phase, onPick: (String) -> Unit, onSample: (String) -> Unit) {
    val c = LectriceTheme.colors
    val color = if (selected) c.onAccentContainer else c.fg
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(c.accentContainer) else Modifier)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.RadioButton) { onPick(voice.id) }
                .semantics { contentDescription = voice.longSummary.let { "${voice.name}, $it" } },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioMark(selected, selectedColor = if (selected) c.onAccentContainer else c.accent)
            Text(voice.name, style = LectriceTheme.type.body, color = color, maxLines = 1)
            Text(voice.longSummary, Modifier.weight(1f, fill = false), style = LectriceTheme.type.rowMeta, color = if (selected) c.onAccentContainer else c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SampleButton(voice, samplePhase, color) { onSample(voice.id) }
    }
}

/** The 48 dp sample button (file 16): speaker when idle, a spinner while the clip is made, the equaliser while it plays; always tappable. */
@Composable
private fun SampleButton(voice: Voice, phase: VoiceSampleState.Phase, tint: Color, onClick: () -> Unit) {
    val description = when (phase) {
        VoiceSampleState.Phase.Idle -> stringResource(R.string.sheet_voice_hear, voice.name)
        VoiceSampleState.Phase.Loading -> stringResource(R.string.sheet_voice_making, voice.name)
        VoiceSampleState.Phase.Playing -> stringResource(R.string.sheet_voice_playing, voice.name)
    }
    Box(
        Modifier
            .size(SAMPLE_BUTTON)
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = ripple(bounded = false, radius = SAMPLE_RIPPLE), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            VoiceSampleState.Phase.Idle -> LectriceIcon(LectriceIcons.VolumeUp, null, tint = tint)
            VoiceSampleState.Phase.Loading -> Spinner(color = tint)
            VoiceSampleState.Phase.Playing -> Equalizer(tint = tint)
        }
    }
}

/** File 15: Off · 15 min · 30 min · 45 min · 1 h · End of chapter (disabled without a TOC). */
@Composable
internal fun SleepChips(sleep: SleepTimer, hasToc: Boolean, onSet: (SleepTimer) -> Unit, modifier: Modifier = Modifier) {
    val selectedMinutes = (sleep as? SleepTimer.Minutes)?.totalMinutes
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip(stringResource(R.string.sleep_off), sleep == SleepTimer.Off, { onSet(SleepTimer.Off) })
        SLEEP_MINUTES.forEach { minutes ->
            val label = if (minutes == MINUTES_PER_HOUR) stringResource(R.string.sleep_hour) else stringResource(R.string.sleep_minutes, minutes)
            ChoiceChip(label, selectedMinutes == minutes, { onSet(SleepTimer.Minutes(minutes, minutes * MS_PER_MINUTE)) })
        }
        ChoiceChip(stringResource(R.string.sleep_end_of_chapter), sleep == SleepTimer.EndOfChapter, { onSet(SleepTimer.EndOfChapter) }, enabled = hasToc)
    }
}

private val SLEEP_MINUTES = listOf(15, 30, 45, 60)
private const val MINUTES_PER_HOUR = 60
private const val MS_PER_MINUTE = 60_000L
private const val GROUP_PREVIEW = 6
private const val HALF_SHARE = 0.7f
private const val DISABLED_ALPHA = 0.6f
private val HALF_MAX = 600.dp
private val HANDLE_HEIGHT = 48.dp
private val SAMPLE_BUTTON = 48.dp
private val SAMPLE_RIPPLE = 20.dp
