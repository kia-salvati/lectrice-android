package app.lectrice.feature.settings

import androidx.annotation.StringRes
import app.lectrice.core.model.ErrorName

// `StorageFull` is not mapped here: its message carries the missing bytes, so the screens build it themselves.

/** The title of the failed-import sheet, when the error has one. */
@StringRes
internal fun ErrorName.importFailedTitle(): Int? = when (this) {
    ErrorName.ModelChecksumMismatch -> R.string.import_failed_title
    else -> null
}

@StringRes
internal fun ErrorName.importFailedBody(): Int = when (this) {
    ErrorName.FileUnreadable -> R.string.import_unreadable
    ErrorName.ModelChecksumMismatch -> R.string.import_failed_body
    else -> R.string.common_something_wrong
}

@StringRes
internal fun ErrorName.downloadFailedMessage(): Int = when (this) {
    ErrorName.ModelChecksumMismatch -> R.string.download_damaged
    else -> R.string.download_failed
}
