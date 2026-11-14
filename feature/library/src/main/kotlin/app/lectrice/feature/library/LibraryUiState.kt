package app.lectrice.feature.library

import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.StatusPart

/** The Continue card (file 02 §3.1–3.2). [location] and [timeLeft] fill in once the book's document has loaded. */
data class ContinueCard(
    val documentId: String,
    val title: String,
    val percent: Int,
    val location: List<StatusPart>?,
    /** "4 h 12 min left" */
    val timeLeft: String?,
    val finished: Boolean,
    /** The card's book is the one playing: its button pauses. */
    val playing: Boolean,
    val playLabel: CardPlayLabel,
)

/** TalkBack label of the card's play button; it names the page the card shows. */
sealed interface CardPlayLabel {
    val title: String

    /** Before the book's place is known: "Play {title}". */
    data class Book(override val title: String) : CardPlayLabel

    /** "Play {title} from page {page}" */
    data class FromPage(override val title: String, val page: String) : CardPlayLabel

    /** Finished: "Play {title} again" (it starts over). */
    data class Again(override val title: String) : CardPlayLabel
}

/** A Recent row (file 02 §3.3). */
data class RecentRow(
    val documentId: String,
    val title: String,
    /** Cover tile initials ("DC") and hue 0–5. */
    val initials: String,
    val hue: Int,
    /** "18 pages · opened 2 hours ago" */
    val meta: String,
    /** Null = never started ("Start"). */
    val percent: Int?,
    /** Read to the end: "Finished · listen again" instead of "Continue · 100 %". */
    val finished: Boolean,
)

/** An import row: running ("Reading… cleaning text") or queued ("Waiting…"). */
data class ImportRow(val key: Long, val displayName: String, val running: Boolean)

sealed interface LibraryBanner {
    /** L5: no model installed and no download running. */
    data object VoiceNotInstalled : LibraryBanner

    data class Downloading(val percent: Int) : LibraryBanner

    data object EngineFailed : LibraryBanner
}

/** L4: why a picked file couldn't be imported, with what the message needs. */
data class ImportError(val name: ErrorName, val displayName: String, val bytes: Long?)

/** L7: the long-press book sheet. Sizes are null until `storage_usage` answers. */
data class BookSheet(
    val documentId: String,
    val title: String,
    val initials: String,
    val hue: Int,
    val libraryBytes: Long?,
    val audioBytes: Long?,
)

/** L7c: the remove confirmation. */
data class RemoveConfirm(
    val documentId: String,
    val title: String,
    val freesBytes: Long?,
    val isPlaying: Boolean,
    val hasRunningExport: Boolean,
    /** Finished exports of this book in Lectrice: count and bytes; 0 = no checkbox. */
    val exportCount: Int,
    val exportBytes: Long,
)

sealed interface LibrarySnackbar {
    /** L6 */
    data object AlreadyInLibrary : LibrarySnackbar

    /** L7u, with Undo. */
    data class Removed(val title: String) : LibrarySnackbar

    data class Error(val name: ErrorName) : LibrarySnackbar

    /** File 23 S3: "Linked to *title*." with action **Listen** (`listenToLinked`). */
    data class AudiobookLinked(val documentId: String, val title: String) : LibrarySnackbar

    /** File 23: "This audiobook wasn't made by Lectrice, so it can't follow the text. Playing it comes in a later version." */
    data object AudiobookForeign : LibrarySnackbar

    /** Too many imports are running or waiting: the file was not taken. */
    data object ImportsFull : LibrarySnackbar

    /** The reader found the book gone (removed meanwhile, or its files are missing). */
    data object BookGone : LibrarySnackbar
}

/**
 * A file another app sent ("Open with" / "Share"): nothing is copied or read until the user says yes.
 * Buttons: Not now · Import (a PDF) or Link (an audiobook).
 */
data class IncomingConfirm(val key: Long, val displayName: String, val isAudiobook: Boolean)

/** File 23 S4: the map names a book that isn't in the library. Buttons: Open PDF… (the PDF picker) · Not now. */
data object AudiobookBookMissing

data class LibraryUiState(
    /** True until the first page answers (the screen waits 150 ms before showing placeholders). */
    val loading: Boolean = true,
    val continueCard: ContinueCard? = null,
    val recent: List<RecentRow> = emptyList(),
    val imports: List<ImportRow> = emptyList(),
    val banner: LibraryBanner? = null,
    /** Loading the list failed: inline error banner with "Try again". */
    val listFailed: Boolean = false,
    val importError: ImportError? = null,
    val bookSheet: BookSheet? = null,
    val removeConfirm: RemoveConfirm? = null,
    val snackbar: LibrarySnackbar? = null,
    /** File 23 S4 sheet; non-null while shown. */
    val audiobookBookMissing: AudiobookBookMissing? = null,
    /** The oldest file waiting for a yes; non-null while its dialog shows. */
    val incomingConfirm: IncomingConfirm? = null,
) {
    val isEmpty: Boolean get() = !loading && continueCard == null && recent.isEmpty() && imports.isEmpty() && !listFailed
}

/** What the screen can ask for. */
interface LibraryActions {
    fun openBook(documentId: String, play: Boolean)
    fun togglePlayOnCard()
    fun loadMore()
    fun retryList()
    fun pickedPdf(uri: String)
    /** File 23 S2 "Audiobook file (M4B)" in the import menu. */
    fun pickedAudiobook(uri: String)
    /** The user said yes to the file another app sent: it is imported (a PDF) or linked (an audiobook). */
    fun acceptIncoming(key: Long)
    fun declineIncoming(key: Long)
    /** S3's Listen: opens the book playing from its audiobook. */
    fun listenToLinked(documentId: String)
    /** S4: Not now (Open PDF… launches the PDF picker in the UI and then calls this too). */
    fun dismissBookMissing()
    fun dismissImportError()
    fun openBookSheet(documentId: String)
    fun closeBookSheet()
    fun clearBookAudio(documentId: String)
    fun askRemove(documentId: String)
    fun cancelRemove()
    fun confirmRemove(deleteExports: Boolean)
    fun undoRemove()
    fun snackbarDismissed()
    fun bannerAction()
    fun openSettings()
}
