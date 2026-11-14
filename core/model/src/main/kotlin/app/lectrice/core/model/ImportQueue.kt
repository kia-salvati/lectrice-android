package app.lectrice.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One file in the import queue (file 03 §3.2). */
data class ImportJob(val key: Long, val displayName: String, val running: Boolean)

sealed interface ImportOutcome {
    val displayName: String

    data class Imported(override val displayName: String, val result: ImportResult) : ImportOutcome

    data class Failed(override val displayName: String, val error: LectriceError) : ImportOutcome
}

/**
 * Imports run one at a time, first in first out (file 03 §3.2). App-scoped: the picker and "Open with" both feed it,
 * and an import keeps going while the user moves around the app.
 */
class ImportQueue(private val library: LibraryRepository, private val scope: CoroutineScope) {
    private val pending = Channel<Pair<Long, PdfSource>>(Channel.UNLIMITED)
    private val jobsState = MutableStateFlow<List<ImportJob>>(emptyList())
    private val outcomes = MutableSharedFlow<ImportOutcome>(extraBufferCapacity = OUTCOME_BUFFER)
    private var nextKey = 0L

    val jobs: StateFlow<List<ImportJob>> = jobsState.asStateFlow()

    /** Imported books and failures, each delivered once. */
    val results: SharedFlow<ImportOutcome> = outcomes.asSharedFlow()

    init {
        scope.launch {
            for ((key, source) in pending) run(key, source)
        }
    }

    /** False when [MAX_JOBS] imports are running or waiting already: the file is not taken. */
    fun enqueue(source: PdfSource): Boolean {
        val key = synchronized(this) {
            if (jobsState.value.size >= MAX_JOBS) return false
            val key = nextKey++
            jobsState.update { it + ImportJob(key, displayName = "", running = false) }
            key
        }
        scope.launch {
            val name = suspendRunCatching { library.describe(source).displayName }.getOrDefault("")
            jobsState.update { jobs -> jobs.map { if (it.key == key && it.displayName.isEmpty()) it.copy(displayName = name) else it } }
            pending.send(key to source)
        }
        return true
    }

    private suspend fun run(key: Long, source: PdfSource) {
        jobsState.update { jobs -> jobs.map { if (it.key == key) it.copy(running = true) else it } }
        val outcome = try {
            val info = library.describe(source)
            ImportOutcome.Imported(info.displayName, library.importPdf(source, info))
        } catch (error: LectriceError) {
            ImportOutcome.Failed(jobsState.value.firstOrNull { it.key == key }?.displayName.orEmpty(), error)
        }
        jobsState.update { jobs -> jobs.filterNot { it.key == key } }
        outcomes.emit(outcome)
    }

    companion object {
        /** Each waiting import can hold a 200 MB file open for copying; nobody picks more than this at once. */
        const val MAX_JOBS = 20
        private const val OUTCOME_BUFFER = 8
    }
}
