package app.lectrice.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.Divider
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.SearchField
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.SheetHeader
import app.lectrice.core.designsystem.Text

/**
 * R8 (file 07 §3.4): the full-height searchable outline. Level-1 rows stick while their children scroll, the current
 * path gets the accent bar, the deepest path row is centred on open. [search] gives the rows for a query (matches bold).
 */
@Composable
internal fun ChaptersSheet(outline: List<OutlineRow>, search: (String) -> List<OutlineRow>, onPick: (OutlineRow) -> Unit, onDismiss: () -> Unit) {
    val c = LectriceTheme.colors
    val title = stringResource(R.string.chapters_title)
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(query, outline) { if (query.isBlank()) outline else search(query) }
    val sticky = remember(outline) { outline.any { it.level > 1 } }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) keyboard?.hide() }
    }
    val centreIndex = remember(outline) { outline.indexOfLast { it.onPath }.takeIf { it >= 0 } }
    LaunchedEffect(centreIndex) {
        if (centreIndex == null || query.isNotBlank()) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        listState.scrollToItem(centreIndex, -(viewport / 2 - CENTRE_ROW_GUESS))
    }
    BottomSheet(onDismiss, contentDescription = title, fullHeight = true) {
        SheetHeader(title) { LectriceIconButton(LectriceIcons.Close, stringResource(R.string.common_close), onDismiss) }
        if (outline.isEmpty()) {
            Text(stringResource(R.string.chapters_empty), Modifier.padding(horizontal = 16.dp, vertical = 24.dp), style = LectriceTheme.type.body, color = c.fg2)
            return@BottomSheet
        }
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.chapters_search),
            clearDescription = stringResource(R.string.chapters_clear),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            contentDescription = stringResource(R.string.chapters_search_a11y),
        )
        if (rows.isEmpty()) {
            Text(stringResource(R.string.chapters_no_match, query.trim()), Modifier.padding(horizontal = 16.dp, vertical = 24.dp), style = LectriceTheme.type.body, color = c.fg2)
            return@BottomSheet
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            outlineItems(rows, sticky, onPick)
            item("bottom") { SheetBottomSpacer() }
        }
    }
}

private fun LazyListScope.outlineItems(rows: List<OutlineRow>, sticky: Boolean, onPick: (OutlineRow) -> Unit) {
    rows.forEach { row ->
        if (sticky && row.level == 1) {
            stickyHeader(key = row.index) { OutlineRowView(row, header = true, onPick = onPick) }
        } else {
            item(key = row.index) { OutlineRowView(row, header = false, onPick = onPick) }
        }
    }
}

@Composable
private fun OutlineRowView(row: OutlineRow, header: Boolean, onPick: (OutlineRow) -> Unit) {
    val c = LectriceTheme.colors
    val type = LectriceTheme.type
    val style: TextStyle = when (row.level) {
        1 -> type.rowTitle
        2 -> type.body.copy(fontSize = 15.sp, lineHeight = 20.sp)
        else -> type.bodyS
    }
    val color = if (row.onPath) c.fg else if (row.level >= 3) c.fg2 else c.fg
    val indent = when (row.level) {
        1 -> 16.dp
        2 -> 32.dp
        else -> 48.dp
    }
    val text = remember(row) {
        val start = row.matchStart
        val end = row.matchEnd
        if (start == null || end == null) AnnotatedString(row.title) else AnnotatedString.Builder(row.title).apply { addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end) }.toAnnotatedString()
    }
    val description = stringResource(if (row.onPath) R.string.chapters_row_current_a11y else R.string.chapters_row_a11y, row.title, row.pageLabel)
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (header) Modifier.background(c.bg2) else Modifier)
            .heightIn(min = if (row.level >= 3) 40.dp else 48.dp)
            .clickable(role = Role.Button) { onPick(row) }
            .drawBehind { if (row.onPath) drawRect(c.accent, size = Size(PATH_BAR.toPx(), size.height)) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (row.level == 1) heading()
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = indent, end = 16.dp, top = if (row.level >= 3) 10.dp else 12.dp, bottom = if (row.level >= 3) 10.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = text,
                modifier = Modifier.weight(1f),
                style = style.copy(color = color, fontWeight = if (row.onPath) FontWeight.SemiBold else style.fontWeight),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(row.pageLabel, style = type.caption, color = c.fg2, maxLines = 1)
        }
        if (header) Divider(Modifier.align(Alignment.BottomCenter), inset = 0.dp)
    }
}

private const val CENTRE_ROW_GUESS = 24
private val PATH_BAR = 3.dp
