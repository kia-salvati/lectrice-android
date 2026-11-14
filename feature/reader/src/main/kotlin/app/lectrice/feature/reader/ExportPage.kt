package app.lectrice.feature.reader

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.Banner
import app.lectrice.core.designsystem.BannerKind
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.Divider
import app.lectrice.core.designsystem.LectriceDialog
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSnackbar
import app.lectrice.core.designsystem.LectriceSwitch
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LinearProgress
import app.lectrice.core.designsystem.PrimaryButton
import app.lectrice.core.designsystem.RadioMark
import app.lectrice.core.designsystem.SecondaryButton
import app.lectrice.core.designsystem.SegmentedControl
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.SheetHeader
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.Text
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.Sizes
import kotlinx.coroutines.delay

private enum class RangeEnd { From, To }

/** E1–E7 (file 10, v2 brief §5.4): a header, a scrolling body and, on E1/E7, the pinned footer. Pushed inside the player sheet. */
@Composable
fun ExportPage(state: ExportUiState, actions: ExportActions, onBack: () -> Unit, onSaveAs: () -> Unit) {
    val c = LectriceTheme.colors
    var list by rememberSaveable { mutableStateOf<RangeEnd?>(null) }
    var wholeNeeded by remember { mutableStateOf<Long?>(null) }
    if (state.page is ExportPage.Choose) state.footer?.let { wholeNeeded = it.neededBytes }
    val page = state.page
    BackHandler {
        when {
            list != null -> list = null
            page is ExportPage.Part -> actions.backToChoose()
            else -> onBack()
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(SNACK_MS)
            actions.messageShown()
        }
    }
    val summary = remember(state.voices, state.voiceId, state.quality, state.modelId, state.speed) {
        val voice = state.voices.firstOrNull { it.id == state.voiceId }?.name ?: state.voiceId
        val model = state.quality.firstOrNull { it.modelId == state.modelId }?.name
        listOfNotNull(voice, model, state.speed.label).joinToString(SEPARATOR)
    }
    Box(Modifier.fillMaxSize().background(c.bg2)) {
        Column(Modifier.fillMaxSize()) {
            when (page) {
                ExportPage.Loading -> {
                    PageHeader(stringResource(R.string.export_title), onBack)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                }
                ExportPage.Choose -> {
                    PageHeader(stringResource(R.string.export_title), onBack)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { ChooseBody(state, actions) }
                    state.footer?.let { ChooseFooter(it, state, actions) }
                }
                ExportPage.Part -> {
                    PageHeader(stringResource(R.string.export_part), actions::backToChoose)
                    Text(summary, Modifier.padding(horizontal = 16.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
                    state.footer?.let { footer ->
                        Text(
                            stringResource(R.string.export_part_free_line, Sizes.format(footer.freeBytes), Sizes.format(wholeNeeded ?: footer.neededBytes)),
                            Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
                            style = LectriceTheme.type.bodyS,
                            color = c.fg2,
                        )
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(16.dp))
                        RangeRow(stringResource(R.string.export_from), state.rangeChoices.getOrNull(state.rangeFrom)?.title.orEmpty()) { list = RangeEnd.From }
                        Spacer(Modifier.height(8.dp))
                        RangeRow(stringResource(R.string.export_to), state.rangeChoices.getOrNull(state.rangeTo)?.title.orEmpty()) { list = RangeEnd.To }
                    }
                    state.footer?.let { PartFooter(it, actions) }
                }
                is ExportPage.Progress -> {
                    PageHeader(stringResource(R.string.export_title), onBack)
                    Text(page.summary, Modifier.padding(horizontal = 16.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, end = 16.dp)) { ProgressBody(page, actions) }
                }
                is ExportPage.Done -> {
                    PageHeader(stringResource(R.string.export_title), onBack)
                    Text(summary, Modifier.padding(horizontal = 16.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 8.dp, end = 16.dp)) { DoneBody(page, actions, onSaveAs) }
                }
            }
        }
        state.message?.let { LectriceSnackbar(it.text(), Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 40.dp)) }
        list?.let { end ->
            RangeListSheet(
                title = stringResource(if (end == RangeEnd.From) R.string.export_from else R.string.export_to),
                choices = state.rangeChoices,
                selected = if (end == RangeEnd.From) state.rangeFrom else state.rangeTo,
                onPick = { index ->
                    if (end == RangeEnd.From) actions.setRange(index, maxOf(index, state.rangeTo)) else actions.setRange(minOf(index, state.rangeFrom), index)
                    list = null
                },
                onDismiss = { list = null },
            )
        }
        if (state.confirmCancel) {
            LectriceDialog(stringResource(R.string.export_cancel_title), stringResource(R.string.export_cancel_body), onDismiss = actions::dismissCancel) {
                LectriceTextButton(stringResource(R.string.export_cancel_keep), actions::dismissCancel)
                LectriceTextButton(stringResource(R.string.export_cancel), actions::confirmCancel, color = c.errFg)
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LectriceIconButton(LectriceIcons.ArrowBack, stringResource(R.string.common_back), onBack)
        Text(title, Modifier.weight(1f).semantics { heading() }, style = LectriceTheme.type.titleM, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() }, style = LectriceTheme.type.sectionHeader, color = LectriceTheme.colors.fg)
}

@Composable
private fun ChooseBody(state: ExportUiState, actions: ExportActions) {
    val c = LectriceTheme.colors
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    val voice = state.voices.firstOrNull { it.id == state.voiceId }

    GroupHeader(stringResource(R.string.export_voice))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg)
            .clickable(role = Role.Button) { voiceOpen = !voiceOpen }
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LectriceIcon(LectriceIcons.RecordVoiceOver, null)
        Column(Modifier.weight(1f)) {
            Text(voice?.name ?: state.voiceId, style = LectriceTheme.type.body.copy(fontWeight = FontWeight.Medium))
            voice?.let { Text(it.longSummary, style = LectriceTheme.type.bodyS, color = c.fg2) }
        }
        ExpandIcon(voiceOpen)
    }
    if (voiceOpen && state.voices.isNotEmpty()) {
        VoiceRows(state.voices, state.voiceId, state.voiceSample, onPick = { actions.setVoice(it); voiceOpen = false }, onSample = actions::playSample)
    }

    GroupHeader(stringResource(R.string.export_quality))
    state.quality.forEachIndexed { index, row ->
        if (index > 0) Divider(inset = 0.dp)
        QualityRowView(row, selected = row.modelId == state.modelId, onSelect = { actions.setModel(row.modelId) }, onDownload = { actions.downloadModel(row.modelId) })
    }
    state.qualityNote?.let { note ->
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LectriceIcon(LectriceIcons.Info, null, Modifier.padding(top = 2.dp), tint = c.fg2, size = 20.dp)
            Text(note, style = LectriceTheme.type.bodyS, color = c.fg2)
        }
    }

    GroupHeader(stringResource(R.string.export_speed))
    SegmentedControl(
        options = PlaybackSpeed.entries.map { it.label },
        selectedIndex = state.speed.ordinal,
        onSelect = { actions.setSpeed(PlaybackSpeed.entries[it]) },
        contentDescription = stringResource(R.string.export_speed_a11y),
    )
    Text(
        if (state.speed == PlaybackSpeed.X1) stringResource(R.string.export_speed_note_1x) else stringResource(R.string.export_speed_note, state.speed.label),
        Modifier.padding(top = 8.dp),
        style = LectriceTheme.type.bodyS,
        color = c.fg2,
    )

    val readCode = stringResource(R.string.sheet_read_code)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LectriceIcon(LectriceIcons.MenuBook, null)
        Text(readCode, Modifier.weight(1f), style = LectriceTheme.type.rowTitle)
        LectriceSwitch(state.readSkipped, actions::setReadSkipped, contentDescription = readCode)
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.bg).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LectriceIcon(LectriceIcons.Info, null, Modifier.padding(top = 2.dp), size = 20.dp)
        Text(stringResource(R.string.export_reassure), style = LectriceTheme.type.body)
    }
}

@Composable
private fun QualityRowView(row: QualityRow, selected: Boolean, onSelect: () -> Unit, onDownload: () -> Unit) {
    val c = LectriceTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .selectable(selected = selected, enabled = row.installed, role = Role.RadioButton, onClick = onSelect),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioMark(selected, enabled = row.installed)
            Column(Modifier.weight(1f)) {
                Text(row.name, style = LectriceTheme.type.rowTitle)
                Text(row.subtitle, Modifier.padding(top = 2.dp), style = LectriceTheme.type.rowMeta, color = c.fg2)
                row.downloadPercent?.let { LinearProgress(it / PERCENT, Modifier.padding(top = 6.dp)) }
            }
        }
        row.downloadLabel?.let { LectriceTextButton(it, onDownload) }
    }
}

@Composable
private fun ChooseFooter(footer: ExportFooter, state: ExportUiState, actions: ExportActions) {
    val c = LectriceTheme.colors
    val installed = state.quality.firstOrNull { it.modelId == state.modelId }?.installed == true
    FooterFrame {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(footer.summary, style = LectriceTheme.type.labelL)
            if (footer.fits) {
                Text(footer.makeTime, style = LectriceTheme.type.bodyS, color = c.fg2)
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.warnBg).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LectriceIcon(LectriceIcons.SdCardAlert, null, Modifier.padding(top = 2.dp), tint = c.warnFg, size = 20.dp)
                    Column {
                        Text(stringResource(R.string.export_space_title), style = LectriceTheme.type.labelL, color = c.warnFg)
                        Text(stringResource(R.string.export_space_needs, Sizes.format(footer.neededBytes), Sizes.format(footer.freeBytes)), Modifier.padding(top = 2.dp), style = LectriceTheme.type.bodyS, color = c.warnFg)
                        if (!footer.canExportPart) {
                            Text(stringResource(R.string.export_space_none, Sizes.format((footer.neededBytes - footer.freeBytes).coerceAtLeast(0))), Modifier.padding(top = 2.dp), style = LectriceTheme.type.bodyS, color = c.warnFg)
                        }
                    }
                }
            }
        }
        when {
            footer.fits -> PrimaryButton(footer.startLabel, actions::start, Modifier.padding(top = 8.dp), enabled = installed)
            footer.canExportPart -> {
                PrimaryButton(stringResource(R.string.export_part), actions::openPart, Modifier.padding(top = 8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LectriceTextButton(stringResource(R.string.export_free_up), actions::freeUpSpace) }
            }
            else -> PrimaryButton(stringResource(R.string.export_free_up), actions::freeUpSpace, Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PartFooter(footer: ExportFooter, actions: ExportActions) {
    val c = LectriceTheme.colors
    FooterFrame {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(footer.summary, style = LectriceTheme.type.labelL)
            if (footer.fits) {
                Text(footer.makeTime, style = LectriceTheme.type.bodyS, color = c.fg2)
            } else {
                Text(stringResource(R.string.export_no_fit, Sizes.format(footer.neededBytes), Sizes.format(footer.freeBytes)), style = LectriceTheme.type.bodyS, color = c.warnFg)
            }
        }
        PrimaryButton(footer.startLabel, actions::start, Modifier.padding(top = 8.dp), enabled = footer.fits)
        if (!footer.fits) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LectriceTextButton(stringResource(R.string.export_pick_fits), actions::pickWhatFits) }
    }
}

/** The pinned footer: `bg2`, 1 dp `line` on top, padding 12 16 (+ the gesture inset). */
@Composable
private fun FooterFrame(content: @Composable ColumnScope.() -> Unit) {
    val c = LectriceTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg2)) {
        Divider(inset = 0.dp)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), content = content)
    }
}

@Composable
private fun RangeRow(caption: String, value: String, onClick: () -> Unit) {
    val c = LectriceTheme.colors
    val description = stringResource(R.string.export_range_a11y, caption, value)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(caption, style = LectriceTheme.type.caption, color = c.fg2)
            Text(value, style = LectriceTheme.type.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ExpandIcon(false)
    }
}

/** E7f / E7t: every chapter choice with its length and size; the picked one carries a check and is centred on open. */
@Composable
private fun RangeListSheet(title: String, choices: List<RangeChoice>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val viewport = listState.layoutInfo.viewportSize.height
        listState.scrollToItem(selected.coerceIn(0, choices.lastIndex.coerceAtLeast(0)), -(viewport / 2 - CENTRE_ROW_GUESS))
    }
    BottomSheet(onDismiss, contentDescription = title, fullHeight = true) {
        SheetHeader(title) { LectriceIconButton(LectriceIcons.Close, stringResource(R.string.common_close), onDismiss) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(choices, key = { _, choice -> choice.index }) { index, choice ->
                val on = index == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (on) Modifier.background(c.accentContainer) else Modifier)
                        .selectable(selected = on, role = Role.RadioButton) { onPick(index) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(choice.title, style = LectriceTheme.type.rowTitle, color = if (on) c.onAccentContainer else c.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(choice.meta, Modifier.padding(top = 2.dp), style = LectriceTheme.type.rowMeta, color = if (on) c.onAccentContainer else c.fg2)
                    }
                    if (on) LectriceIcon(LectriceIcons.Check, null, tint = c.onAccentContainer)
                }
            }
            item("bottom") { SheetBottomSpacer() }
        }
    }
}

@Composable
private fun ProgressBody(page: ExportPage.Progress, actions: ExportActions) {
    val c = LectriceTheme.colors
    val context = LocalContext.current
    val export = page.export
    val percent = export.percent
    val kind = page.kind
    when (kind) {
        ProgressKind.PausedTimeLimit -> StateBanner(BannerKind.Warn, LectriceIcons.Warning, stringResource(R.string.export_paused_android))
        ProgressKind.PausedHeat -> StateBanner(BannerKind.Warn, LectriceIcons.Thermostat, stringResource(R.string.export_paused_heat))
        ProgressKind.PausedBattery -> StateBanner(BannerKind.Warn, LectriceIcons.BatterySaver, stringResource(R.string.export_paused_battery))
        ProgressKind.OutOfSpaceParts -> StateBanner(BannerKind.Err, LectriceIcons.Error, stringResource(R.string.export_space_parts, export.partsDone, export.partsTotal))
        ProgressKind.OutOfSpaceAssemble -> StateBanner(BannerKind.Err, LectriceIcons.Error, stringResource(R.string.export_space_assemble, export.partsTotal, Sizes.format(((page.neededBytes ?: 0) - (page.freeBytes ?: 0)).coerceAtLeast(0))))
        ProgressKind.Failed -> StateBanner(BannerKind.Err, LectriceIcons.Error, stringResource(R.string.export_failed))
        else -> Unit
    }
    val outOfSpace = kind == ProgressKind.OutOfSpaceParts || kind == ProgressKind.OutOfSpaceAssemble
    if (outOfSpace && page.neededBytes != null && page.freeBytes != null) {
        Text(stringResource(R.string.export_space_line, Sizes.format(page.neededBytes), Sizes.format(page.freeBytes)), Modifier.padding(top = 8.dp), style = LectriceTheme.type.bodyS)
    }
    if (kind != ProgressKind.Failed) {
        LinearProgress(percent / PERCENT, Modifier.padding(top = 16.dp), big = true)
        val label = when (kind) {
            ProgressKind.PausedByUser -> stringResource(R.string.export_paused, percent)
            ProgressKind.WaitingOtherExport -> stringResource(R.string.export_waiting_other)
            ProgressKind.WaitingEngine -> stringResource(R.string.export_waiting_engine)
            else -> stringResource(R.string.export_exporting, percent)
        }
        Text(label, Modifier.padding(top = 12.dp), style = LectriceTheme.type.labelL)
        Text(page.detail, Modifier.padding(top = 4.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
        if (kind == ProgressKind.Running || kind == ProgressKind.PausedByUser || outOfSpace) {
            Text(stringResource(R.string.export_note), Modifier.padding(top = 16.dp), style = LectriceTheme.type.body)
        }
    }
    Spacer(Modifier.height(24.dp))
    when (kind) {
        ProgressKind.Running, ProgressKind.WaitingOtherExport, ProgressKind.WaitingEngine -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.export_pause), actions::pause, Modifier.weight(1f))
            LectriceTextButton(stringResource(R.string.export_cancel), actions::askCancel)
        }
        ProgressKind.PausedByUser -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(R.string.export_continue), actions::resume, Modifier.weight(1f))
            LectriceTextButton(stringResource(R.string.export_cancel), actions::askCancel)
        }
        ProgressKind.PausedTimeLimit -> {
            PrimaryButton(stringResource(R.string.export_continue_now), actions::resume)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                LectriceTextButton(stringResource(R.string.export_battery), { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } })
            }
        }
        ProgressKind.PausedHeat, ProgressKind.PausedBattery -> PrimaryButton(stringResource(R.string.export_continue_now), actions::resume)
        ProgressKind.OutOfSpaceParts, ProgressKind.OutOfSpaceAssemble -> {
            val fits = (page.neededBytes ?: 0) <= (page.freeBytes ?: 0)
            PrimaryButton(stringResource(R.string.export_continue), actions::resume, enabled = fits)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { LectriceTextButton(stringResource(R.string.export_free_up), actions::freeUpSpace) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LectriceTextButton(stringResource(R.string.export_cancel), actions::askCancel) }
        }
        ProgressKind.Failed -> PrimaryButton(stringResource(R.string.export_start_again), actions::makeAnother)
    }
    Spacer(Modifier.navigationBarsPadding().height(24.dp))
}

@Composable
private fun StateBanner(kind: BannerKind, icon: ImageVector, text: String) {
    Banner(kind, icon, text, Modifier.padding(top = 16.dp))
}

@Composable
private fun DoneBody(page: ExportPage.Done, actions: ExportActions, onSaveAs: () -> Unit) {
    val c = LectriceTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LectriceIcon(LectriceIcons.CheckCircle, null, Modifier.size(48.dp), tint = c.okFg, size = 48.dp)
        Text(stringResource(R.string.export_done_title), style = LectriceTheme.type.titleM, textAlign = TextAlign.Center)
        Text(page.summary, style = LectriceTheme.type.body, textAlign = TextAlign.Center)
    }
    ListenFromFileButton(actions::listenFromFile, Modifier.padding(top = 24.dp))
    Text(stringResource(R.string.export_listen_caption), Modifier.fillMaxWidth().padding(top = 4.dp), style = LectriceTheme.type.caption, color = c.fg2, textAlign = TextAlign.Center)
    if (page.saved) {
        PrimaryButton(stringResource(R.string.export_saved), {}, Modifier.padding(top = 12.dp), enabled = false, icon = LectriceIcons.Check)
    } else {
        PrimaryButton(stringResource(R.string.export_save), actions::saveToAudiobooks, Modifier.padding(top = 12.dp))
    }
    Text(stringResource(R.string.export_save_caption), Modifier.fillMaxWidth().padding(top = 4.dp), style = LectriceTheme.type.caption, color = c.fg2, textAlign = TextAlign.Center)
    SecondaryButton(stringResource(R.string.export_save_as), onSaveAs, Modifier.padding(top = 12.dp))
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        LectriceTextButton(stringResource(R.string.export_share), actions::share, leading = { LectriceIcon(LectriceIcons.IosShare, null, tint = c.accent) })
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LectriceTextButton(stringResource(R.string.export_another), actions::makeAnother) }
    Spacer(Modifier.navigationBarsPadding().height(24.dp))
}

/** S1 (file 23): the tonal button on E4, `accentContainer` with the headphones mark. */
@Composable
private fun ListenFromFileButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LectriceTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(c.accentContainer)
            .clickable(interactionSource = null, indication = ripple(color = c.onAccentContainer), role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LectriceIcon(LectriceIcons.Headphones, null, tint = c.onAccentContainer)
        Text(stringResource(R.string.export_listen_from_file), Modifier.weight(1f, fill = false), style = LectriceTheme.type.labelL, color = c.onAccentContainer)
    }
}

private const val SNACK_MS = 4_000L
private const val PERCENT = 100f
private const val CENTRE_ROW_GUESS = 28
private const val SEPARATOR = " · "
