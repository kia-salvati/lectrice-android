package app.lectrice.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.LectriceIcon
import app.lectrice.core.designsystem.LectriceIconButton
import app.lectrice.core.designsystem.LectriceIcons
import app.lectrice.core.designsystem.LectriceTextButton
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.designsystem.BottomSheet
import app.lectrice.core.designsystem.RadioMark
import app.lectrice.core.designsystem.SheetBottomSpacer
import app.lectrice.core.designsystem.SheetHeader
import app.lectrice.core.designsystem.Text
import app.lectrice.core.model.AudioSource

/** File 23 B2/B4: "Listen with" — two radio cards, then "Use another file…" and "Remove link". */
@Composable
internal fun AudioSourceSheet(
    audiobook: AudiobookUi,
    onSelect: (AudioSource) -> Unit,
    onPickAudiobook: () -> Unit,
    onRemoveLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LectriceTheme.colors
    val title = stringResource(R.string.audio_source_title)
    BottomSheet(onDismiss, contentDescription = title) {
        SheetHeader(title) { LectriceIconButton(LectriceIcons.Close, stringResource(R.string.common_close), onDismiss) }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceCard(stringResource(R.string.audio_source_generated), selected = !audiobook.active, warn = false, onClick = { onSelect(AudioSource.Generated) }) { _, fg2 ->
                Text(stringResource(R.string.audio_source_generated_sub), Modifier.padding(start = CARD_INDENT, top = 4.dp), style = LectriceTheme.type.bodyS, color = fg2)
            }
            SourceCard(stringResource(R.string.audio_source_file), selected = audiobook.active, warn = audiobook.missing, onClick = { onSelect(AudioSource.Audiobook) }) { fg, fg2 ->
                Text(audiobook.fileName, Modifier.padding(start = CARD_INDENT, top = 4.dp), style = LectriceTheme.type.body, color = fg)
                if (audiobook.missing) {
                    Row(Modifier.padding(start = CARD_INDENT, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LectriceIcon(LectriceIcons.Warning, null, tint = fg, size = 20.dp)
                        Text(stringResource(R.string.audio_source_missing), style = LectriceTheme.type.label, color = fg)
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        LectriceTextButton(stringResource(R.string.audio_source_find), onPickAudiobook, color = fg, style = LectriceTheme.type.label)
                    }
                } else {
                    Text(audiobook.detail, Modifier.padding(start = CARD_INDENT, top = 4.dp), style = LectriceTheme.type.bodyS, color = fg2)
                    Text(audiobook.follows, Modifier.padding(start = CARD_INDENT), style = LectriceTheme.type.bodyS, color = fg2)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LectriceTextButton(stringResource(R.string.audio_source_another), onPickAudiobook)
                LectriceTextButton(stringResource(R.string.audio_source_remove), onRemoveLink, color = c.errFg)
            }
        }
        SheetBottomSpacer()
    }
}

/** The look of the model cards (S2); [warn] = B4's "File missing" colours. [lines] gets the card's text colours. */
@Composable
private fun SourceCard(title: String, selected: Boolean, warn: Boolean, onClick: () -> Unit, lines: @Composable ColumnScope.(fg: Color, fg2: Color) -> Unit) {
    val c = LectriceTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val bg = if (warn) c.warnBg else if (selected) c.accentContainer else c.bg2
    val fg = if (warn) c.warnFg else if (selected) c.onAccentContainer else c.fg
    val fg2 = if (warn) c.warnFg else if (selected) c.onAccentContainer else c.fg2
    val outline = if (warn) c.warnFg else if (selected) c.accent else c.line
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(if (selected || warn) 2.dp else 1.dp, outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioMark(selected, selectedColor = fg)
            Text(title, Modifier.weight(1f), style = LectriceTheme.type.titleM, color = fg)
        }
        lines(fg, fg2)
    }
}

private val CARD_INDENT = 36.dp
