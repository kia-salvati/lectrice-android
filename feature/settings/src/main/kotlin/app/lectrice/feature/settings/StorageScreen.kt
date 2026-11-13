package app.lectrice.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.Banner
import app.lectrice.core.designsystem.BannerAction
import app.lectrice.core.designsystem.BannerKind
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.ChevronRight
import app.lectrice.core.designsystem.CoverTile
import app.lectrice.core.designsystem.Divider
import app.lectrice.core.designsystem.LectriceCard
import app.lectrice.core.designsystem.LectriceDialog
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceSnackbar
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LinearProgress
import app.lectrice.core.designsystem.ListRow
import app.lectrice.core.designsystem.SectionHeader
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.StatusBarScrim
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.TopBar
import app.lectrice.core.model.Sizes
import kotlinx.coroutines.delay

/** T3 (file 20 §3.5). Open sheets are screen state; numbers come from [state]. */
@Composable
fun StorageScreen(state: StorageUiState, actions: StorageActions, onBack: () -> Unit, closeInsteadOfBack: Boolean) {
    val c = LectriceTheme.colors
    var bookSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeBookId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = removeBookId != null || state.confirmDeleteExport != null || state.confirmDeleteAll || exportSheetId != null || bookSheetId != null) {
        when {
            removeBookId != null -> removeBookId = null
            state.confirmDeleteExport != null || state.confirmDeleteAll -> actions.dismiss()
            exportSheetId != null -> exportSheetId = null
            else -> bookSheetId = null
        }
    }
    val loading = stringResource(R.string.common_loading)
    fun orLoading(text: String) = if (state.loading) loading else text

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(
                stringResource(R.string.storage_title),
                onBack,
                stringResource(if (closeInsteadOfBack) R.string.common_close else R.string.common_back),
                backIcon = if (closeInsteadOfBack) LectriceIcons.Close else LectriceIcons.ArrowBack,
            )
            state.requestedBytes?.let { bytes ->
                Banner(BannerKind.Warn, LectriceIcons.SdCardAlert, stringResource(R.string.storage_free_up, Sizes.format(bytes)), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    BannerAction(stringResource(R.string.storage_open_phone), BannerKind.Warn, actions::openPhoneStorage)
                }
            }
            SummaryCard(state, orLoading(state.totalLine), orLoading(state.freeLine))

            SectionHeader(stringResource(R.string.storage_models))
            if (state.models.isEmpty()) {
                ListRow(orLoading(stringResource(R.string.storage_no_voice)))
            } else {
                state.models.forEach { model -> ListRow(model.name, subtitle = orLoading(model.meta), onClick = { actions.openModel(model.id) }, trailing = { ChevronRight() }) }
            }

            SectionHeader(stringResource(R.string.storage_books))
            if (state.books.isEmpty() && !state.loading) {
                Text(stringResource(R.string.storage_no_books), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = LectriceTheme.type.body, color = c.fg2)
            } else {
                Text(orLoading(state.booksHeader), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
                state.books.forEachIndexed { index, book ->
                    ListRow(
                        book.title,
                        subtitle = orLoading(book.meta),
                        minHeight = 72.dp,
                        titleMaxLines = 1,
                        onClick = { bookSheetId = book.documentId },
                        leading = { CoverTile(book.initials, book.hue) },
                        trailing = { if (!state.loading) Text(Sizes.format(book.libraryBytes + book.audioBytes), style = LectriceTheme.type.label) },
                    )
                    if (index < state.books.lastIndex) Divider(inset = 72.dp)
                }
            }

            SectionHeader(stringResource(R.string.storage_cache))
            Column(Modifier.padding(horizontal = 16.dp)) {
                LinearProgress(if (state.loading) 0f else state.cacheFraction, Modifier.padding(top = 8.dp))
                Text(orLoading(state.cacheLine), Modifier.padding(top = 4.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
                LectriceTextButton(stringResource(R.string.storage_clear_cache), actions::clearAudioCache, Modifier.padding(top = 4.dp).offset(x = (-12).dp), enabled = state.cacheFraction > 0f)
                Text(stringResource(R.string.storage_cache_note), style = LectriceTheme.type.bodyS, color = c.fg2)
            }

            if (state.exports.isNotEmpty()) {
                SectionHeader(stringResource(R.string.storage_exports))
                state.exports.forEachIndexed { index, row ->
                    ListRow(
                        row.title,
                        subtitle = orLoading(row.meta),
                        leadingIcon = LectriceIcons.AudioFile,
                        titleMaxLines = 1,
                        onClick = { exportSheetId = row.id },
                        extra = { if (row.unfinished && !state.loading) LinearProgress(row.export.percent / PERCENT, Modifier.padding(top = 4.dp).width(EXPORT_BAR_WIDTH)) },
                    )
                    if (index < state.exports.lastIndex) Divider()
                }
            }

            SectionHeader(stringResource(R.string.storage_phone))
            ListRow(stringResource(R.string.storage_free_up_phone), onClick = actions::openPhoneStorage, trailing = { ChevronRight() })
            ListRow(stringResource(R.string.storage_delete_all), titleColor = c.errFg, onClick = actions::askDeleteAll)
            Spacer(Modifier.navigationBarsPadding().height(48.dp))
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))

        state.message?.let { message ->
            LaunchedEffect(message) {
                delay(SNACK_MS)
                actions.messageShown()
            }
            LectriceSnackbar(message, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
        state.books.firstOrNull { it.documentId == bookSheetId }?.let { book ->
            StorageBookSheet(book, onClear = { actions.clearBookAudio(book.documentId); bookSheetId = null }, onRemove = { removeBookId = book.documentId }, onDismiss = { bookSheetId = null })
        }
        state.exports.firstOrNull { it.id == exportSheetId }?.let { row ->
            ExportSheet(row, actions, onDismiss = { exportSheetId = null })
        }
        state.books.firstOrNull { it.documentId == removeBookId }?.let { book ->
            LectriceDialog(
                title = stringResource(R.string.storage_remove_title, book.title),
                body = stringResource(R.string.storage_remove_body, Sizes.format(book.libraryBytes + book.audioBytes)),
                onDismiss = { removeBookId = null },
            ) {
                LectriceTextButton(stringResource(R.string.common_cancel), { removeBookId = null })
                LectriceTextButton(stringResource(R.string.storage_remove_confirm), { removeBookId = null; bookSheetId = null; actions.removeBook(book.documentId) }, color = c.errFg)
            }
        }
        state.confirmDeleteExport?.let { row ->
            LectriceDialog(
                title = stringResource(R.string.storage_export_delete_title),
                body = stringResource(R.string.storage_export_delete_body, Sizes.format(row.export.fileBytes ?: 0)),
                onDismiss = actions::dismiss,
            ) {
                LectriceTextButton(stringResource(R.string.common_cancel), actions::dismiss)
                LectriceTextButton(stringResource(R.string.storage_export_delete), { exportSheetId = null; actions.confirmDeleteExport() }, color = c.errFg)
            }
        }
        if (state.confirmDeleteAll) {
            LectriceDialog(
                title = stringResource(R.string.storage_delete_all_title),
                body = stringResource(R.string.storage_delete_all_body),
                onDismiss = actions::dismiss,
            ) {
                LectriceTextButton(stringResource(R.string.common_cancel), actions::dismiss)
                LectriceTextButton(stringResource(R.string.storage_delete_all_confirm), actions::confirmDeleteAll, color = c.errFg)
            }
        }
    }
}

/** Total, free, the 8 dp stacked bar in the cover hues and its legend (decorative for TalkBack). */
@Composable
private fun SummaryCard(state: StorageUiState, totalLine: String, freeLine: String) {
    val c = LectriceTheme.colors
    val segments = state.segments.filter { it.bytes > 0 }
    val total = segments.sumOf { it.bytes }.toFloat().coerceAtLeast(1f)
    LectriceCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), radius = 12.dp) {
        Text(totalLine, style = LectriceTheme.type.titleM)
        Text(freeLine, Modifier.padding(top = 4.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
        Canvas(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clearAndSetSemantics { }) {
            val gap = 2.dp.toPx()
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(c.line, cornerRadius = radius)
            if (!state.loading) {
                var x = 0f
                segments.forEachIndexed { index, segment ->
                    val width = (size.width - gap * (segments.size - 1)) * segment.bytes / total
                    drawRoundRect(c.onTile(segment.hue), Offset(x, 0f), Size(width.coerceAtLeast(gap), size.height), if (index == 0 || index == segments.lastIndex) radius else CornerRadius.Zero)
                    x += width + gap
                }
            }
        }
        if (!state.loading) {
            FlowRow(Modifier.padding(top = 12.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                segments.forEach { segment ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.onTile(segment.hue)))
                        Text("${segment.label} ${Sizes.format(segment.bytes)}", style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** T3b: the Library's book sheet drawn here with the Storage actions. */
@Composable
private fun StorageBookSheet(book: StorageBookRow, onClear: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    val meta = if (book.audioBytes > 0) stringResource(R.string.storage_book_meta, Sizes.format(book.libraryBytes), Sizes.format(book.audioBytes)) else stringResource(R.string.storage_book_meta_no_audio, Sizes.format(book.libraryBytes))
    BottomSheet(onDismiss, contentDescription = stringResource(R.string.storage_book_sheet)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverTile(book.initials, book.hue)
            Column(Modifier.weight(1f)) {
                Text(book.title, style = LectriceTheme.type.titleM, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, Modifier.padding(top = 4.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
            }
        }
        Divider(inset = 0.dp)
        if (book.audioBytes > 0) ListRow(stringResource(R.string.storage_book_clear, Sizes.format(book.audioBytes)), leadingIcon = LectriceIcons.DeleteSweep, minHeight = 56.dp, onClick = onClear)
        ListRow(stringResource(R.string.storage_book_remove), leadingIcon = LectriceIcons.Delete, titleColor = c.errFg, minHeight = 56.dp, onClick = onRemove)
        SheetBottomSpacer()
    }
}

/** T3c: one exported audiobook (done in Lectrice, done in Audiobooks, or unfinished). */
@Composable
private fun ExportSheet(row: StorageExportRow, actions: StorageActions, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    BottomSheet(onDismiss, contentDescription = stringResource(R.string.storage_export_sheet)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LectriceIcon(LectriceIcons.AudioFile, null, Modifier.padding(top = 2.dp), tint = c.fg2)
            Column(Modifier.weight(1f)) {
                Text(row.title, style = LectriceTheme.type.titleM, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.meta, Modifier.padding(top = 4.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
                if (row.unfinished) {
                    LinearProgress(row.export.percent / PERCENT, Modifier.padding(top = 4.dp).width(EXPORT_BAR_WIDTH))
                } else {
                    Text(stringResource(if (row.inAudiobooks) R.string.storage_export_in_audiobooks else R.string.storage_export_in_lectrice), Modifier.padding(top = 4.dp), style = LectriceTheme.type.caption, color = c.fg2)
                }
            }
        }
        Divider(inset = 0.dp)
        if (row.unfinished) {
            Text(stringResource(R.string.storage_export_cancel_note), Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp), style = LectriceTheme.type.bodyS, color = c.fg2)
        } else {
            ListRow(stringResource(R.string.storage_export_open), leadingIcon = LectriceIcons.OpenInNew, minHeight = 56.dp, onClick = { actions.openExport(row) })
            ListRow(stringResource(R.string.storage_export_share), leadingIcon = LectriceIcons.IosShare, minHeight = 56.dp, onClick = { actions.shareExport(row) })
            if (!row.inAudiobooks) {
                ListRow(stringResource(R.string.storage_export_save), subtitle = stringResource(R.string.storage_export_save_sub), leadingIcon = LectriceIcons.LibraryMusic, minHeight = 56.dp, onClick = { onDismiss(); actions.saveExport(row) })
            }
            ListRow(stringResource(R.string.storage_export_delete), leadingIcon = LectriceIcons.Delete, titleColor = c.errFg, minHeight = 56.dp, onClick = { actions.askDeleteExport(row) })
        }
        SheetBottomSpacer()
    }
}

private const val PERCENT = 100f
private const val SNACK_MS = 4_000L
private val EXPORT_BAR_WIDTH = 120.dp
