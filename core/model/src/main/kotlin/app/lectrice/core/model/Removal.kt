package app.lectrice.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A removed book still inside its undo window (file 21 §3.2). */
data class PendingRemoval(val documentId: String, val title: String, val deleteExports: Boolean)

/**
 * "Remove from library" with undo (file 21): soft remove at once, purge when the undo window ends, when another book is
 * removed, or when the app goes to the background. App-scoped, so the Library and the Storage screen share it.
 */
class RemovalController(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val onPurged: (String) -> Unit = {},
) {
    private val pendingState = MutableStateFlow<PendingRemoval?>(null)
    private var timer: Job? = null

    val pending: StateFlow<PendingRemoval?> = pendingState.asStateFlow()

    /** Throws [LectriceError] if the soft remove fails (the row stays). */
    suspend fun remove(documentId: String, title: String, deleteExports: Boolean) {
        purgeNow()
        if (playback.state.value.documentId == documentId) playback.stop()
        library.removeDocument(documentId)
        pendingState.value = PendingRemoval(documentId, title, deleteExports)
        timer = scope.launch {
            delay(UNDO_WINDOW_MS)
            purgeNow()
        }
    }

    suspend fun undo() {
        val removal = pendingState.value ?: return
        timer?.cancel()
        pendingState.value = null
        library.restoreDocument(removal.documentId)
    }

    /** The import of the same PDF restored it inside the window: drop the pending purge. */
    fun restoredByImport(documentId: String) {
        if (pendingState.value?.documentId != documentId) return
        timer?.cancel()
        pendingState.value = null
    }

    fun purgeSoon() {
        scope.launch { purgeNow() }
    }

    suspend fun purgeNow() {
        val removal = pendingState.value ?: return
        timer?.cancel()
        pendingState.value = null
        runCatching { library.purgeRemoved(if (removal.deleteExports) listOf(removal.documentId) else emptyList()) }
        settings.forgetBook(removal.documentId)
        onPurged(removal.documentId)
    }

    companion object {
        /** The snackbar's undo window (file 12's rule for a snackbar with an action). */
        const val UNDO_WINDOW_MS = 8_000L
    }
}
