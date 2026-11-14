package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

/** Prepare (file 14): make a range of the book's audio ahead, while charging, for phones slower than real time. */
data class PrepareRequest(
    val documentId: String,
    val voiceId: String,
    val readSkipped: Boolean,
    val fromSentence: Int,
    val toSentence: Int,
    /** "Ch. 9": what "Ready up to …" names. */
    val upToLabel: String,
    val createdAtEpochMs: Long,
)

sealed interface PrepareState {
    data object WaitingForCharger : PrepareState

    /** [onComputer]: the paired computer makes the range (file 24 §3.13; no charger needed). */
    data class Running(val percent: Int, val onComputer: Boolean = false) : PrepareState

    data class Ready(val upToLabel: String) : PrepareState
}

/** The three choices of file 14 §2. */
enum class PrepareScope { ThisChapter, NextThreeChapters, AsMuchAsFits }

interface PrepareController {
    /** Per document id. */
    val states: StateFlow<Map<String, PrepareState>>

    fun prepare(request: PrepareRequest)

    /** Turns Prepare off for the book: stops and lifts the pin. */
    fun cancel(documentId: String)
}
