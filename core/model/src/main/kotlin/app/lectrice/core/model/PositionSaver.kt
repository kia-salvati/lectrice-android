package app.lectrice.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves the reading place (file 13 §3.2): at most once every 5 s while playing (trailing: the last id in the window is
 * saved), and at once on pause, jumps while paused, book changes and when the app goes to the background.
 */
class PositionSaver(
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private var pending: Pair<String, Int>? = null
    private var saved: Pair<String, Int>? = null
    private var timer: Job? = null

    /** The current sentence changed while playing. */
    @Synchronized
    fun playingAt(documentId: String, sentenceId: Int) {
        pending = documentId to sentenceId
        if (timer?.isActive == true) return
        timer = scope.launch {
            delay(debounceMs)
            flush()
        }
    }

    /** Save now (pause, stop, a jump while paused, another book, background). */
    fun saveNow(documentId: String, sentenceId: Int) {
        synchronized(this) { pending = documentId to sentenceId }
        flush()
    }

    fun flush() {
        val next = synchronized(this) {
            timer?.cancel()
            pending.also { pending = null }
        } ?: return
        if (next == saved) return
        scope.launch {
            try {
                library.savePosition(next.first, next.second)
                synchronized(this@PositionSaver) { saved = next }
            } catch (error: LectriceError) {
                // Logged by the repository; retried at the next event (file 13 §3.2).
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 5_000L
    }
}
