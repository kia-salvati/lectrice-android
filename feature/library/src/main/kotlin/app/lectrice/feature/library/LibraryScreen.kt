package app.lectrice.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.Banner
import app.lectrice.core.designsystem.BannerAction
import app.lectrice.core.designsystem.BannerKind
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.CheckboxRow
import app.lectrice.core.designsystem.CoverTile
import app.lectrice.core.designsystem.Divider
import app.lectrice.core.designsystem.LectriceCard
import app.lectrice.core.designsystem.LectriceDialog
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSnackbar
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LinearProgress
import app.lectrice.core.designsystem.ListRow
import app.lectrice.core.designsystem.PlaceholderBlock
import app.lectrice.core.designsystem.PlayButton
import app.lectrice.core.designsystem.PrimaryButton
import app.lectrice.core.designsystem.ProgressBarSmall
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.StatusLine
import app.lectrice.core.designsystem.Text
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.Sizes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** L1–L7 (files 02, 03 §3.6, 21). Business state comes from [state]; only the remove checkbox is screen-local. */
@Composable
fun LibraryScreen(state: LibraryUiState, actions: LibraryActions, onPickPdf: () -> Unit, onPickAudiobook: () -> Unit = {}) {
    val c = LectriceTheme.colors
    var importMenuOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = importMenuOpen || state.removeConfirm != null || state.bookSheet != null || state.importError != null || state.audiobookBookMissing != null) {
        when {
            importMenuOpen -> importMenuOpen = false
            state.removeConfirm != null -> actions.cancelRemove()
            state.bookSheet != null -> actions.closeBookSheet()
            state.importError != null -> actions.dismissImportError()
            else -> actions.dismissBookMissing()
        }
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (state.isEmpty) EmptyLibrary() else LibraryList(state, actions)
        if (importMenuOpen) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { importMenuOpen = false })
            ImportMenu(
                onPdf = { importMenuOpen = false; onPickPdf() },
                onAudiobook = { importMenuOpen = false; onPickAudiobook() },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        OpenPdfButton({ importMenuOpen = !importMenuOpen }, Modifier.align(Alignment.BottomEnd))
        state.snackbar?.let { LibrarySnackbarHost(it, actions, Modifier.align(Alignment.BottomCenter)) }
        state.importError?.let { ImportErrorSheet(it, actions::dismissImportError) }
        state.bookSheet?.let { BookSheet(it, actions) }
        state.audiobookBookMissing?.let { AudiobookBookMissingSheet(onOpenPdf = { actions.dismissBookMissing(); onPickPdf() }, onNotNow = actions::dismissBookMissing) }
        state.removeConfirm?.let { RemoveDialog(it, actions) }
        state.incomingConfirm?.let { IncomingDialog(it, actions) }
    }
}

/** A file from another app: asked before anything is copied or read. Tapping outside is "Not now". */
@Composable
private fun IncomingDialog(confirm: IncomingConfirm, actions: LibraryActions) {
    BackHandler { actions.declineIncoming(confirm.key) }
    LectriceDialog(
        title = stringResource(if (confirm.isAudiobook) R.string.incoming_title_audiobook else R.string.incoming_title_pdf, confirm.displayName),
        body = stringResource(if (confirm.isAudiobook) R.string.incoming_body_audiobook else R.string.incoming_body_pdf),
        onDismiss = { actions.declineIncoming(confirm.key) },
    ) {
        LectriceTextButton(stringResource(R.string.incoming_not_now), { actions.declineIncoming(confirm.key) })
        LectriceTextButton(stringResource(if (confirm.isAudiobook) R.string.incoming_link else R.string.incoming_import), { actions.acceptIncoming(confirm.key) })
    }
}

@Composable
private fun LibraryHeader(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.library_title), Modifier.weight(1f).semantics { heading() }, style = LectriceTheme.type.libraryTitle)
        LectriceIconButton(LectriceIcons.Settings, stringResource(R.string.library_settings), onSettings)
    }
}

@Composable
private fun LibraryList(state: LibraryUiState, actions: LibraryActions) {
    val listState = rememberLazyListState()
    var showPlaceholders by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        showPlaceholders = false
        if (state.loading) {
            delay(PLACEHOLDER_DELAY_MS)
            showPlaceholders = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .collect { (last, total) -> if (last != null && last >= total - 1 - LOAD_MORE_AHEAD) actions.loadMore() }
    }
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = LIST_BOTTOM_SPACE), modifier = Modifier.fillMaxSize()) {
        item("header") { LibraryHeader(actions::openSettings) }
        if (state.listFailed) {
            item("listFailed") {
                Banner(BannerKind.Err, LectriceIcons.Error, stringResource(R.string.library_list_failed), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    BannerAction(stringResource(R.string.library_banner_try_again), BannerKind.Err, actions::retryList)
                }
            }
        }
        state.banner?.let { banner -> item("banner") { LibraryBannerView(banner, actions::bannerAction, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) } }
        state.continueCard?.let { card -> item("card") { ContinueCardView(card, actions, stacked) } }
        item("recent") {
            Text(stringResource(R.string.library_recent), Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp).semantics { heading() }, style = LectriceTheme.type.sectionHeader)
        }
        itemsIndexed(state.imports, key = { _, row -> "import-${row.key}" }) { _, row ->
            ImportRowView(row)
            Divider(inset = 72.dp)
        }
        itemsIndexed(state.recent, key = { _, row -> row.documentId }) { index, row ->
            RecentRowView(row, actions, stacked)
            if (index < state.recent.lastIndex) Divider(inset = 72.dp)
        }
        if (showPlaceholders && state.loading) {
            items(PLACEHOLDER_ROWS) { PlaceholderRow() }
        }
    }
}

@Composable
private fun LibraryBannerView(banner: LibraryBanner, onAction: () -> Unit, modifier: Modifier) {
    when (banner) {
        LibraryBanner.VoiceNotInstalled -> Banner(BannerKind.Warn, LectriceIcons.Warning, stringResource(R.string.library_banner_voice_missing), modifier) {
            BannerAction(stringResource(R.string.library_banner_download), BannerKind.Warn, onAction)
        }
        is LibraryBanner.Downloading -> Banner(
            BannerKind.Warn,
            LectriceIcons.Download,
            stringResource(R.string.library_banner_downloading, banner.percent),
            modifier,
            extra = { LinearProgress(banner.percent / PERCENT, Modifier.padding(top = 8.dp)) },
        ) { BannerAction(stringResource(R.string.library_banner_details), BannerKind.Warn, onAction) }
        LibraryBanner.EngineFailed -> Banner(BannerKind.Err, LectriceIcons.Error, stringResource(R.string.library_banner_engine_failed), modifier) {
            BannerAction(stringResource(R.string.library_banner_try_again), BannerKind.Err, onAction)
        }
    }
}

@Composable
private fun ContinueCardView(card: ContinueCard, actions: LibraryActions, stacked: Boolean) {
    val c = LectriceTheme.colors
    val removeLabel = stringResource(R.string.book_sheet_remove)
    LectriceCard(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .combinedClickable(interactionSource = null, indication = ripple(), onClick = { actions.openBook(card.documentId, false) }, onLongClick = { actions.openBookSheet(card.documentId) })
            .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { actions.askRemove(card.documentId); true }) },
        padding = 0.dp,
    ) {
        Box {
            Box(Modifier.matchParentSize().padding(vertical = 16.dp)) {
                Box(Modifier.width(ACCENT_BAR).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent))
            }
            val text: @Composable () -> Unit = {
                Column {
                    Text(stringResource(if (card.finished) R.string.library_finished else R.string.library_continue), style = LectriceTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = c.fg2)
                    Text(card.title, Modifier.padding(top = 4.dp), style = LectriceTheme.type.titleM, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    card.location?.let { StatusLine(it) }
                    LinearProgress(card.percent / PERCENT, Modifier.padding(top = 12.dp))
                    Text(if (card.timeLeft != null) "${card.percent} % · ${card.timeLeft}" else "${card.percent} %", Modifier.padding(top = 4.dp), style = LectriceTheme.type.caption, color = c.fg2)
                }
            }
            val playLabel = when (val label = card.playLabel) {
                is CardPlayLabel.Book -> stringResource(R.string.library_card_play, label.title)
                is CardPlayLabel.FromPage -> stringResource(R.string.library_card_play_from_page, label.title, label.page)
                is CardPlayLabel.Again -> stringResource(R.string.library_card_play_again, label.title)
            }
            val play: @Composable () -> Unit = { PlayButton(card.playing, actions::togglePlayOnCard, playLabel) }
            if (stacked) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.End) {
                    text()
                    Spacer(Modifier.height(8.dp))
                    play()
                }
            } else {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { text() }
                    play()
                }
            }
        }
    }
}

/** At [stacked] font sizes the "Continue · 34 %" action moves under the title, start-aligned, so the text keeps the full width. */
@Composable
private fun RecentRowView(row: RecentRow, actions: LibraryActions, stacked: Boolean) {
    val c = LectriceTheme.colors
    val removeLabel = stringResource(R.string.book_sheet_remove)
    val progressText = when {
        row.finished -> stringResource(R.string.library_finished)
        row.percent != null -> stringResource(R.string.library_row_continue, row.percent)
        else -> stringResource(R.string.library_row_start)
    }
    val description = if (row.percent != null) {
        stringResource(R.string.library_row_a11y_started, row.title, row.percent, row.meta)
    } else {
        stringResource(R.string.library_row_a11y_not_started, row.title, row.meta)
    }
    val action: @Composable (Modifier, Alignment.Horizontal) -> Unit = { modifier, align ->
        Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(progressText, style = LectriceTheme.type.label, color = c.accent)
            ProgressBarSmall((row.percent ?: 0) / PERCENT)
        }
    }
    BoxWithConstraints {
        val trailingMax = maxWidth * TRAILING_MAX_SHARE
        ListRow(
            title = row.title,
            subtitle = row.meta,
            minHeight = 72.dp,
            titleMaxLines = 1,
            onClick = { actions.openBook(row.documentId, false) },
            onLongClick = { actions.openBookSheet(row.documentId) },
            contentDescription = description,
            customActions = listOf(CustomAccessibilityAction(removeLabel) { actions.askRemove(row.documentId); true }),
            leading = { CoverTile(row.initials, row.hue) },
            extra = if (stacked) ({ action(Modifier.padding(top = 8.dp), Alignment.Start) }) else null,
            trailing = if (stacked) null else ({ action(Modifier.widthIn(max = trailingMax), Alignment.End) }),
        )
    }
}

@Composable
private fun ImportRowView(row: ImportRow) {
    val c = LectriceTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(c.line))
        Column(Modifier.weight(1f)) {
            Text(row.displayName, style = LectriceTheme.type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.running) LinearProgress(null, Modifier.width(IMPORT_BAR_WIDTH))
                Text(stringResource(if (row.running) R.string.library_import_reading else R.string.library_import_waiting), style = LectriceTheme.type.rowMeta, color = c.fg2)
            }
        }
    }
}

@Composable
private fun PlaceholderRow() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaceholderBlock(Modifier.size(40.dp, 56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaceholderBlock(Modifier.fillMaxWidth(PLACEHOLDER_TITLE_SHARE).height(16.dp))
            PlaceholderBlock(Modifier.fillMaxWidth(PLACEHOLDER_META_SHARE).height(12.dp))
        }
    }
}

@Composable
private fun EmptyLibrary() {
    val c = LectriceTheme.colors
    Column(Modifier.fillMaxSize()) {
        LibraryHeader(onSettings = {})
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.library_empty_title), style = LectriceTheme.type.titleL, textAlign = TextAlign.Center)
            Text(stringResource(R.string.library_empty_body), Modifier.padding(top = 8.dp).width(EMPTY_TEXT_WIDTH), style = LectriceTheme.type.body, color = c.fg2, textAlign = TextAlign.Center)
            Text(stringResource(R.string.library_empty_limits), Modifier.padding(top = 12.dp), style = LectriceTheme.type.caption, color = c.fg2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun OpenPdfButton(onClick: () -> Unit, modifier: Modifier) {
    val c = LectriceTheme.colors
    val label = stringResource(R.string.library_open_pdf)
    Box(
        modifier
            .navigationBarsPadding()
            .padding(end = 16.dp, bottom = 16.dp)
            .size(56.dp)
            .border(1.dp, c.bg, CircleShape)
            .clip(CircleShape)
            .background(c.accent)
            .clickable(interactionSource = null, indication = ripple(color = c.onAccent), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LectriceIcon(LectriceIcons.NoteAdd, null, tint = c.onAccent)
    }
}

/** S2 (file 23): the two import choices, right-aligned just above the round button. */
@Composable
private fun ImportMenu(onPdf: () -> Unit, onAudiobook: () -> Unit, modifier: Modifier) {
    LectriceCard(
        modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = MENU_ABOVE_FAB)
            .widthIn(max = MENU_MAX_WIDTH),
        radius = 12.dp,
        padding = 0.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ListRow(stringResource(R.string.library_import_pdf), leadingIcon = LectriceIcons.PictureAsPdf, onClick = onPdf)
            ListRow(stringResource(R.string.library_import_audiobook), leadingIcon = LectriceIcons.Headphones, onClick = onAudiobook)
        }
    }
}

@Composable
private fun LibrarySnackbarHost(snackbar: LibrarySnackbar, actions: LibraryActions, modifier: Modifier) {
    LaunchedEffect(snackbar) {
        delay(if (snackbar is LibrarySnackbar.Removed) UNDO_MS else SNACK_MS)
        actions.snackbarDismissed()
    }
    val text = when (snackbar) {
        LibrarySnackbar.AlreadyInLibrary -> stringResource(R.string.library_snack_already)
        is LibrarySnackbar.Removed -> stringResource(R.string.library_snack_removed, snackbar.title)
        is LibrarySnackbar.Error -> stringResource(if (snackbar.name == ErrorName.StorageFull) R.string.library_snack_storage_full else R.string.library_error_other)
        is LibrarySnackbar.AudiobookLinked -> stringResource(R.string.library_snack_audiobook_linked, snackbar.title)
        LibrarySnackbar.AudiobookForeign -> stringResource(R.string.library_snack_audiobook_foreign)
        LibrarySnackbar.ImportsFull -> stringResource(R.string.library_snack_imports_full)
    }
    val action: Pair<String, () -> Unit>? = when (snackbar) {
        is LibrarySnackbar.Removed -> stringResource(R.string.library_snack_undo) to actions::undoRemove
        is LibrarySnackbar.AudiobookLinked -> stringResource(R.string.library_snack_listen) to { actions.listenToLinked(snackbar.documentId) }
        else -> null
    }
    LectriceSnackbar(
        text,
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = SNACK_ABOVE_FAB),
        actionLabel = action?.first,
        onAction = action?.second,
    )
}

/** S4 (file 23): the audiobook's map names a book that isn't in the library. */
@Composable
private fun AudiobookBookMissingSheet(onOpenPdf: () -> Unit, onNotNow: () -> Unit) {
    val title = stringResource(R.string.audiobook_missing_title)
    BottomSheet(onNotNow, contentDescription = title) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, Modifier.semantics { heading() }, style = LectriceTheme.type.titleM)
            Text(stringResource(R.string.audiobook_missing_body), Modifier.padding(top = 12.dp), style = LectriceTheme.type.body)
            PrimaryButton(stringResource(R.string.audiobook_missing_open), onOpenPdf, Modifier.padding(top = 24.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                LectriceTextButton(stringResource(R.string.audiobook_missing_not_now), onNotNow)
            }
            SheetBottomSpacer()
        }
    }
}

@Composable
private fun ImportErrorSheet(error: ImportError, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    BottomSheet(onDismiss, contentDescription = stringResource(R.string.library_error_title)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LectriceIcon(LectriceIcons.Error, null, tint = c.errFg)
                Text(stringResource(R.string.library_error_title), style = LectriceTheme.type.titleM)
            }
            Text(importErrorText(error), Modifier.padding(top = 12.dp), style = LectriceTheme.type.body)
            PrimaryButton(stringResource(R.string.library_error_ok), onDismiss, Modifier.padding(top = 24.dp))
            SheetBottomSpacer()
        }
    }
}

@Composable
private fun importErrorText(error: ImportError): String = when (error.name) {
    ErrorName.PdfNoText -> stringResource(R.string.library_error_no_text, error.displayName)
    ErrorName.PdfEncrypted -> stringResource(R.string.library_error_encrypted, error.displayName)
    ErrorName.UploadTooLarge -> error.bytes?.let { stringResource(R.string.library_error_too_large, error.displayName, Sizes.format(it)) }
        ?: stringResource(R.string.library_error_too_large_unknown, error.displayName)
    ErrorName.PdfInvalid -> stringResource(R.string.library_error_invalid, error.displayName)
    ErrorName.PdfTooComplex -> stringResource(R.string.library_error_too_complex, error.displayName)
    ErrorName.StorageFull -> error.bytes?.let { stringResource(R.string.library_error_storage_full, Sizes.format(it)) }
        ?: stringResource(R.string.library_error_storage_full_unknown)
    ErrorName.FileUnreadable -> stringResource(R.string.library_error_unreadable, error.displayName)
    else -> stringResource(R.string.library_error_other)
}

/** L7: the long-press sheet (file 21 §2). Also drawn by the Storage screen with its own actions. */
@Composable
private fun BookSheet(sheet: BookSheet, actions: LibraryActions) {
    val c = LectriceTheme.colors
    val meta = when {
        sheet.libraryBytes == null -> stringResource(R.string.book_sheet_meta_loading)
        sheet.audioBytes != null && sheet.audioBytes > 0 -> stringResource(R.string.book_sheet_meta, Sizes.format(sheet.libraryBytes), Sizes.format(sheet.audioBytes))
        else -> stringResource(R.string.book_sheet_meta_no_audio, Sizes.format(sheet.libraryBytes))
    }
    BottomSheet(actions::closeBookSheet, contentDescription = stringResource(R.string.book_sheet_a11y)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverTile(sheet.initials, sheet.hue)
            Column(Modifier.weight(1f)) {
                Text(sheet.title, style = LectriceTheme.type.titleM, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, Modifier.padding(top = 4.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
            }
        }
        Divider(inset = 0.dp)
        if (sheet.audioBytes == null || sheet.audioBytes > 0) {
            val clear = sheet.audioBytes?.let { stringResource(R.string.book_sheet_clear_size, Sizes.format(it)) } ?: stringResource(R.string.book_sheet_clear)
            ListRow(clear, leadingIcon = LectriceIcons.DeleteSweep, minHeight = 56.dp, onClick = { actions.clearBookAudio(sheet.documentId) })
        }
        ListRow(stringResource(R.string.book_sheet_remove), leadingIcon = LectriceIcons.Delete, titleColor = c.errFg, minHeight = 56.dp, onClick = { actions.askRemove(sheet.documentId) })
        SheetBottomSpacer()
    }
}

/** L7c (file 21 §3.1). */
@Composable
private fun RemoveDialog(confirm: RemoveConfirm, actions: LibraryActions) {
    val c = LectriceTheme.colors
    var deleteExports by rememberSaveable(confirm.documentId) { mutableStateOf(false) }
    val frees = confirm.freesBytes?.let(Sizes::format) ?: stringResource(R.string.book_sheet_meta_loading)
    LectriceDialog(
        title = stringResource(R.string.remove_title, confirm.title),
        body = stringResource(R.string.remove_body, frees),
        onDismiss = actions::cancelRemove,
        extra = {
            if (confirm.hasRunningExport) Text(stringResource(R.string.remove_export_cancelled), Modifier.padding(top = 8.dp), style = LectriceTheme.type.body)
            if (confirm.isPlaying) Text(stringResource(R.string.remove_playback_stops), Modifier.padding(top = 8.dp), style = LectriceTheme.type.body)
            if (confirm.exportCount > 0) {
                val size = Sizes.format(confirm.exportBytes)
                val label = if (confirm.exportCount == 1) stringResource(R.string.remove_also_one, size) else stringResource(R.string.remove_also_many, confirm.exportCount, size)
                CheckboxRow(deleteExports, label, { deleteExports = it }, Modifier.padding(top = 12.dp))
            }
        },
    ) {
        LectriceTextButton(stringResource(R.string.remove_cancel), actions::cancelRemove)
        LectriceTextButton(stringResource(R.string.remove_confirm), { actions.confirmRemove(deleteExports) }, color = c.errFg)
    }
}

private const val PLACEHOLDER_DELAY_MS = 150L
private const val PLACEHOLDER_ROWS = 3
private const val LOAD_MORE_AHEAD = 5
private const val PERCENT = 100f
private const val STACK_FONT_SCALE = 2f
private const val TRAILING_MAX_SHARE = 0.4f
private const val UNDO_MS = 8_000L
private const val SNACK_MS = 4_000L
private const val PLACEHOLDER_TITLE_SHARE = 0.6f
private const val PLACEHOLDER_META_SHARE = 0.4f
private val LIST_BOTTOM_SPACE = 120.dp
private val IMPORT_BAR_WIDTH = 160.dp
private val EMPTY_TEXT_WIDTH = 320.dp
private val ACCENT_BAR = 3.dp
private val SNACK_ABOVE_FAB = 80.dp
private val MENU_ABOVE_FAB = 80.dp
private val MENU_MAX_WIDTH = 280.dp
