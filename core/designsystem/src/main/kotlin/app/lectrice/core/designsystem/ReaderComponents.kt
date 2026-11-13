package app.lectrice.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The book slider (file 05 §3.6): 4 dp track, `accent` up to the thumb, `accentContainer` for the audio already made
 * ahead, 2 × 8 chapter ticks, a 20 dp thumb. Dragging shows [label] for the finger's fraction in a bubble above the
 * thumb; releasing calls [onSeek]. TalkBack steps by 1 %.
 */
@Composable
fun BookSlider(
    fraction: Float,
    bufferedFraction: Float,
    ticks: List<Float>,
    label: (Float) -> String,
    onSeek: (Float) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = LectriceTheme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val shown = dragging ?: fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth()) {
        dragging?.let { at ->
            BubbleLabel(label(at), shown, Modifier.align(Alignment.TopStart))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = SLIDER_BUBBLE_SPACE)
                .height(SLIDER_TOUCH_HEIGHT)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .progressSemantics(shown, 0f..1f, SLIDER_STEPS)
                .semantics {
                    this.contentDescription = contentDescription
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f))
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> dragging = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragging?.let(onSeek)
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val trackY = size.height / 2
            val trackHeight = 4.dp.toPx()
            val radius = CornerRadius(trackHeight / 2)
            val thumbX = size.width * shown
            drawRoundRect(c.line, Offset(0f, trackY - trackHeight / 2), Size(size.width, trackHeight), radius)
            if (bufferedFraction > 0f) {
                drawRect(c.accentContainer, Offset(thumbX, trackY - trackHeight / 2), Size((size.width * bufferedFraction).coerceAtMost(size.width - thumbX), trackHeight))
            }
            drawRoundRect(c.accent, Offset(0f, trackY - trackHeight / 2), Size(thumbX, trackHeight), radius)
            ticks.forEach { tick ->
                drawRect(c.fg2, Offset(size.width * tick.coerceIn(0f, 1f) - 1.dp.toPx(), trackY - 4.dp.toPx()), Size(2.dp.toPx(), 8.dp.toPx()))
            }
            drawCircle(c.accent, 10.dp.toPx(), Offset(thumbX, trackY))
        }
    }
}

@Composable
private fun BubbleLabel(text: String, fraction: Float, modifier: Modifier) {
    val c = LectriceTheme.colors
    val density = LocalDensity.current
    var bubbleWidth by remember { mutableStateOf(0) }
    var rowWidth by remember { mutableStateOf(0) }
    Box(modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width }) {
        val x = with(density) { ((rowWidth * fraction) - bubbleWidth / 2f).coerceIn(0f, (rowWidth - bubbleWidth).coerceAtLeast(0).toFloat()).toDp() }
        Text(
            text,
            Modifier
                .padding(start = x)
                .onSizeChanged { bubbleWidth = it.width }
                .clip(RoundedCornerShape(8.dp))
                .background(c.inverseBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = LectriceTheme.type.label,
            color = c.inverseFg,
            maxLines = 1,
        )
    }
}

/** The chapters search field (file 07 §3.4): 48 dp, radius full, `bg`, 1 dp `line`, search icon, clear ✕ once typed. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearDescription: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val c = LectriceTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(c.bg)
            .border(1.dp, c.line, CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LectriceIcon(LectriceIcons.Search, null, tint = c.fg2)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = LectriceTheme.type.body, color = c.fg2, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
                textStyle = LectriceTheme.type.body.copy(color = c.fg),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (value.isNotEmpty()) LectriceIconButton(LectriceIcons.Close, clearDescription, { onValueChange("") }, size = 40.dp, tint = c.fg2)
    }
}

/** The one-time hint tooltip (R1h, R13h): `inverseBg`, radius 8, padding 12 16, text + underlined "Got it". */
@Composable
fun HintTooltip(text: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = LectriceTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.inverseBg)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 8.dp), style = LectriceTheme.type.bodyS, color = c.inverseFg)
        LectriceTextButton(actionLabel, onAction, color = c.inverseFg, style = LectriceTheme.type.label, underline = true)
    }
}

/**
 * Draws a 24 dp fade on the right edge over the content while [scrollState] can still scroll forward (README §4.4:
 * no scrollbar, a cue instead), in [color] (the box background).
 */
fun Modifier.endFade(scrollState: ScrollableState, color: Color, width: Dp = 24.dp): Modifier = drawWithContent {
    drawContent()
    if (scrollState.canScrollForward) {
        val w = width.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, color), startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f),
            size = Size(w, size.height),
        )
    }
}

/** A thin determinate line (the collapsed pill's book progress, 2 dp `accent` on `line`). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = LectriceTheme.colors
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawRect(c.line)
        drawRect(c.accent, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

private val SLIDER_TOUCH_HEIGHT = 48.dp
private val SLIDER_BUBBLE_SPACE = 36.dp
private const val SLIDER_STEPS = 99
