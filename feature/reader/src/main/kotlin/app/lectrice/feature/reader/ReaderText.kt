package app.lectrice.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.LocalReducedMotion
import app.lectrice.core.designsystem.HIGHLIGHT_MS
import app.lectrice.core.designsystem.Text
import app.lectrice.core.designsystem.endFade
import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.TextSize

/** Where a sentence sits inside a block's text. */
internal class SentenceRange(val id: Int, val start: Int, val end: Int)

/** What the follow-scroll needs from a laid-out block: the top of a sentence's first line inside the item. */
internal class BlockTextLayout(private val ranges: List<SentenceRange>, private val layout: TextLayoutResult, private val textTopPx: Int) {
    fun sentenceTop(sentenceId: Int): Int? {
        val range = ranges.firstOrNull { it.id == sentenceId } ?: return null
        return textTopPx + layout.getLineTop(layout.getLineForOffset(range.start)).toInt()
    }
}

/** Per-block layouts, written by the items as they lay out and read by the follow-scroll (not snapshot state). */
internal class ReaderLayouts {
    private val byBlock = HashMap<Int, BlockTextLayout>()

    fun put(blockIndex: Int, layout: BlockTextLayout) {
        byBlock[blockIndex] = layout
    }

    fun sentenceTop(blockIndex: Int, sentenceId: Int): Int? = byBlock[blockIndex]?.sentenceTop(sentenceId)
}

/** The reader text (file 04): one item per block, keyed by block id; only the current block reads the highlight. */
@Composable
internal fun ReaderText(
    blocks: List<ReaderBlock>,
    textSize: TextSize,
    readSkipped: Boolean,
    currentSentence: State<Int?>,
    currentBlock: State<Int?>,
    listState: LazyListState,
    layouts: ReaderLayouts,
    contentPadding: PaddingValues,
    scrollConnection: NestedScrollConnection,
    onTapSentence: (Int) -> Unit,
    onTapBlock: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val styles = rememberReaderStyles(textSize)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().widthIn(max = MAX_COLUMN_WIDTH).nestedScroll(scrollConnection),
        ) {
            items(blocks, key = { it.id }, contentType = { it.kind }) { block ->
                val highlighted by remember(block.index) {
                    derivedStateOf { if (currentBlock.value == block.index) currentSentence.value else null }
                }
                when (block.kind) {
                    BlockKind.Code, BlockKind.Table -> BoxBlock(block, styles, readSkipped, highlighted, layouts, onTapBlock)
                    else -> TextBlock(block, styles, readSkipped, highlighted, layouts, onTapSentence, onTapBlock)
                }
            }
        }
    }
}

internal class ReaderStyles(val paragraph: TextStyle, val h1: TextStyle, val h2: TextStyle, val h3: TextStyle, val caption: TextStyle, val code: TextStyle, val cue: TextStyle)

@Composable
private fun rememberReaderStyles(textSize: TextSize): ReaderStyles {
    val type = LectriceTheme.type
    return remember(textSize, type) {
        val scale = textSize.scale
        ReaderStyles(
            paragraph = type.serif(textSize.paragraphSp.sp, (textSize.paragraphSp * LINE_HEIGHT_FACTOR).sp),
            h1 = type.serif((H1_SP * scale).sp, (H1_LINE_SP * scale).sp, FontWeight.SemiBold),
            h2 = type.serif((H2_SP * scale).sp, (H2_LINE_SP * scale).sp, FontWeight.SemiBold),
            h3 = type.serif((H3_SP * scale).sp, (H3_LINE_SP * scale).sp, FontWeight.Bold),
            caption = type.bodyS.copy(fontStyle = FontStyle.Italic),
            code = type.code,
            cue = type.cue,
        )
    }
}

/** The text of one block with its sentence ranges and the chips drawn inline. */
private class BlockText(val text: AnnotatedString, val ranges: List<SentenceRange>, val chips: List<IntRange>, val inline: Map<String, InlineTextContent>)

@Composable
private fun TextBlock(
    block: ReaderBlock,
    styles: ReaderStyles,
    readSkipped: Boolean,
    highlighted: Int?,
    layouts: ReaderLayouts,
    onTapSentence: (Int) -> Unit,
    onTapBlock: (Int) -> Unit,
) {
    val c = LectriceTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chipStyle = LectriceTheme.type.caption
    val style = when (block.kind) {
        BlockKind.Heading -> when (block.level) {
            1 -> styles.h1
            2 -> styles.h2
            else -> styles.h3
        }
        BlockKind.Caption -> styles.caption
        else -> styles.paragraph
    }
    val built = remember(block, style, chipStyle, density) {
        val builder = AnnotatedString.Builder()
        val ranges = ArrayList<SentenceRange>(block.sentences.size)
        val chips = ArrayList<IntRange>()
        val inline = HashMap<String, InlineTextContent>()
        block.sentences.forEachIndexed { index, sentence ->
            if (index > 0) builder.append(' ')
            block.chipsBeforeSentence[sentence.id]?.let { chip ->
                val id = "chip:${chip.label}"
                if (id !in inline) {
                    // The chip's own paddings in dp plus slack: a placeholder even a pixel short wrapped "p. 3" to "p." (B5).
                    val width = with(density) { (measurer.measure(PAGE_PREFIX + chip.label, chipStyle).size.width.toDp() + CHIP_MARGIN + CHIP_PADDING * 2 + CHIP_SLACK).toSp() }
                    inline[id] = InlineTextContent(Placeholder(width, CHIP_HEIGHT_SP.sp, PlaceholderVerticalAlign.TextCenter)) { PageChipView(chip) }
                }
                val start = builder.length
                builder.appendInlineContent(id, chip.label)
                chips += start until builder.length
            }
            val start = builder.length
            builder.append(sentence.text)
            ranges += SentenceRange(sentence.id, start, builder.length)
        }
        BlockText(builder.toAnnotatedString(), ranges, chips, inline)
    }
    val range = highlighted?.let { id -> built.ranges.firstOrNull { it.id == id } }
    val text = remember(built, range, c.hlFg) {
        if (range == null) built.text else AnnotatedString.Builder(built.text).apply { addStyle(SpanStyle(color = c.hlFg), range.start, range.end) }.toAnnotatedString()
    }
    val topPadding = when (block.kind) {
        BlockKind.Heading -> if (block.index == 0) 0.dp else headingTopGap(block.level)
        else -> 0.dp
    }
    val bottomPadding = when (block.kind) {
        BlockKind.Heading -> headingBottomGap(block.level)
        else -> PARAGRAPH_GAP
    }
    val topPaddingPx = with(density) { topPadding.roundToPx() }
    val layoutRef = remember { arrayOfNulls<TextLayoutResult>(1) }
    val playFromHere = stringResource(R.string.reader_play_from_here)
    val firstSentence = block.sentences.firstOrNull()?.id
    val tapSentence: (Int) -> Unit = if (block.kind == BlockKind.Caption) ({ onTapBlock(block.index) }) else onTapSentence
    val isList = block.kind == BlockKind.ListItem
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = SIDE_PADDING, end = SIDE_PADDING, top = topPadding, bottom = bottomPadding)
            .semantics(mergeDescendants = true) {
                if (firstSentence != null) customActions = listOf(CustomAccessibilityAction(playFromHere) { tapSentence(firstSentence); true })
            },
    ) {
        if (isList) Text(BULLET, Modifier.padding(start = LIST_BULLET_OFFSET), style = style, color = c.fg)
        HighlightedText(
            text = text,
            style = style.copy(color = if (block.kind == BlockKind.Caption) c.fg2 else c.fg),
            inline = built.inline,
            ranges = built.ranges,
            chips = built.chips,
            range = range,
            layoutRef = layoutRef,
            onLayout = { layout -> layouts.put(block.index, BlockTextLayout(built.ranges, layout, topPaddingPx)) },
            onTapSentence = tapSentence,
            modifier = if (isList) Modifier.padding(start = LIST_INDENT) else Modifier,
        )
    }
}

/** One text with the current sentence's rounded `hl` background drawn behind it, cross-fading 120 ms on change. */
@Composable
private fun HighlightedText(
    text: AnnotatedString,
    style: TextStyle,
    inline: Map<String, InlineTextContent>,
    ranges: List<SentenceRange>,
    chips: List<IntRange>,
    range: SentenceRange?,
    layoutRef: Array<TextLayoutResult?>,
    onLayout: (TextLayoutResult) -> Unit,
    onTapSentence: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(1f) }
    var shown by remember { mutableStateOf<SentenceRange?>(null) }
    var fading by remember { mutableStateOf<SentenceRange?>(null) }
    LaunchedEffect(range) {
        if (shown === range) return@LaunchedEffect
        fading = shown
        shown = range
        progress.snapTo(0f)
        if (reduced) progress.snapTo(1f) else progress.animateTo(1f, tween(HIGHLIGHT_MS))
        fading = null
    }
    val padPx = with(LocalDensity.current) { HIGHLIGHT_PAD.toPx() }
    val radiusPx = with(LocalDensity.current) { HIGHLIGHT_RADIUS.toPx() }
    BasicText(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val layout = layoutRef[0] ?: return@drawBehind
                val p = progress.value
                fading?.let { drawHighlight(layout, it, c.hl.copy(alpha = c.hl.alpha * (1f - p)), padPx, radiusPx) }
                shown?.let { drawHighlight(layout, it, c.hl.copy(alpha = c.hl.alpha * p), padPx, radiusPx) }
            }
            .pointerInput(ranges, chips) {
                detectTapGestures { position ->
                    val layout = layoutRef[0] ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(position)
                    if (chips.any { offset in it }) return@detectTapGestures
                    val hit = ranges.lastOrNull { it.start <= offset } ?: ranges.firstOrNull() ?: return@detectTapGestures
                    onTapSentence(hit.id)
                }
            },
        style = style,
        onTextLayout = { layout ->
            layoutRef[0] = layout
            onLayout(layout)
        },
        inlineContent = inline,
    )
}

private fun DrawScope.drawHighlight(layout: TextLayoutResult, range: SentenceRange, color: Color, padPx: Float, radiusPx: Float) {
    val end = (range.end - 1).coerceAtLeast(range.start)
    if (range.end <= range.start || end >= layout.layoutInput.text.length) return
    val firstLine = layout.getLineForOffset(range.start)
    val lastLine = layout.getLineForOffset(end)
    for (line in firstLine..lastLine) {
        val left = if (line == firstLine) layout.getHorizontalPosition(range.start, usePrimaryDirection = true) else layout.getLineLeft(line)
        val right = if (line == lastLine) layout.getHorizontalPosition(range.end, usePrimaryDirection = true) else layout.getLineRight(line)
        val rect = Rect(minOf(left, right) - padPx, layout.getLineTop(line), maxOf(left, right) + padPx, layout.getLineBottom(line))
        drawRoundRect(color, Offset(rect.left, rect.top), Size(rect.width, rect.height), CornerRadius(radiusPx))
    }
}

/** Code and table blocks: a `bg2` box that scrolls sideways with the right fade, then the "Read as …" cue line. */
@Composable
private fun BoxBlock(block: ReaderBlock, styles: ReaderStyles, readSkipped: Boolean, highlighted: Int?, layouts: ReaderLayouts, onTapBlock: (Int) -> Unit) {
    val c = LectriceTheme.colors
    val scroll = rememberScrollState()
    val boxText = block.boxText.orEmpty()
    val currentInBox = if (readSkipped && highlighted != null) block.sentences.firstOrNull { it.id == highlighted } else null
    val text = remember(boxText, currentInBox, c.hl, c.hlFg) {
        val at = currentInBox?.let { boxText.indexOf(it.text.trim()).takeIf { k -> k >= 0 } }
        if (at == null) AnnotatedString(boxText) else AnnotatedString.Builder(boxText).apply { addStyle(SpanStyle(background = c.hl, color = c.hlFg), at, at + currentInBox.text.trim().length) }.toAnnotatedString()
    }
    val cueCurrent = block.cue != null && highlighted == block.cue.id
    val description = stringResource(if (block.kind == BlockKind.Table) R.string.reader_table_block_a11y else R.string.reader_code_block_a11y)
    Column(Modifier.fillMaxWidth().padding(horizontal = SIDE_PADDING)) {
        block.chipBeforeBlock?.let { chip ->
            PageChipView(chip, standalone = true)
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(c.bg2)
                .clickable { onTapBlock(block.index) }
                .semantics { contentDescription = description }
                .endFade(scroll, c.bg2),
        ) {
            BasicText(
                text = text,
                modifier = Modifier.horizontalScroll(scroll).padding(12.dp),
                style = styles.code.copy(color = c.fg),
                onTextLayout = { layout ->
                    val ranges = block.sentences.map { SentenceRange(it.id, 0, 0) } + listOfNotNull(block.cue?.let { SentenceRange(it.id, 0, 0) })
                    layouts.put(block.index, BlockTextLayout(ranges, layout, 0))
                },
            )
        }
        if (!readSkipped && block.cue != null) {
            Text(
                stringResource(R.string.reader_read_as, block.cue.text),
                Modifier
                    .padding(top = 8.dp)
                    .then(if (cueCurrent) Modifier.background(c.hl, RoundedCornerShape(HIGHLIGHT_RADIUS)).padding(horizontal = HIGHLIGHT_PAD) else Modifier),
                style = styles.cue,
                color = if (cueCurrent) c.hlFg else c.fg2,
            )
        }
        Spacer(Modifier.height(PARAGRAPH_GAP))
    }
}

/** "p. 265": Roboto 12/16 `fg2`, 1 dp `line` border, radius 6, height 20, padding 0 6, margin-right 6. */
@Composable
internal fun PageChipView(chip: PageChip, standalone: Boolean = false) {
    val c = LectriceTheme.colors
    val description = if (chip.label == chip.pdfPage.toString()) stringResource(R.string.reader_page_a11y, chip.label) else stringResource(R.string.reader_page_pdf_a11y, chip.label, chip.pdfPage)
    val height = with(LocalDensity.current) { CHIP_HEIGHT_SP.sp.toDp() }
    Box(
        Modifier
            .padding(end = CHIP_MARGIN)
            .height(height)
            .border(1.dp, c.line, RoundedCornerShape(6.dp))
            .padding(horizontal = CHIP_PADDING)
            .then(if (standalone) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(PAGE_PREFIX + chip.label, style = LectriceTheme.type.caption, color = c.fg2, maxLines = 1)
    }
}

private fun headingTopGap(level: Int): Dp = when (level) {
    1 -> H1_TOP
    2 -> H2_TOP
    else -> H3_TOP
}

private fun headingBottomGap(level: Int): Dp = when (level) {
    1 -> 16.dp
    2 -> 12.dp
    else -> 8.dp
}

private const val LINE_HEIGHT_FACTOR = 1.68f
private const val H1_SP = 26f
private const val H1_LINE_SP = 34f
private const val H2_SP = 22f
private const val H2_LINE_SP = 30f
private const val H3_SP = 19f
private const val H3_LINE_SP = 28f
private const val CHIP_HEIGHT_SP = 20
private const val BULLET = "•"
/** A no-break space: the number never wraps away from "p.". */
private const val PAGE_PREFIX = "p.\u00A0"
private val SIDE_PADDING = 16.dp
private val PARAGRAPH_GAP = 16.dp
private val LIST_INDENT = 24.dp
private val LIST_BULLET_OFFSET = 8.dp
private val CHIP_MARGIN = 6.dp
private val CHIP_PADDING = 6.dp
private val CHIP_SLACK = 1.dp
private val HIGHLIGHT_PAD = 2.dp
private val HIGHLIGHT_RADIUS = 4.dp
private val MAX_COLUMN_WIDTH = 640.dp
/* the CSS margins collapse with the 16 dp paragraph gap before them: 48 / 32 / 24 in all */
private val H1_TOP = 32.dp
private val H2_TOP = 16.dp
private val H3_TOP = 8.dp
