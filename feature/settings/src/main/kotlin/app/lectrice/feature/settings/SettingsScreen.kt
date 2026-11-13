package app.lectrice.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.ChevronRight
import app.lectrice.core.designsystem.Equalizer
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSwitch
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.ListRow
import app.lectrice.core.designsystem.RadioMark
import app.lectrice.core.designsystem.SectionHeader
import app.lectrice.core.designsystem.SegmentedControl
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.SheetHeader
import app.lectrice.core.designsystem.StatusBarScrim
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.StepSlider
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.TopBar
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.ReadAheadMinutes
import app.lectrice.core.model.TextSize
import app.lectrice.core.model.ThemeSetting
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceSampleState

private enum class SettingsSheet { None, Voice, Speed, ReadAhead }

/** T1 (file 11 §3.1). Which sheet is open is screen state; every value comes from [state]. */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit) {
    val c = LectriceTheme.colors
    var sheet by rememberSaveable { mutableStateOf(SettingsSheet.None) }
    BackHandler(enabled = sheet != SettingsSheet.None) { sheet = SettingsSheet.None }
    val settings = state.settings
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(stringResource(R.string.settings_title), onBack, stringResource(R.string.common_back))

            SectionHeader(stringResource(R.string.settings_listening))
            ListRow(stringResource(R.string.settings_default_voice), subtitle = state.defaultVoiceSummary, onClick = { sheet = SettingsSheet.Voice }, trailing = { ChevronRight() })
            ListRow(stringResource(R.string.settings_default_speed), subtitle = settings.defaultSpeed.label, onClick = { sheet = SettingsSheet.Speed }, trailing = { ChevronRight() })
            val readCode = stringResource(R.string.settings_read_code)
            ListRow(readCode, trailing = { LectriceSwitch(settings.readSkipped, actions::setReadSkipped, contentDescription = readCode) })
            ListRow(
                stringResource(R.string.settings_read_ahead),
                subtitle = stringResource(R.string.settings_read_ahead_value, settings.readAhead.minutes),
                onClick = { sheet = SettingsSheet.ReadAhead },
                trailing = { ChevronRight() },
            )
            val lockSentence = stringResource(R.string.settings_lock_sentence)
            ListRow(lockSentence, subtitle = stringResource(R.string.settings_lock_sentence_sub), trailing = { LectriceSwitch(settings.sentenceOnLockScreen, actions::setSentenceOnLockScreen, contentDescription = lockSentence) })
            val lockStage = stringResource(R.string.settings_lock_stage)
            ListRow(lockStage, subtitle = stringResource(R.string.settings_lock_stage_sub), trailing = { LectriceSwitch(settings.listenOverLockScreen, actions::setListenOverLockScreen, contentDescription = lockStage) })

            SectionHeader(stringResource(R.string.settings_reading))
            TextSizeControl(settings.textSize, actions::setTextSize)
            Text(stringResource(R.string.settings_theme), Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp), style = LectriceTheme.type.labelL)
            SegmentedControl(
                options = listOf(R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark, R.string.settings_theme_black).map { stringResource(it) },
                selectedIndex = settings.theme.ordinal,
                onSelect = { actions.setTheme(ThemeSetting.entries[it]) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                contentDescription = stringResource(R.string.settings_theme_a11y),
            )

            SectionHeader(stringResource(R.string.settings_voice_model))
            ListRow(state.modelSummary, onClick = actions::openModelPage, trailing = { ChevronRight() })

            SectionHeader(stringResource(R.string.settings_storage))
            ListRow(stringResource(R.string.settings_storage), subtitle = state.storageSummary ?: stringResource(R.string.common_loading), onClick = actions::openStorage, trailing = { ChevronRight() })

            SectionHeader(stringResource(R.string.settings_battery))
            ListRow(stringResource(R.string.settings_keep_exports), subtitle = stringResource(R.string.settings_keep_exports_sub), onClick = actions::openBatterySettings, trailing = { ChevronRight() })

            SectionHeader(stringResource(R.string.settings_about))
            val copyLabel = stringResource(R.string.settings_copy_version)
            ListRow(
                state.versionLine,
                onClick = {},
                onLongClick = actions::copyVersion,
                customActions = listOf(CustomAccessibilityAction(copyLabel) { actions.copyVersion(); true }),
            )
            ListRow(stringResource(R.string.settings_licenses), onClick = actions::openLicenses, trailing = { ChevronRight() })
            ListRow(stringResource(R.string.settings_source), onClick = actions::openSourceCode, trailing = { ChevronRight() })
            Spacer(Modifier.navigationBarsPadding().height(48.dp))
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
        when (sheet) {
            SettingsSheet.None -> Unit
            SettingsSheet.Voice -> VoiceSheet(state.voices, settings.defaultVoiceId, state.voiceSample, onPick = { actions.setDefaultVoice(it); sheet = SettingsSheet.None }, onSample = actions::playVoiceSample, onDismiss = { sheet = SettingsSheet.None })
            SettingsSheet.Speed -> SpeedSheet(settings.defaultSpeed, onPick = { actions.setDefaultSpeed(it); sheet = SettingsSheet.None }, onDismiss = { sheet = SettingsSheet.None })
            SettingsSheet.ReadAhead -> ReadAheadSheet(settings.readAhead, onPick = { actions.setReadAhead(it); sheet = SettingsSheet.None }, onDismiss = { sheet = SettingsSheet.None })
        }
    }
}

/** The 5-stop slider with its S · M · L · XL · XXL labels and the live serif preview (file 04 §3.8). */
@Composable
private fun TextSizeControl(size: TextSize, onChange: (TextSize) -> Unit) {
    val c = LectriceTheme.colors
    val label = stringResource(R.string.settings_text_size)
    Text(label, Modifier.padding(horizontal = 16.dp), style = LectriceTheme.type.labelL)
    StepSlider(TextSize.entries.size, size.ordinal, { onChange(TextSize.entries[it]) }, Modifier.padding(horizontal = 16.dp), contentDescription = label)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextSize.entries.forEach { stop ->
            val current = stop == size
            Text(stop.name, style = LectriceTheme.type.caption.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal), color = if (current) c.fg else c.fg2)
        }
    }
    val previewSp = (size.paragraphSp + PREVIEW_EXTRA_SP).sp
    Text(
        stringResource(R.string.settings_preview),
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = LectriceTheme.type.serif(previewSp, previewSp * PREVIEW_LINE_HEIGHT),
    )
}

@Composable
private fun VoiceSheet(voices: List<Voice>, selectedId: String, sample: VoiceSampleState, onPick: (String) -> Unit, onSample: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    val title = stringResource(R.string.settings_default_voice)
    BottomSheet(onDismiss, contentDescription = title, fullHeight = true, halfDetent = true) {
        SheetHeader(title)
        LazyColumn(Modifier.fillMaxSize()) {
            voices.groupBy { it.longSummary }.forEach { (group, members) ->
                item(group) { SectionHeader(group) }
                items(members, key = { it.id }) { voice ->
                    val selected = voice.id == selectedId
                    ListRow(
                        voice.name,
                        modifier = if (selected) Modifier.background(c.accentContainer) else Modifier,
                        titleColor = if (selected) c.onAccentContainer else c.fg,
                        onClick = { onPick(voice.id) },
                        leading = { RadioMark(selected, selectedColor = if (selected) c.onAccentContainer else c.accent) },
                        trailing = { SampleButton(voice, sample.phaseOf(voice.id), if (selected) c.onAccentContainer else c.fg) { onSample(voice.id) } },
                    )
                }
            }
            item("bottom") { SheetBottomSpacer() }
        }
    }
}

/** The 48 dp sample button (file 16): speaker when idle, a spinner while the clip is made, the equaliser while it plays; always tappable. */
@Composable
private fun SampleButton(voice: Voice, phase: VoiceSampleState.Phase, tint: Color, onClick: () -> Unit) {
    val description = when (phase) {
        VoiceSampleState.Phase.Idle -> stringResource(R.string.settings_hear_voice, voice.name)
        VoiceSampleState.Phase.Loading -> stringResource(R.string.settings_voice_making, voice.name)
        VoiceSampleState.Phase.Playing -> stringResource(R.string.settings_voice_playing, voice.name)
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

@Composable
private fun SpeedSheet(speed: PlaybackSpeed, onPick: (PlaybackSpeed) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.settings_default_speed)
    BottomSheet(onDismiss, contentDescription = title) {
        SheetHeader(title)
        SegmentedControl(
            options = PlaybackSpeed.entries.map { it.label },
            selectedIndex = speed.ordinal,
            onSelect = { onPick(PlaybackSpeed.entries[it]) },
            modifier = Modifier.padding(horizontal = 16.dp),
            contentDescription = stringResource(R.string.settings_speed_a11y),
        )
        SheetBottomSpacer()
    }
}

@Composable
private fun ReadAheadSheet(current: ReadAheadMinutes, onPick: (ReadAheadMinutes) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.settings_read_ahead)
    BottomSheet(onDismiss, contentDescription = title) {
        SheetHeader(title)
        ReadAheadMinutes.entries.forEach { choice ->
            ListRow(
                stringResource(R.string.settings_read_ahead_value, choice.minutes),
                onClick = { onPick(choice) },
                leading = { RadioMark(choice == current) },
            )
        }
        SheetBottomSpacer()
    }
}

private const val PREVIEW_EXTRA_SP = 2
private val SAMPLE_BUTTON = 48.dp
private val SAMPLE_RIPPLE = 20.dp
private const val PREVIEW_LINE_HEIGHT = 1.4f
