package app.lectrice.feature.reader

import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.ComputerHelperRepository
import app.lectrice.core.model.Document
import app.lectrice.core.model.PlaybackOrder
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.PrepareController
import app.lectrice.core.model.PrepareRequest
import app.lectrice.core.model.PrepareScope
import app.lectrice.core.model.PrepareState
import app.lectrice.core.model.Toc
import app.lectrice.core.model.VoiceModelRepository
import kotlinx.coroutines.flow.StateFlow

/** Prepare for one book (while charging, or on the computer helper): the player sheet's row and what a choice asks for. */
class PreparePresenter(
    private val documentId: String,
    private val prepares: PrepareController,
    private val models: VoiceModelRepository,
    private val helper: ComputerHelperRepository,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    val states: StateFlow<Map<String, PrepareState>> get() = prepares.states

    /** Null hides the row: a phone fast enough without a computer, or a book playing from its audiobook file. */
    fun info(source: AudioSource): PrepareInfo? {
        val computer = helper.state.value.takeIf { it.link.makesAudio }?.active?.shownName
        if (!models.isSlowPhone() && computer == null) return null
        if (source == AudioSource.Audiobook) return null
        val summary = when (val prepared = prepares.states.value[documentId]) {
            null -> PrepareSummary.Off
            PrepareState.WaitingForCharger -> PrepareSummary.WaitsForCharger
            is PrepareState.Running -> PrepareSummary.Preparing(prepared.percent, computer?.takeIf { prepared.onComputer })
            is PrepareState.Ready -> PrepareSummary.Ready(prepared.upToLabel)
        }
        return PrepareInfo(summary, active = summary != PrepareSummary.Off, onComputer = computer)
    }

    /** Queues [scope] from the current sentence; false when there is no current sentence. */
    fun prepare(document: Document, order: PlaybackOrder, playing: PlaybackState, scope: PrepareScope): Boolean {
        val current = playing.currentSentenceId ?: return false
        val nextChapters = Toc.chapterNodeIndexes(document.toc).map { document.toc[it] }.filter { it.sentenceId > current }
        val (end, label) = when (scope) {
            PrepareScope.ThisChapter -> endBefore(document, nextChapters.firstOrNull()?.sentenceId)
            PrepareScope.NextThreeChapters -> endBefore(document, nextChapters.getOrNull(NEXT_CHAPTERS)?.sentenceId)
            PrepareScope.AsMuchAsFits -> {
                var last = current
                var cursor: Int? = current
                while (cursor != null && order.secondsBetween(current, cursor, 1f) < MAX_PREPARE_SECONDS) {
                    last = cursor
                    cursor = order.stepFrom(cursor, 1)
                }
                last to chapterLabelBefore(document, last + 1)
            }
        }
        prepares.prepare(PrepareRequest(documentId, playing.voiceId, playing.readSkipped, current, end, label, clockMs()))
        return true
    }

    fun cancel() = prepares.cancel(documentId)

    private fun endBefore(document: Document, nextStart: Int?): Pair<Int, String> =
        (nextStart?.minus(1) ?: (document.sentenceIdEnd - 1)) to chapterLabelBefore(document, nextStart)

    /** "Ch. 9" (the chapter that ends right before [nextStart]), else "the end". */
    private fun chapterLabelBefore(document: Document, nextStart: Int?): String {
        val last = (nextStart ?: document.sentenceIdEnd) - 1
        val location = Toc.location(document.toc, last) ?: return "the end"
        val label = Toc.chapterLabel(document.toc[location.chapterIndex].title)
        return label.number?.let { "Ch. $it" } ?: label.name
    }

    private companion object {
        const val NEXT_CHAPTERS = 3
        /** The audio cache cap less 100 MB, in seconds of 16-bit 24 kHz WAV. */
        const val MAX_PREPARE_SECONDS = (1024 - 100) * 1_048_576.0 / 48_000
    }
}
