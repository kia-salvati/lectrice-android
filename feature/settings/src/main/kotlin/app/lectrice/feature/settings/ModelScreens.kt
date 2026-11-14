package app.lectrice.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lectrice.core.designsystem.Banner
import app.lectrice.core.designsystem.BannerAction
import app.lectrice.core.designsystem.BannerKind
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.InfoCard
import app.lectrice.core.designsystem.LectriceCard
import app.lectrice.core.designsystem.LectriceDialog
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LinearProgress
import app.lectrice.core.designsystem.PlayButton
import app.lectrice.core.designsystem.PrimaryButton
import app.lectrice.core.designsystem.RadioMark
import app.lectrice.core.designsystem.SecondaryButton
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.StatusBarScrim
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.TopBar
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.Sizes
import app.lectrice.core.model.VoiceSampleState
import app.lectrice.core.model.Voices

// ===== S1 =====

@Composable
fun WelcomeScreen(onGetVoice: () -> Unit, onNotNow: () -> Unit) {
    val c = LectriceTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = LectriceTheme.space.l, top = WELCOME_TOP, end = LectriceTheme.space.l, bottom = BOTTOM_BUTTONS_SPACE)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.welcome_mark), style = LectriceTheme.type.serif(MARK_SP, MARK_SP, FontWeight.SemiBold), color = c.onAccent)
            }
            Text(stringResource(R.string.welcome_title), Modifier.padding(top = LectriceTheme.space.xl).semantics { heading() }, style = LectriceTheme.type.welcomeTitle)
            Text(stringResource(R.string.welcome_tagline), Modifier.padding(top = LectriceTheme.space.s), style = LectriceTheme.type.titleM.copy(fontWeight = FontWeight.Normal))
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.l)) {
                WelcomePoint(LectriceIcons.MenuBook, stringResource(R.string.welcome_point_clean))
                WelcomePoint(LectriceIcons.Headphones, stringResource(R.string.welcome_point_voice))
                WelcomePoint(LectriceIcons.CheckCircle, stringResource(R.string.welcome_point_offline))
            }
        }
        BottomButtons(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton(stringResource(R.string.welcome_get_voice), onGetVoice)
            LectriceTextButton(stringResource(R.string.welcome_not_now), onNotNow, Modifier.align(Alignment.CenterHorizontally))
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun WelcomePoint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.l)) {
        LectriceIcon(icon, null, tint = LectriceTheme.colors.accent)
        Text(text, style = LectriceTheme.type.body)
    }
}

/** The `.bottom` stack: 16 dp gutters, 8 dp gaps, 16 dp above the gesture inset. */
@Composable
private fun BottomButtons(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(LectriceTheme.colors.bg).navigationBarsPadding().padding(start = LectriceTheme.space.l, end = LectriceTheme.space.l, bottom = LectriceTheme.space.l),
        verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.s),
        content = content,
    )
}

// ===== S2 =====

@Composable
fun ChooseModelScreen(state: ModelSetupUiState, actions: ModelSetupActions, onImportFile: () -> Unit, onBack: () -> Unit) {
    val c = LectriceTheme.colors
    val selected = state.cards.firstOrNull { it.id == state.selectedId }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = CHOOSE_BOTTOM_SPACE)) {
            Box(Modifier.padding(start = LectriceTheme.space.xs, top = LectriceTheme.space.xs)) { LectriceIconButton(LectriceIcons.ArrowBack, stringResource(R.string.common_back), onBack) }
            Column(Modifier.padding(horizontal = LectriceTheme.space.l)) {
                Text(stringResource(R.string.choose_title), Modifier.padding(top = LectriceTheme.space.l).semantics { heading() }, style = LectriceTheme.type.titleL)
                Text(stringResource(R.string.choose_body), Modifier.padding(top = LectriceTheme.space.s), style = LectriceTheme.type.body, color = c.fg2)
                Column(Modifier.padding(top = LectriceTheme.space.xl).selectableGroup(), verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.m)) {
                    state.cards.forEach { card -> ModelCardView(card, selected = card.id == state.selectedId, onSelect = { actions.select(card.id) }) }
                }
                Text(stringResource(R.string.choose_hint), Modifier.padding(top = LectriceTheme.space.l), style = LectriceTheme.type.bodyS, color = c.fg2)
                HintLine(LectriceIcons.SdStorage, state.freeLine, Modifier.padding(top = LectriceTheme.space.s))
                HintLine(LectriceIcons.Wifi, stringResource(R.string.choose_wifi), Modifier.padding(top = LectriceTheme.space.xs))
            }
        }
        BottomButtons(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton(
                text = selected?.let { stringResource(R.string.choose_download, Sizes.format(it.downloadBytes)) } ?: stringResource(R.string.choose_disabled),
                onClick = { selected?.let { actions.download(it.id) } },
                enabled = selected != null,
            )
            LectriceTextButton(
                stringResource(R.string.choose_import),
                onImportFile,
                Modifier.align(Alignment.CenterHorizontally),
                enabled = state.download == null && !state.importing,
                leading = if (state.importing) ({ Spinner() }) else null,
            )
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
        state.dialog?.let { SetupDialogView(it, actions) }
    }
}

@Composable
private fun HintLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    val c = LectriceTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s), verticalAlignment = Alignment.CenterVertically) {
        LectriceIcon(icon, null, tint = c.fg2, size = LectriceTheme.size.icon)
        Text(text, style = LectriceTheme.type.bodyS, color = c.fg2)
    }
}

/** S2's radio card ([selected] non-null) or T2's plain card ([selected] null) with [extra] under the notes. */
@Composable
private fun ModelCardView(card: ModelCard, selected: Boolean? = null, onSelect: (() -> Unit)? = null, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = LectriceTheme.colors
    val on = selected == true
    val shape = RoundedCornerShape(16.dp)
    val fg = if (on) c.onAccentContainer else c.fg
    val fg2 = if (on) c.onAccentContainer else c.fg2
    val selectModifier = if (selected != null && onSelect != null) Modifier.selectable(selected = on, role = Role.RadioButton, onClick = onSelect) else Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (on) c.accentContainer else c.bg2)
            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, shape)
            .then(selectModifier)
            .padding(LectriceTheme.space.l),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.m), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) RadioMark(on, selectedColor = if (on) c.onAccentContainer else c.accent)
            Text(card.name, Modifier.weight(1f), style = LectriceTheme.type.titleM, color = fg)
            if (card.recommended) Text(stringResource(R.string.choose_recommended), style = LectriceTheme.type.label, color = if (on) c.onAccentContainer else c.accent)
            Text(Sizes.format(card.downloadBytes), style = LectriceTheme.type.labelL.copy(fontWeight = FontWeight.SemiBold), color = fg)
        }
        val indent = if (selected != null) CARD_INDENT else 0.dp
        Text(card.description, Modifier.padding(start = indent, top = LectriceTheme.space.xs), style = LectriceTheme.type.body, color = fg)
        Text(card.sizeLine, Modifier.padding(start = indent, top = LectriceTheme.space.xs), style = LectriceTheme.type.bodyS, color = fg2)
        Text(card.speedNote, Modifier.padding(start = indent), style = LectriceTheme.type.bodyS, color = fg2)
        extra?.invoke(this)
    }
}

/** S2b, T2c and the import/space errors, shared by the choose screen and the model page. */
@Composable
private fun SetupDialogView(dialog: SetupDialog, actions: ModelSetupActions) {
    val c = LectriceTheme.colors
    BackHandler(onBack = actions::dismissDialog)
    when (dialog) {
        is SetupDialog.MobileData -> LectriceDialog(
            title = stringResource(R.string.metered_title),
            body = stringResource(R.string.metered_body, Sizes.format(dialog.downloadBytes)),
            onDismiss = actions::dismissDialog,
        ) {
            LectriceTextButton(stringResource(R.string.metered_wait), { actions.confirmMobileData(true) })
            LectriceTextButton(stringResource(R.string.metered_now), { actions.confirmMobileData(false) })
        }
        is SetupDialog.Delete -> LectriceDialog(
            title = stringResource(R.string.model_delete_title, dialog.name),
            body = stringResource(R.string.model_delete_body),
            onDismiss = actions::dismissDialog,
        ) {
            LectriceTextButton(stringResource(R.string.common_cancel), actions::dismissDialog)
            LectriceTextButton(stringResource(R.string.model_delete_confirm), actions::confirmDelete, color = c.errFg)
        }
        is SetupDialog.ImportFailed -> {
            val title = dialog.name.importFailedTitle()?.let { stringResource(it) }
            val body = if (dialog.name == ErrorName.StorageFull) spaceShortText(dialog.neededBytes) else stringResource(dialog.name.importFailedBody())
            BottomSheet(actions::dismissDialog, contentDescription = title ?: body) {
                Column(Modifier.padding(horizontal = LectriceTheme.space.l)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.m), verticalAlignment = Alignment.CenterVertically) {
                        LectriceIcon(LectriceIcons.Error, null, tint = c.errFg)
                        Text(title ?: body, Modifier.weight(1f), style = LectriceTheme.type.titleM)
                    }
                    if (title != null) Text(body, Modifier.padding(top = LectriceTheme.space.m), style = LectriceTheme.type.body)
                    PrimaryButton(stringResource(R.string.common_ok), actions::dismissDialog, Modifier.padding(top = LectriceTheme.space.xl))
                    SheetBottomSpacer()
                }
            }
        }
    }
}

@Composable
private fun spaceShortText(neededBytes: Long?): String =
    neededBytes?.let { stringResource(R.string.space_short, Sizes.format(it)) } ?: stringResource(R.string.space_short_unknown)

// ===== S3 / S4 =====

@Composable
fun DownloadScreen(state: ModelSetupUiState, actions: ModelSetupActions, onOpenPdf: () -> Unit, onLibrary: () -> Unit) {
    val c = LectriceTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (state.ready) ReadyView(state, actions, onOpenPdf, onLibrary) else DownloadingView(state.download, actions, onOpenPdf)
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
        state.dialog?.let { SetupDialogView(it, actions) }
    }
}

@Composable
private fun DownloadingView(download: DownloadUi?, actions: ModelSetupActions, onOpenPdf: () -> Unit) {
    val c = LectriceTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = LectriceTheme.space.l, top = DOWNLOAD_TOP, end = LectriceTheme.space.l, bottom = DOWNLOAD_BOTTOM_SPACE)) {
            Text(stringResource(R.string.download_title), Modifier.semantics { heading() }, style = LectriceTheme.type.titleL)
            val installing = download?.installing == true
            LinearProgress(if (installing) null else (download?.percent ?: 0) / PERCENT, Modifier.padding(top = 32.dp), big = true)
            val label = when {
                download == null -> stringResource(R.string.common_loading)
                installing -> stringResource(R.string.download_installing)
                download.paused -> stringResource(R.string.download_paused, download.progressLine)
                else -> download.progressLine
            }
            Text(label, Modifier.padding(top = LectriceTheme.space.m), style = LectriceTheme.type.labelL)
            download?.speedLine?.let { Text(it, Modifier.padding(top = LectriceTheme.space.xs), style = LectriceTheme.type.bodyS, color = c.fg2) }
            if (download != null) DownloadBanner(download, Modifier.padding(top = LectriceTheme.space.l))
            InfoCard(stringResource(R.string.download_reassure), Modifier.padding(top = LectriceTheme.space.xl))
        }
        BottomButtons(Modifier.align(Alignment.BottomCenter)) {
            LectriceTextButton(stringResource(R.string.download_meanwhile), onOpenPdf, Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s), verticalAlignment = Alignment.CenterVertically) {
                when {
                    download?.error != null -> PrimaryButton(stringResource(R.string.common_try_again), actions::retry, Modifier.weight(1f))
                    download?.paused == true -> PrimaryButton(stringResource(R.string.download_resume), actions::resume, Modifier.weight(1f))
                    else -> SecondaryButton(stringResource(R.string.download_pause), actions::pause, Modifier.weight(1f), enabled = download != null && !download.installing)
                }
                LectriceTextButton(stringResource(R.string.download_cancel), actions::cancel)
            }
        }
    }
}

/** S3b–S3d: the one banner between the speed line and the reassurance card. */
@Composable
private fun DownloadBanner(download: DownloadUi, modifier: Modifier) {
    when {
        download.error == ErrorName.StorageFull -> Banner(BannerKind.Err, LectriceIcons.Error, spaceShortText(download.neededBytes), modifier)
        download.error != null -> Banner(BannerKind.Err, LectriceIcons.Error, stringResource(download.error.downloadFailedMessage()), modifier)
        download.waitingForWifi -> Banner(BannerKind.Warn, LectriceIcons.Warning, stringResource(R.string.download_waiting_wifi), modifier)
        download.waitingForNetwork -> Banner(BannerKind.Warn, LectriceIcons.Warning, stringResource(R.string.download_waiting_network), modifier)
    }
}

@Composable
private fun ReadyView(state: ModelSetupUiState, actions: ModelSetupActions, onOpenPdf: () -> Unit, onLibrary: () -> Unit) {
    val c = LectriceTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = LectriceTheme.space.l, top = WELCOME_TOP, end = LectriceTheme.space.l, bottom = BOTTOM_BUTTONS_SPACE)) {
            LectriceIcon(LectriceIcons.CheckCircle, null, tint = c.okFg, size = 48.dp)
            Text(stringResource(R.string.ready_title), Modifier.padding(top = LectriceTheme.space.l).semantics { heading() }, style = LectriceTheme.type.titleL)
            LectriceCard(Modifier.padding(top = LectriceTheme.space.xl).fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.l), verticalAlignment = Alignment.CenterVertically) {
                    val samplePhase = state.voiceSample.phaseOf(Voices.DEFAULT_VOICE_ID)
                    PlayButton(
                        playing = samplePhase == VoiceSampleState.Phase.Playing,
                        onClick = actions::playSample,
                        contentDescription = stringResource(R.string.ready_hear),
                        preparing = samplePhase == VoiceSampleState.Phase.Loading,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ready_voice), style = LectriceTheme.type.label)
                        Text(stringResource(R.string.ready_sample), style = LectriceTheme.type.sample)
                    }
                }
            }
            if (state.slowPhone) Text(stringResource(R.string.ready_slow), Modifier.padding(top = LectriceTheme.space.l), style = LectriceTheme.type.bodyS, color = c.fg2)
        }
        BottomButtons(Modifier.align(Alignment.BottomCenter)) {
            PrimaryButton(stringResource(R.string.ready_open), onOpenPdf)
            LectriceTextButton(stringResource(R.string.ready_library), onLibrary, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

// ===== T2 =====

@Composable
fun ModelPageScreen(state: ModelSetupUiState, actions: ModelSetupActions, onBack: () -> Unit) {
    val c = LectriceTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(stringResource(R.string.model_title), onBack, stringResource(R.string.common_back))
            Column(Modifier.padding(horizontal = LectriceTheme.space.l), verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.m)) {
                state.cards.forEach { card ->
                    ModelCardView(card) { ModelCardState(card, state.download?.takeIf { it.modelId == card.id }, actions) }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(48.dp))
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
        state.dialog?.let { SetupDialogView(it, actions) }
    }
}

@Composable
private fun ColumnScope.ModelCardState(card: ModelCard, download: DownloadUi?, actions: ModelSetupActions) {
    val c = LectriceTheme.colors
    when (val install = card.state) {
        ModelInstallState.Installed -> {
            Text(stringResource(if (card.inUse) R.string.model_installed_in_use else R.string.model_installed), Modifier.padding(top = LectriceTheme.space.s), style = LectriceTheme.type.label, color = c.okFg)
            if (!card.inUse) {
                Row(Modifier.padding(top = LectriceTheme.space.s), horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(stringResource(R.string.model_use), { actions.use(card.id) }, Modifier.weight(1f))
                    LectriceTextButton(stringResource(R.string.model_delete, Sizes.format(card.installedBytes)), { actions.askDelete(card.id) }, color = c.errFg)
                }
            }
        }
        ModelInstallState.NotInstalled -> {
            Text(stringResource(R.string.model_not_installed), Modifier.padding(top = LectriceTheme.space.s), style = LectriceTheme.type.bodyS, color = c.fg2)
            SecondaryButton(stringResource(R.string.choose_download, Sizes.format(card.downloadBytes)), { actions.download(card.id) }, Modifier.padding(top = LectriceTheme.space.s))
        }
        is ModelInstallState.Downloading -> {
            LinearProgress(install.let { if (it.totalBytes > 0) it.doneBytes.toFloat() / it.totalBytes else 0f }, Modifier.padding(top = LectriceTheme.space.m))
            val line = download?.let { if (it.paused) stringResource(R.string.download_paused, it.progressLine) else it.progressLine }
            if (line != null) Text(line, Modifier.padding(top = LectriceTheme.space.xs), style = LectriceTheme.type.bodyS, color = c.fg2)
            if (download != null) DownloadBanner(download, Modifier.padding(top = LectriceTheme.space.s))
            Row(Modifier.padding(top = LectriceTheme.space.xs), horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.xs)) {
                if (install.paused) LectriceTextButton(stringResource(R.string.download_resume), actions::resume) else LectriceTextButton(stringResource(R.string.download_pause), actions::pause)
                LectriceTextButton(stringResource(R.string.download_cancel), actions::cancel)
            }
        }
        ModelInstallState.Installing -> {
            LinearProgress(null, Modifier.padding(top = LectriceTheme.space.m))
            Text(stringResource(R.string.download_installing), Modifier.padding(top = LectriceTheme.space.xs), style = LectriceTheme.type.bodyS, color = c.fg2)
        }
        is ModelInstallState.Failed -> {
            val text = if (install.name == ErrorName.StorageFull) spaceShortText(download?.neededBytes) else stringResource(install.name.downloadFailedMessage())
            Banner(BannerKind.Err, LectriceIcons.Error, text, Modifier.padding(top = LectriceTheme.space.m)) {
                BannerAction(stringResource(R.string.common_try_again), BannerKind.Err, actions::retry)
            }
        }
    }
}

private const val PERCENT = 100f
private val MARK_SP = 36.sp
private val CARD_INDENT = 36.dp
private val WELCOME_TOP = 96.dp
private val DOWNLOAD_TOP = 48.dp
private val BOTTOM_BUTTONS_SPACE = 160.dp
private val CHOOSE_BOTTOM_SPACE = 160.dp
private val DOWNLOAD_BOTTOM_SPACE = 176.dp
