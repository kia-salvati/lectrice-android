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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.lectrice.core.designsystem.Banner
import app.lectrice.core.designsystem.BannerAction
import app.lectrice.core.designsystem.BannerKind
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.ChevronRight
import app.lectrice.core.designsystem.LectriceCard
import app.lectrice.core.designsystem.LectriceDialog
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSwitch
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.ListRow
import app.lectrice.core.designsystem.PrimaryButton
import app.lectrice.core.designsystem.SectionHeader
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.Spinner
import app.lectrice.core.designsystem.StatusBarScrim
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.TopBar
import app.lectrice.core.model.FoundComputer
import app.lectrice.core.model.HelperAttention
import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.NeededModel
import app.lectrice.core.model.PairedComputer
import app.lectrice.core.model.Sizes

/** What waits behind the H6 rationale sheet. */
private enum class PermissionAsk { Enable, Pair }

/** File 24 §11, option A: the helper's own page (H1, H9) with one pushed page per pairing step (H2–H5). */
@Composable
fun ComputerHelperScreen(
    state: ComputerHelperUiState,
    actions: ComputerHelperActions,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val helper = state.helper
    var ask by rememberSaveable { mutableStateOf<PermissionAsk?>(null) }
    BackHandler(enabled = ask != null) { ask = null }
    val pairBack: () -> Unit = { actions.pairBack() }
    Box(Modifier.fillMaxSize().background(LectriceTheme.colors.bg)) {
        when (val step = state.pair) {
            null -> HelperPage(
                helper,
                actions,
                onBack,
                onOpenAppSettings,
                onEnable = { on -> if (on && helper.needsPermission) ask = PermissionAsk.Enable else actions.setEnabled(on) },
                onPair = { if (helper.needsPermission) ask = PermissionAsk.Pair else actions.startPairing() },
            )
            is PairStep.List -> PairListPage(step, helper, state.found, actions, pairBack, onOpenAppSettings)
            is PairStep.Address -> AddressPage(step, actions, pairBack)
            is PairStep.Code -> CodePage(step, actions, pairBack)
            is PairStep.Done -> DonePage(step, helper.enabled, actions)
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
        ask?.let { pending ->
            NearbySheet(
                onContinue = {
                    ask = null
                    onRequestPermission()
                    when (pending) {
                        PermissionAsk.Enable -> actions.setEnabled(true)
                        PermissionAsk.Pair -> actions.startPairing()
                    }
                },
                onDismiss = { ask = null },
            )
        }
    }
}

// ===== H1, H9: the helper page =====

@Composable
private fun HelperPage(
    helper: HelperState,
    actions: ComputerHelperActions,
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onPair: () -> Unit,
) {
    val c = LectriceTheme.colors
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renameFor by rememberSaveable { mutableStateOf<String?>(null) }
    var forgetFor by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = renameFor != null || forgetFor != null) {
        renameFor = null
        forgetFor = null
    }
    val paired = helper.computers.isNotEmpty()
    val attention = (helper.link as? HelperLink.NeedsAttention)?.reason
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(stringResource(R.string.helper_title), onBack, stringResource(R.string.common_back))
            val switchLabel = stringResource(R.string.helper_switch)
            ListRow(
                switchLabel,
                subtitle = if (paired) null else stringResource(R.string.helper_pair_first),
                titleColor = if (paired) c.fg else c.fg2,
                trailing = { LectriceSwitch(helper.enabled, onEnable, contentDescription = switchLabel, enabled = paired) },
            )
            Text(stringResource(R.string.helper_explain), Modifier.padding(start = LectriceTheme.space.l, top = LectriceTheme.space.xs, end = LectriceTheme.space.l, bottom = LectriceTheme.space.s), style = LectriceTheme.type.bodyS, color = c.fg2)
            if (attention != null) AttentionLine(attention, helper.active?.shownName, helper.neededModel, actions::retry, onPair, onOpenAppSettings)

            SectionHeader(stringResource(R.string.helper_computers))
            helper.computers.forEach { computer ->
                val active = computer.peerId == helper.active?.peerId
                ComputerRow(
                    computer,
                    connected = active && helper.link.makesAudio,
                    needsAttention = active && attention != null && attention != HelperAttention.PermissionDenied,
                    menuOpen = menuFor == computer.peerId,
                    onMenu = { open -> menuFor = if (open) computer.peerId else null },
                    onRename = { menuFor = null; renameFor = computer.peerId },
                    onForget = { menuFor = null; forgetFor = computer.peerId },
                )
            }
            LectriceTextButton(stringResource(R.string.helper_pair), onPair, Modifier.padding(horizontal = LectriceTheme.space.xs))
            Row(Modifier.padding(LectriceTheme.space.l), horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s)) {
                LectriceIcon(LectriceIcons.Info, null, tint = c.fg2, size = LectriceTheme.size.icon)
                Text(stringResource(R.string.helper_help), Modifier.weight(1f), style = LectriceTheme.type.bodyS, color = c.fg2)
            }
            Spacer(Modifier.navigationBarsPadding().height(48.dp))
        }

        helper.computers.firstOrNull { it.peerId == renameFor }?.let { computer ->
            RenameDialog(computer, onSave = { label -> renameFor = null; actions.rename(computer.peerId, label) }, onDismiss = { renameFor = null })
        }
        helper.computers.firstOrNull { it.peerId == forgetFor }?.let { computer ->
            LectriceDialog(
                title = stringResource(R.string.helper_forget_title, computer.shownName),
                body = stringResource(R.string.helper_forget_body),
                onDismiss = { forgetFor = null },
            ) {
                LectriceTextButton(stringResource(R.string.common_cancel), { forgetFor = null })
                LectriceTextButton(stringResource(R.string.helper_forget), { forgetFor = null; actions.forget(computer.peerId) }, color = c.errFg)
            }
        }
    }
}

/** H9: the warn line under the switch; the denied permission (H6) is a card with "Open settings". */
@Composable
private fun AttentionLine(
    reason: HelperAttention,
    computerName: String?,
    neededModel: NeededModel?,
    onRetry: () -> Unit,
    onPair: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    if (reason == HelperAttention.PermissionDenied) {
        DeniedCard(onOpenAppSettings)
        return
    }
    val text = when (reason) {
        HelperAttention.ModelMissing -> neededModel?.let { stringResource(R.string.helper_attention_model_named, it.name, Sizes.format(it.downloadBytes)) }
            ?: stringResource(R.string.helper_attention_model)
        HelperAttention.NotPaired -> stringResource(R.string.helper_attention_forgot, computerName ?: stringResource(R.string.helper_your_computer))
        else -> stringResource(R.string.helper_mismatch)
    }
    val modifier = Modifier.padding(start = LectriceTheme.space.l, top = LectriceTheme.space.s, end = LectriceTheme.space.l)
    when (reason) {
        HelperAttention.ModelMissing -> Banner(BannerKind.Warn, LectriceIcons.Warning, text, modifier) {
            BannerAction(stringResource(R.string.helper_retry), BannerKind.Warn, onRetry)
        }
        HelperAttention.NotPaired -> Banner(BannerKind.Warn, LectriceIcons.Warning, text, modifier) {
            BannerAction(stringResource(R.string.helper_pair_again), BannerKind.Warn, onPair)
        }
        else -> Banner(BannerKind.Warn, LectriceIcons.Warning, text, modifier)
    }
}

@Composable
private fun DeniedCard(onOpenAppSettings: () -> Unit) {
    LectriceCard(Modifier.padding(start = LectriceTheme.space.l, top = LectriceTheme.space.s, end = LectriceTheme.space.l).fillMaxWidth(), radius = 12.dp, padding = 0.dp) {
        Text(stringResource(R.string.helper_denied), Modifier.padding(start = LectriceTheme.space.l, top = LectriceTheme.space.m, end = LectriceTheme.space.l), style = LectriceTheme.type.body)
        Row(Modifier.fillMaxWidth().padding(end = LectriceTheme.space.xs), horizontalArrangement = Arrangement.End) {
            LectriceTextButton(stringResource(R.string.helper_open_settings), onOpenAppSettings)
        }
    }
}

@Composable
private fun ComputerRow(
    computer: PairedComputer,
    connected: Boolean,
    needsAttention: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    val c = LectriceTheme.colors
    val subtitle = when {
        connected -> stringResource(R.string.helper_connected)
        needsAttention -> stringResource(R.string.helper_needs_attention)
        else -> lastSeen(computer.lastSeenAtMs)
    }
    ListRow(
        computer.shownName,
        subtitle = subtitle,
        leadingIcon = LectriceIcons.Computer,
        subtitleColor = if (needsAttention && !connected) c.warnFg else c.fg2,
        trailing = {
            Box {
                LectriceIconButton(LectriceIcons.MoreVert, stringResource(R.string.helper_more_a11y, computer.shownName), { onMenu(true) })
                if (menuOpen) ComputerMenu(onRename, onForget, onDismiss = { onMenu(false) })
            }
        },
    )
}

/** H1m: Rename / Forget, dropped under the ⋯ button; an outside tap or Back closes it. */
@Composable
private fun ComputerMenu(onRename: () -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    val drop = with(LocalDensity.current) { MENU_DROP.roundToPx() }
    val shape = RoundedCornerShape(12.dp)
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, drop), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.width(MENU_WIDTH).clip(shape).background(c.bg2).border(1.dp, c.line, shape).padding(vertical = LectriceTheme.space.s)) {
            ListRow(stringResource(R.string.helper_rename), onClick = onRename)
            ListRow(stringResource(R.string.helper_forget), titleColor = c.errFg, onClick = onForget)
        }
    }
}

@Composable
private fun lastSeen(seenAtMs: Long?): String {
    if (seenAtMs == null) return stringResource(R.string.helper_seen_never)
    val minutes = ((System.currentTimeMillis() - seenAtMs) / MS_PER_MINUTE).coerceAtLeast(0).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.helper_seen_now)
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.helper_seen_minutes, minutes)
        minutes < MINUTES_PER_DAY -> stringResource(R.string.helper_seen_hours, minutes / MINUTES_PER_HOUR)
        else -> stringResource(R.string.helper_seen_days, minutes / MINUTES_PER_DAY)
    }
}

@Composable
private fun RenameDialog(computer: PairedComputer, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(computer.peerId) { mutableStateOf(computer.shownName.take(MAX_NAME_LENGTH)) }
    val save = { onSave(name.trim().ifEmpty { null }) }
    LectriceDialog(
        title = stringResource(R.string.helper_rename),
        body = stringResource(R.string.helper_rename_body),
        onDismiss = onDismiss,
        modifier = Modifier.imePadding(),
        extra = {
            LabeledField(
                label = stringResource(R.string.helper_rename_label),
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                modifier = Modifier.padding(top = LectriceTheme.space.l),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                onDone = save,
            )
        },
    ) {
        LectriceTextButton(stringResource(R.string.common_cancel), onDismiss)
        LectriceTextButton(stringResource(R.string.helper_rename_save), save)
    }
}

/** H6a: why Android will ask for "Nearby devices", shown before the system dialog. */
@Composable
private fun NearbySheet(onContinue: () -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss, contentDescription = stringResource(R.string.helper_nearby_a11y)) {
        Text(stringResource(R.string.helper_nearby), Modifier.padding(horizontal = LectriceTheme.space.l), style = LectriceTheme.type.body)
        PrimaryButton(stringResource(R.string.helper_continue), onContinue, Modifier.padding(start = LectriceTheme.space.l, top = LectriceTheme.space.xl, end = LectriceTheme.space.l))
        Box(Modifier.fillMaxWidth().padding(top = LectriceTheme.space.s), contentAlignment = Alignment.Center) {
            LectriceTextButton(stringResource(R.string.helper_not_now), onDismiss)
        }
        SheetBottomSpacer()
    }
}

// ===== H2–H5: the pairing pages =====

/** One pairing page: the bar, a scrolling body and an optional button pinned above the keyboard. */
@Composable
private fun PairPage(title: String, onBack: () -> Unit, foot: (@Composable () -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(title, onBack, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            body()
            Spacer(if (foot == null) Modifier.navigationBarsPadding().height(LectriceTheme.space.xl) else Modifier.height(LectriceTheme.space.l))
        }
        if (foot != null) Box(Modifier.navigationBarsPadding().padding(LectriceTheme.space.l)) { foot() }
    }
}

@Composable
private fun PairListPage(
    step: PairStep.List,
    helper: HelperState,
    found: List<FoundComputer>,
    actions: ComputerHelperActions,
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val c = LectriceTheme.colors
    val denied = (helper.link as? HelperLink.NeedsAttention)?.reason == HelperAttention.PermissionDenied
    PairPage(stringResource(R.string.helper_pair), onBack) {
        when {
            denied -> DeniedCard(onOpenAppSettings)
            !helper.onWifi -> Column(
                Modifier.fillMaxWidth().padding(start = 32.dp, top = 48.dp, end = 32.dp),
                verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LectriceIcon(LectriceIcons.Wifi, null, tint = c.fg2, size = 40.dp)
                Text(stringResource(R.string.pair_no_wifi), style = LectriceTheme.type.body, textAlign = TextAlign.Center)
            }
            else -> {
                val nothingFound = found.isEmpty()
                found.forEach { computer ->
                    ListRow(computer.name, leadingIcon = LectriceIcons.Computer, onClick = { actions.pickComputer(computer) }, trailing = { ChevronRight() })
                }
                if (nothingFound && step.searchedLong) NotFoundHelp() else LookingRow(dim = !nothingFound)
                if (!nothingFound || step.searchedLong) {
                    LectriceTextButton(
                        stringResource(R.string.pair_enter_address),
                        actions::enterAddress,
                        Modifier.padding(start = LectriceTheme.space.xs, top = if (nothingFound) 16.dp else 0.dp),
                        style = if (nothingFound) LectriceTheme.type.labelL else LectriceTheme.type.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun LookingRow(dim: Boolean) {
    val c = LectriceTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = LectriceTheme.size.touch).padding(horizontal = LectriceTheme.space.l, vertical = LectriceTheme.space.s).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner(Modifier.padding(horizontal = LectriceTheme.space.xs))
        Text(
            stringResource(R.string.pair_looking),
            Modifier.weight(1f),
            style = if (dim) LectriceTheme.type.rowMeta else LectriceTheme.type.rowTitle,
            color = if (dim) c.fg2 else c.fg,
        )
    }
}

/** H2c: what to check when no computer shows up. */
@Composable
private fun NotFoundHelp() {
    val pairAPhone = stringResource(R.string.pair_a_phone)
    val lines = listOf(
        AnnotatedString(stringResource(R.string.pair_help_wifi)),
        AnnotatedString(stringResource(R.string.pair_help_open)),
        boldPart(stringResource(R.string.pair_help_click, pairAPhone), pairAPhone),
    )
    val style = LectriceTheme.type.bodyS.copy(color = LectriceTheme.colors.fg2)
    Column(Modifier.padding(start = 20.dp, end = LectriceTheme.space.l)) {
        lines.forEach { line ->
            Row(Modifier.padding(top = LectriceTheme.space.s), horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s)) {
                BasicText(BULLET, Modifier.clearAndSetSemantics { }, style = style)
                BasicText(line, Modifier.weight(1f), style = style)
            }
        }
    }
}

@Composable
private fun AddressPage(step: PairStep.Address, actions: ComputerHelperActions, onBack: () -> Unit) {
    // Typed text lives here so the cursor never waits for the ViewModel's round trip.
    var text by rememberSaveable { mutableStateOf(step.text) }
    PairPage(
        stringResource(R.string.pair_address_title),
        onBack,
        foot = { PrimaryButton(stringResource(R.string.helper_continue), actions::submitAddress) },
    ) {
        Column(Modifier.padding(horizontal = LectriceTheme.space.l)) {
            LabeledField(
                label = stringResource(R.string.pair_address_label),
                value = text,
                onValueChange = { text = it; actions.addressChanged(it) },
                modifier = Modifier.padding(top = LectriceTheme.space.s),
                placeholder = stringResource(R.string.pair_address_placeholder),
                error = step.invalid,
                textStyle = LectriceTheme.type.code.copy(fontSize = ADDRESS_TEXT, lineHeight = ADDRESS_LINE),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                onDone = actions::submitAddress,
            )
            if (step.invalid) {
                Text(
                    stringResource(R.string.pair_address_invalid),
                    Modifier.padding(top = LectriceTheme.space.s).semantics { liveRegion = LiveRegionMode.Polite },
                    style = LectriceTheme.type.bodyS,
                    color = LectriceTheme.colors.errFg,
                )
            }
        }
    }
}

@Composable
private fun CodePage(step: PairStep.Code, actions: ComputerHelperActions, onBack: () -> Unit) {
    val phase = step.phase
    var code by rememberSaveable { mutableStateOf("") }
    val locked = phase != CodePhase.Typing && phase !is CodePhase.Wrong
    val focus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase != CodePhase.Typing && phase != CodePhase.Checking) code = "" }
    LaunchedEffect(locked) { if (!locked) runCatching { focus.requestFocus() } }
    val name = step.computerName.ifEmpty { stringResource(R.string.pair_code_your_computer) }
    val boxesLabel = stringResource(R.string.pair_code_a11y)
    PairPage(stringResource(R.string.helper_pair), onBack) {
        Column(Modifier.padding(horizontal = LectriceTheme.space.l)) {
            Text(stringResource(R.string.pair_code_type, name), style = LectriceTheme.type.body)
            BasicTextField(
                value = code,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(CODE_LENGTH)
                    val complete = digits.length == CODE_LENGTH && digits != code
                    code = digits
                    if (complete) actions.submitCode(digits)
                },
                modifier = Modifier.padding(top = LectriceTheme.space.xl).fillMaxWidth().focusRequester(focus).semantics { contentDescription = boxesLabel },
                enabled = !locked,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.None),
                decorationBox = { CodeBoxes(code, locked) },
            )
            CodeStatus(phase, name, actions::typeAgain)
        }
    }
}

/** The six boxes drawn in place of the text field's own text; the next empty box holds the caret. */
@Composable
private fun CodeBoxes(code: String, locked: Boolean) {
    val c = LectriceTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s)) {
        repeat(CODE_LENGTH) { index ->
            val next = !locked && index == code.length
            val border = when {
                locked -> c.line
                next -> c.accent
                else -> c.fg2
            }
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .width(CODE_BOX)
                    .heightIn(min = CODE_BOX)
                    .clip(shape)
                    .background(if (locked) c.bg2 else c.bg)
                    .border(if (next) 2.dp else 1.dp, border, shape),
                contentAlignment = Alignment.Center,
            ) {
                val digit = code.getOrNull(index)
                when {
                    digit != null -> Text(digit.toString(), style = LectriceTheme.type.titleL, color = if (locked) c.fg2 else c.fg, maxLines = 1)
                    next -> Box(Modifier.size(2.dp, 24.dp).background(c.accent))
                }
            }
        }
    }
}

@Composable
private fun CodeStatus(phase: CodePhase, computerName: String, onTypeAgain: () -> Unit) {
    val c = LectriceTheme.colors
    when (phase) {
        CodePhase.Typing -> Unit
        CodePhase.Checking -> PhaseLine(null, AnnotatedString(stringResource(R.string.pair_code_checking)), c.fg)
        is CodePhase.Wrong -> {
            PhaseLine(LectriceIcons.Error, AnnotatedString(stringResource(R.string.pair_code_wrong)), c.errFg)
            Text(
                stringResource(R.string.pair_code_try, phase.attempt, ComputerHelperViewModel.MAX_TRIES),
                Modifier.padding(start = PHASE_TEXT_INSET, top = LectriceTheme.space.xs),
                style = LectriceTheme.type.bodyS,
                color = c.fg2,
            )
        }
        CodePhase.NoCode -> {
            val pairAPhone = stringResource(R.string.pair_a_phone)
            PhaseLine(LectriceIcons.Info, boldPart(stringResource(R.string.pair_code_none, computerName, pairAPhone), pairAPhone), c.fg)
            LectriceTextButton(stringResource(R.string.pair_code_new), onTypeAgain, Modifier.padding(top = LectriceTheme.space.xs).offset(x = TEXT_BUTTON_OUTDENT))
        }
        CodePhase.Unreachable -> {
            PhaseLine(LectriceIcons.Error, AnnotatedString(stringResource(R.string.pair_code_unreachable)), c.errFg)
            LectriceTextButton(stringResource(R.string.common_try_again), onTypeAgain, Modifier.padding(top = LectriceTheme.space.xs).offset(x = TEXT_BUTTON_OUTDENT))
        }
        CodePhase.NotLocal -> PhaseLine(LectriceIcons.Error, AnnotatedString(stringResource(R.string.pair_code_not_local)), c.errFg)
        CodePhase.Incompatible -> PhaseLine(LectriceIcons.Error, AnnotatedString(stringResource(R.string.helper_mismatch)), c.errFg)
    }
}

/** The line under the code boxes; a null [icon] draws the spinner. */
@Composable
private fun PhaseLine(icon: ImageVector?, text: AnnotatedString, color: Color) {
    Row(Modifier.padding(top = LectriceTheme.space.l).semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(LectriceTheme.space.s)) {
        if (icon == null) Spinner(Modifier.padding(2.dp)) else LectriceIcon(icon, null, tint = color, size = LectriceTheme.size.icon)
        BasicText(text, Modifier.weight(1f), style = LectriceTheme.type.bodyS.copy(color = color))
    }
}

@Composable
private fun DonePage(step: PairStep.Done, enabled: Boolean, actions: ComputerHelperActions) {
    val c = LectriceTheme.colors
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = DONE_TOP_GAP)) {
            Column(
                Modifier.fillMaxWidth().padding(start = LectriceTheme.space.xl, top = 32.dp, end = LectriceTheme.space.xl, bottom = LectriceTheme.space.xl),
                verticalArrangement = Arrangement.spacedBy(LectriceTheme.space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LectriceIcon(LectriceIcons.CheckCircle, null, tint = c.okFg, size = 56.dp)
                Text(
                    stringResource(R.string.pair_done_title, step.computer.shownName),
                    Modifier.semantics { heading() },
                    style = LectriceTheme.type.titleL,
                    textAlign = TextAlign.Center,
                )
                Text(stringResource(R.string.pair_done_line), style = LectriceTheme.type.body, color = c.fg2, textAlign = TextAlign.Center)
            }
            val switchLabel = stringResource(R.string.helper_switch)
            ListRow(switchLabel, trailing = { LectriceSwitch(enabled, actions::setEnabled, contentDescription = switchLabel) })
        }
        Box(Modifier.navigationBarsPadding().padding(LectriceTheme.space.l)) { PrimaryButton(stringResource(R.string.pair_done), actions::finishPairing) }
    }
}

// ===== shared pieces =====

/** An outlined field with its label inside the border; takes the focus when it appears. */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: Boolean = false,
    textStyle: TextStyle = LectriceTheme.type.body,
) {
    val c = LectriceTheme.colors
    val tone = if (error) c.errFg else c.accent
    val shape = RoundedCornerShape(12.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxWidth().clip(shape).background(c.bg).border(2.dp, tone, shape).padding(start = LectriceTheme.space.l, top = 6.dp, end = LectriceTheme.space.l, bottom = 10.dp)) {
        Text(label, Modifier.clearAndSetSemantics { }, style = LectriceTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = tone)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label },
            textStyle = textStyle.copy(color = c.fg),
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = c.fg2, maxLines = 1)
                    field()
                }
            },
        )
    }
}

private fun boldPart(text: String, part: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val start = text.indexOf(part)
    if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, start + part.length)
}

private const val CODE_LENGTH = 6
private const val MAX_NAME_LENGTH = 40
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val BULLET = "•"
private val CODE_BOX = 56.dp
private val MENU_WIDTH = 200.dp
private val MENU_DROP = 48.dp
private val DONE_TOP_GAP = 64.dp
private val PHASE_TEXT_INSET = 28.dp
private val TEXT_BUTTON_OUTDENT = (-12).dp
private val ADDRESS_TEXT = 18.sp
private val ADDRESS_LINE = 28.sp
