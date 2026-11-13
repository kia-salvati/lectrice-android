package app.lectrice.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lectrice.core.model.StatusPart
import kotlin.math.roundToInt

// ===== text and icons =====

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LectriceTheme.type.body,
    color: Color = LectriceTheme.colors.fg,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Unspecified,
    textDecoration: TextDecoration? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign, textDecoration = textDecoration),
        overflow = overflow,
        maxLines = maxLines,
    )
}

@Composable
fun LectriceIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LectriceTheme.colors.fg, size: Dp = 24.dp) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

// ===== buttons =====

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = LectriceTheme.colors
    val bg = if (enabled) c.accent else c.line
    val fg = if (enabled) c.onAccent else c.fg2
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = null, indication = ripple(color = fg), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LectriceIcon(icon, null, tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = LectriceTheme.type.labelL, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = LectriceTheme.colors
    val fg = if (enabled) c.fg else c.fg2
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, c.line, CircleShape)
            .clickable(interactionSource = null, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LectriceIcon(icon, null, tint = fg, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = LectriceTheme.type.labelL, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The text button; [color] = `errFg` for destructive ones, `inverseFg` + [underline] on a snackbar. */
@Composable
fun LectriceTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = LectriceTheme.colors.accent,
    style: TextStyle = LectriceTheme.type.labelL,
    underline: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LectriceTheme.colors
    val fg = if (enabled) color else c.fg2
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, style = style, color = fg, textDecoration = if (underline) TextDecoration.Underline else null)
    }
}

@Composable
fun LectriceIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LectriceTheme.colors.fg,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = ripple(bounded = false, radius = 20.dp), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        LectriceIcon(icon, null, tint = if (enabled) tint else LectriceTheme.colors.fg2, size = iconSize)
    }
}

/** The round play/pause button: a [discSize] accent disc inside a [size] touch area; preparing = container colours + a ring. */
@Composable
fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    discSize: Dp = 48.dp,
    preparing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = LectriceTheme.colors
    val disc = if (preparing) c.accentContainer else c.accent
    val onDisc = if (preparing) c.onAccentContainer else c.onAccent
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(interactionSource = null, indication = ripple(bounded = false, radius = size / 2), role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(discSize).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            LectriceIcon(if (playing) LectriceIcons.Pause else LectriceIcons.PlayArrow, null, tint = onDisc)
        }
        if (preparing) Spinner(size = size - 6.dp, stroke = 2.dp)
    }
}

// ===== rows, headers, surfaces =====

/** A Quiet-density list row: min 48 dp, padding 8 16, title 16/20 500, meta 14/18. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    titleColor: Color = LectriceTheme.colors.fg,
    subtitleColor: Color = LectriceTheme.colors.fg2,
    minHeight: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    /** TalkBack actions for what a gesture does (README §4.5), merged into the row's own node. */
    customActions: List<CustomAccessibilityAction> = emptyList(),
    titleMaxLines: Int = Int.MAX_VALUE,
    leading: (@Composable () -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) {
        Modifier.combinedClickable(interactionSource = null, indication = ripple(), role = Role.Button, onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
                if (customActions.isNotEmpty()) this.customActions = customActions
            }
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leadingIcon != null) LectriceIcon(leadingIcon, null, tint = titleColor)
        Column(Modifier.weight(1f)) {
            Text(title, style = LectriceTheme.type.rowTitle, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = LectriceTheme.type.rowMeta, color = subtitleColor)
            extra?.invoke(this)
        }
        trailing?.invoke(this)
    }
}

/** One-line status from [StatusPart]s joined by " · "; only the parts that shrink get the ellipsis (file 07 §3.3). */
@Composable
fun StatusLine(parts: List<StatusPart>, modifier: Modifier = Modifier, style: TextStyle = LectriceTheme.type.bodyS, color: Color = LectriceTheme.colors.fg2) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text(" · ", style = style, color = color, maxLines = 1)
            Text(
                part.text,
                if (part.shrinks) Modifier.weight(1f, fill = false) else Modifier,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ChevronRight(modifier: Modifier = Modifier) {
    LectriceIcon(LectriceIcons.ChevronRight, null, modifier, tint = LectriceTheme.colors.fg2)
}

@Composable
fun Divider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(LectriceTheme.colors.line))
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp).semantics { heading() },
        style = LectriceTheme.type.sectionHeader,
        color = LectriceTheme.colors.accent,
    )
}

/** Back arrow (or ✕) + screen title; 64 dp under the status bar. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, backDescription: String, modifier: Modifier = Modifier, backIcon: ImageVector = LectriceIcons.ArrowBack) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LectriceIconButton(backIcon, backDescription, onBack)
        Text(title, Modifier.weight(1f).semantics { heading() }, style = LectriceTheme.type.titleL)
    }
}

/** Solid `bg` behind the status bar icons, drawn over a screen whose content scrolls up under them. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(LectriceTheme.colors.bg))
}

/** `bg2` + 1 dp `line`, no shadow. */
@Composable
fun LectriceCard(modifier: Modifier = Modifier, radius: Dp = 16.dp, padding: Dp = 16.dp, background: Color = LectriceTheme.colors.bg2, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(modifier.clip(shape).background(background).border(1.dp, LectriceTheme.colors.line, shape).padding(padding), content = content)
}

/** The reassurance card: a 20 dp info icon beside body text. */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier) {
    LectriceCard(modifier, radius = 12.dp, padding = 0.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LectriceIcon(LectriceIcons.Info, null, Modifier.padding(top = 2.dp), size = 20.dp)
            Text(text, style = LectriceTheme.type.body)
        }
    }
}

@Composable
fun CoverTile(initials: String, hue: Int, modifier: Modifier = Modifier, width: Dp = 40.dp, height: Dp = 56.dp, radius: Dp = 8.dp) {
    val c = LectriceTheme.colors
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(radius)).background(c.tile(hue)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = LectriceTheme.type.serif((height.value * TILE_TEXT_SHARE).sp, (height.value * TILE_TEXT_SHARE).sp, FontWeight.SemiBold), color = c.onTile(hue), maxLines = 1)
    }
}

/** A grey block standing in for a row while the list loads. */
@Composable
fun PlaceholderBlock(modifier: Modifier = Modifier, radius: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(LectriceTheme.colors.bg2))
}

// ===== controls =====

@Composable
fun LectriceSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null, enabled: Boolean = true) {
    val c = LectriceTheme.colors
    val reduced = LocalReducedMotion.current
    val thumbSize by animateDpAsState(if (checked) 24.dp else 16.dp, motionSpring(reduced), label = "thumbSize")
    val thumbX by animateDpAsState(if (checked) 24.dp else 6.dp, motionSpring(reduced), label = "thumbX")
    val track = when {
        !enabled -> c.bg2
        checked -> c.accent
        else -> c.bg2
    }
    val ring = when {
        !enabled -> c.line
        checked -> c.accent
        else -> c.fg2
    }
    val thumb = when {
        !enabled -> c.line
        checked -> c.onAccent
        else -> c.fg2
    }
    Box(
        modifier
            .size(52.dp, 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(track).border(2.dp, ring, CircleShape)) {
            Box(Modifier.offset(x = thumbX, y = (32.dp - thumbSize) / 2).size(thumbSize).clip(CircleShape).background(thumb))
        }
    }
}

/** The 24 dp radio circle; the row around it carries the click and the semantics. */
@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, selectedColor: Color = LectriceTheme.colors.accent) {
    val c = LectriceTheme.colors
    val ring = when {
        !enabled -> c.line
        selected -> selectedColor
        else -> c.fg2
    }
    Box(modifier.size(24.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(selectedColor))
    }
}

@Composable
fun CheckboxRow(checked: Boolean, text: String, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LectriceTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LectriceIcon(if (checked) LectriceIcons.CheckBox else LectriceIcons.CheckBoxOutlineBlank, null, tint = if (checked) c.accent else c.fg2)
        Text(text, style = LectriceTheme.type.bodyS)
    }
}

/** The `.segc`: a 40 dp pill of equal segments in a 48 dp touch strip; two stacked pills when the labels don't fit one row. */
@Composable
fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val style = LectriceTheme.type.label
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val rowWidth = constraints.maxWidth
        val labelPadding = with(LocalDensity.current) { (SEGMENT_LABEL_PADDING * 2).roundToPx() }
        val fits = remember(options, style, measurer, rowWidth, labelPadding) {
            options.all { measurer.measure(it, style, maxLines = 1).size.width + labelPadding <= rowWidth / options.size }
        }
        val perRow = (if (fits) options.size else (options.size + 1) / 2).coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.indices.chunked(perRow).forEach { indices -> SegmentRow(options, indices, selectedIndex, onSelect) }
        }
    }
}

/** One pill of [SegmentedControl], showing the options at [indices]. */
@Composable
private fun SegmentRow(options: List<String>, indices: List<Int>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val c = LectriceTheme.colors
    val lineWidth = 1.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .drawBehind {
                val inset = 4.dp.toPx()
                val pill = Path().apply { addRoundRect(RoundRect(Rect(0f, inset, size.width, size.height - inset), CornerRadius(size.height))) }
                val segment = size.width / indices.size
                val selectedSlot = indices.indexOf(selectedIndex)
                clipPath(pill) {
                    drawRect(c.bg2)
                    if (selectedSlot >= 0) drawRect(c.accentContainer, Offset(segment * selectedSlot, 0f), Size(segment, size.height))
                    for (i in 1 until indices.size) {
                        drawLine(c.line, Offset(segment * i, 0f), Offset(segment * i, size.height), lineWidth.toPx())
                    }
                }
                drawPath(pill, c.line, style = Stroke(lineWidth.toPx()))
            },
    ) {
        indices.forEach { index ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = SEGMENT_LABEL_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Text(options[index], style = LectriceTheme.type.label, color = if (selected) c.onAccentContainer else c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val SEGMENT_LABEL_PADDING = 4.dp

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LectriceTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.height(48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(32.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(c.accentContainer) else Modifier.border(1.dp, c.line, shape))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) LectriceIcon(LectriceIcons.Check, null, tint = c.onAccentContainer, size = 18.dp)
            val color = when {
                !enabled -> c.fg2
                selected -> c.onAccentContainer
                else -> c.fg
            }
            Text(text, style = LectriceTheme.type.label, color = color, maxLines = 1)
        }
    }
}

/** A discrete slider with [stepCount] stops (ticks 2 × 8 `fg2`, thumb 20 `accent`) in a 48 dp touch strip. */
@Composable
fun StepSlider(stepCount: Int, value: Int, onValueChange: (Int) -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val c = LectriceTheme.colors
    val last = stepCount - 1
    fun nearest(x: Float, width: Float): Int = (x / width * last).roundToInt().coerceIn(0, last)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .progressSemantics(value.toFloat(), 0f..last.toFloat(), last - 1)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                setProgress { target ->
                    onValueChange(target.roundToInt().coerceIn(0, last))
                    true
                }
            }
            .pointerInput(stepCount) { detectTapGestures { offset -> onValueChange(nearest(offset.x, size.width.toFloat())) } }
            .pointerInput(stepCount) { detectHorizontalDragGestures { change, _ -> onValueChange(nearest(change.position.x, size.width.toFloat())) } },
    ) {
        val trackY = size.height / 2
        val trackHeight = 4.dp.toPx()
        val thumbX = size.width * value / last
        drawRoundRect(c.line, Offset(0f, trackY - trackHeight / 2), Size(size.width, trackHeight), CornerRadius(trackHeight / 2))
        drawRoundRect(c.accent, Offset(0f, trackY - trackHeight / 2), Size(thumbX, trackHeight), CornerRadius(trackHeight / 2))
        for (i in 0..last) {
            val x = size.width * i / last
            drawRect(c.fg2, Offset(x - 1.dp.toPx(), trackY - 4.dp.toPx()), Size(2.dp.toPx(), 8.dp.toPx()))
        }
        drawCircle(c.accent, 10.dp.toPx(), Offset(thumbX, trackY))
    }
}

// ===== progress =====

/** 4 dp (8 dp when [big]) bar; [progress] null = indeterminate. */
@Composable
fun LinearProgress(progress: Float?, modifier: Modifier = Modifier, big: Boolean = false, contentDescription: String? = null) {
    val c = LectriceTheme.colors
    val height = if (big) 8.dp else 4.dp
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val slide by transition.animateFloat(
        initialValue = -INDETERMINATE_WIDTH,
        targetValue = 1f + INDETERMINATE_WIDTH,
        animationSpec = infiniteRepeatable(tween(INDETERMINATE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "slide",
    )
    val semantics = if (progress != null) Modifier.progressSemantics(progress.coerceIn(0f, 1f)) else Modifier.progressSemantics()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(semantics)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(c.line, cornerRadius = radius)
        if (progress != null) {
            drawRoundRect(c.accent, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = radius)
        } else {
            val clip = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), radius)) }
            clipPath(clip) {
                drawRoundRect(c.accent, Offset(size.width * slide, 0f), Size(size.width * INDETERMINATE_WIDTH, size.height), radius)
            }
        }
    }
}

/** The 56 × 4 bar beside "Continue · 34 %". */
@Composable
fun ProgressBarSmall(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgress(fraction, modifier.width(56.dp))
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, stroke: Dp = 2.dp, color: Color = LectriceTheme.colors.accent) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "angle")
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        rotate(angle) {
            drawArc(color, 0f, SPINNER_ARC, useCenter = false, style = Stroke(stroke.toPx(), cap = StrokeCap.Butt))
        }
    }
}

/** "Playing" mark: three rounded bars bouncing between 4 and 14 dp with offset phases; static 8/14/10 under reduced motion. */
@Composable
fun Equalizer(modifier: Modifier = Modifier, tint: Color = LectriceTheme.colors.fg) {
    val reduced = LocalReducedMotion.current
    val heights: List<Dp> = if (reduced) {
        EQ_STATIC_HEIGHTS
    } else {
        val transition = rememberInfiniteTransition(label = "equalizer")
        EQ_PHASE_OFFSETS_MS.map { offset ->
            val height by transition.animateFloat(
                initialValue = EQ_MIN_HEIGHT.value,
                targetValue = EQ_MAX_HEIGHT.value,
                animationSpec = infiniteRepeatable(tween(EQ_HALF_LOOP_MS, easing = LinearEasing), RepeatMode.Reverse, StartOffset(offset)),
                label = "bar",
            )
            height.dp
        }
    }
    val width = EQ_BAR_WIDTH * heights.size + EQ_BAR_GAP * (heights.size - 1)
    Canvas(modifier.size(width, EQ_MAX_HEIGHT).clearAndSetSemantics { }) {
        val bar = EQ_BAR_WIDTH.toPx()
        val step = bar + EQ_BAR_GAP.toPx()
        heights.forEachIndexed { index, height ->
            val h = height.toPx()
            drawRoundRect(tint, Offset(index * step, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

// ===== messages =====

enum class BannerKind { Warn, Err }

/** Inline message: icon + text, actions on their own line at the right. */
@Composable
fun Banner(kind: BannerKind, icon: ImageVector, text: String, modifier: Modifier = Modifier, extra: (@Composable ColumnScope.() -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null) {
    val c = LectriceTheme.colors
    val bg = if (kind == BannerKind.Warn) c.warnBg else c.errBg
    val fg = if (kind == BannerKind.Warn) c.warnFg else c.errFg
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LectriceIcon(icon, null, tint = fg)
            Column(Modifier.weight(1f)) {
                Text(text, style = LectriceTheme.type.bodyS, color = fg)
                extra?.invoke(this)
            }
        }
        if (actions != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Text button inside a banner: the banner's own colour, 14/20 500. */
@Composable
fun BannerAction(text: String, kind: BannerKind, onClick: () -> Unit) {
    val c = LectriceTheme.colors
    LectriceTextButton(text, onClick, color = if (kind == BannerKind.Warn) c.warnFg else c.errFg, style = LectriceTheme.type.label)
}

@Composable
fun LectriceSnackbar(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = LectriceTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.inverseBg)
            .padding(start = 16.dp, end = if (actionLabel != null) 8.dp else 16.dp)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 14.dp), style = LectriceTheme.type.bodyS, color = c.inverseFg)
        if (actionLabel != null && onAction != null) {
            LectriceTextButton(actionLabel, onAction, color = c.inverseFg, style = LectriceTheme.type.label, underline = true)
        }
    }
}

/** A modal dialog over its own scrim: title, body in `fg`, optional extra content, right-aligned text buttons. */
@Composable
fun LectriceDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = LectriceTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(c.scrim)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(c.bg2)
                .clickable(interactionSource = null, indication = null, onClick = {})
                .semantics { this.contentDescription = title }
                .padding(24.dp),
        ) {
            Text(title, style = LectriceTheme.type.titleM)
            Spacer(Modifier.height(16.dp))
            Text(body, style = LectriceTheme.type.body)
            extra?.invoke(this)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
        }
    }
}

private const val TILE_TEXT_SHARE = 0.4f
private const val INDETERMINATE_WIDTH = 0.4f
private const val INDETERMINATE_MS = 1400
private const val SPIN_MS = 1000
private const val SPINNER_ARC = 270f
private const val FULL_TURN = 360f
private const val EQ_HALF_LOOP_MS = 300
private val EQ_PHASE_OFFSETS_MS = listOf(0, 200, 400)
private val EQ_BAR_WIDTH = 3.dp
private val EQ_BAR_GAP = 2.dp
private val EQ_MIN_HEIGHT = 4.dp
private val EQ_MAX_HEIGHT = 14.dp
private val EQ_STATIC_HEIGHTS = listOf(8.dp, 14.dp, 10.dp)
