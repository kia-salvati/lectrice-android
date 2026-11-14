package app.lectrice.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookMode

/** Typed reader labels to the screen's words. */
@Composable
internal fun PrepareSummary.text(): String = when (this) {
    PrepareSummary.Off -> stringResource(R.string.prepare_off)
    PrepareSummary.WaitsForCharger -> stringResource(R.string.prepare_waits_for_charger)
    is PrepareSummary.Preparing -> computer?.let { stringResource(R.string.prepare_running_on, it, percent) } ?: stringResource(R.string.prepare_running, percent)
    is PrepareSummary.Ready -> stringResource(R.string.prepare_ready_up_to, upTo)
}

/** "2 h 41 min · Heart · made at 1.5× · from this phone" */
@Composable
internal fun AudiobookUi.detailText(): String {
    val origin = stringResource(if (kind == AudiobookKind.OwnExport) R.string.audiobook_from_this_phone else R.string.audiobook_imported)
    val made = madeAt?.let { stringResource(R.string.audiobook_made_at, it.label) }
    return listOfNotNull(duration, voiceName, made, origin).joinToString(" · ")
}

@Composable
internal fun AudiobookUi.followsText(): String = stringResource(
    when (mode) {
        AudiobookMode.Sentences -> R.string.audiobook_follows_sentences
        AudiobookMode.Chapters -> R.string.audiobook_follows_chapters
        AudiobookMode.Unaligned -> R.string.audiobook_follows_nothing
    },
)
