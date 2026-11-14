package app.lectrice.feature.reader

import androidx.annotation.StringRes
import app.lectrice.core.model.ErrorName

/** The reader's words for a failed audiobook link. */
@StringRes
internal fun ErrorName.audiobookMessage(): Int = when (this) {
    ErrorName.AudiobookUnreadable -> R.string.reader_snack_audiobook_unreadable
    ErrorName.AudiobookOtherBook -> R.string.reader_snack_audiobook_other_book
    ErrorName.AudiobookNotLectrice -> R.string.reader_snack_audiobook_not_lectrice
    else -> R.string.reader_snack_audiobook_failed
}

/** The export page's words for a failed export. */
@StringRes
internal fun ErrorName.exportMessage(): Int = when (this) {
    ErrorName.StorageFull -> R.string.export_snack_storage_full
    ErrorName.VoiceUnknown -> R.string.export_snack_voice_unknown
    else -> R.string.reader_failed
}
