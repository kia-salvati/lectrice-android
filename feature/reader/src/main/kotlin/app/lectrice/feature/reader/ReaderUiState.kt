package app.lectrice.feature.reader

import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookMode
import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.PrepareScope
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.VoiceSampleState
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.core.model.SleepTimer
import app.lectrice.core.model.StatusPart
import app.lectrice.core.model.TextSize
import app.lectrice.core.model.Voice

/** A sentence as the reader draws it. */
data class ReaderSentence(val id: Int, val text: String)

/** One reader item (one `LazyColumn` item per block, file 04 §3.1). Built once per open, off the main thread. */
data class ReaderBlock(
    val index: Int,
    val id: Int,
    val kind: BlockKind,
    /** Heading level 1–3 (deeper is drawn as 3). */
    val level: Int,
    val sentences: List<ReaderSentence>,
    /** Code and table text as printed (keeps columns and line breaks). */
    val boxText: String?,
    /** "Code example." / "Table." when the block is read as its cue (read code off). */
    val cue: ReaderSentence?,
    /** Page chips: before the block (code/table), or before a sentence (sentence id → chip). */
    val chipBeforeBlock: PageChip?,
    val chipsBeforeSentence: Map<Int, PageChip>,
)

/** "p. 265"; TalkBack also says "PDF page 279" when [pdfPage] differs from the label. */
data class PageChip(val label: String, val pdfPage: Int)

/** One outline row (file 07 §3.4). */
data class OutlineRow(
    val index: Int,
    val title: String,
    val level: Int,
    val pageLabel: String,
    val sentenceId: Int,
    /** On the current path: accent bar + weight 600. */
    val onPath: Boolean,
    /** Search match range in [title] (bold), when searching. */
    val matchStart: Int? = null,
    val matchEnd: Int? = null,
)

/** What the pill and the player show about the current place. */
data class PlayerInfo(
    val status: PlaybackStatus,
    val error: ErrorName?,
    val isPlaying: Boolean,
    /** Pill status parts (file 05 §3.2), without the speed and sleep parts (the pill adds them by width). */
    val location: List<StatusPart>,
    /** Pill TalkBack label: "Chapter 7, page 265. Open the Listen view". */
    val locationSpoken: String,
    val speed: PlaybackSpeed,
    val sleep: SleepTimer,
    /** "28 min" while a minutes timer runs; "End of chapter"; null when off. */
    val sleepShort: String?,
    /** "Stops at 23:41" / "Off" / "End of chapter". */
    val sleepSummary: String,
    val bookPercent: Int,
    /** "4 h 12 min left" */
    val bookTimeLeft: String,
    /** Book slider: thumb fraction 0–1 and the buffered fraction ahead of it (core C25). */
    val bookFraction: Float,
    val bufferedFraction: Float,
    /** Slider ticks at chapter starts, as fractions (≤ 60 of them, else empty). */
    val chapterTicks: List<Float>,
    /** "Ch. 7 · 49 %" under the slider. */
    val sliderCaption: String,
    /** Player sheet chapter line. */
    val chapterLineTitle: String,
    val chapterLineDetail: String,
    val hasToc: Boolean,
    val canPreviousSection: Boolean,
    val canNextSection: Boolean,
    val voice: Voice?,
    val readSkipped: Boolean,
    /** File 23: the book's linked audiobook (the sheet's "Audio" row and the B2 sheet); null when it has none. */
    val audiobook: AudiobookUi? = null,
)

/**
 * File 23 §4, option B of `design/2026-11-13-audiobook-source-options.html`. [active]: the book plays from the file
 * (pill mark, Voice row and "Read code & tables aloud" disabled with "From the audiobook").
 */
data class AudiobookUi(
    val active: Boolean,
    /** "The Long Book – Heart.m4b" */
    val fileName: String,
    /** The detail line is "{duration} · {voiceName} · made at {madeAt} · from this phone|imported" (absent parts left out). */
    val duration: String,
    val voiceName: String?,
    /** The speed the file was made at, when not 1×. */
    val madeAt: PlaybackSpeed?,
    val kind: AudiobookKind,
    /** How the text follows it: each sentence / chapters (another version of the book) / not at all. */
    val mode: AudiobookMode,
    /** B4: the file can't be read; the Audiobook card shows "File missing" + "Find it again…". */
    val missing: Boolean,
)

/** The Listen view's text and labels (file 06). */
data class ListenInfo(
    val bookTitle: String,
    /** Top bar line 2: the section title, else the chapter title, else "p. 12". */
    val title: String,
    val previous: String?,
    val current: String,
    val next: String?,
    /** Current item is a skipped code/table block: show the R13c card. */
    val currentBlockKind: BlockKind,
    val currentBoxText: String?,
    /** "38 % of chapter 7" */
    val progress: String,
    /** "9 min left" */
    val timeLeft: String,
    /** Chapter progress 0–1 and its section ticks as fractions. */
    val chapterFraction: Float,
    val sectionTicks: List<Float>,
)

sealed interface ReaderBanner {
    /** R9: the voice couldn't start. */
    data object VoiceFailed : ReaderBanner

    /**
     * R7 (file 12 §3.3): slower than the listening speed. [best] = the highest speed that plays smoothly (null: none,
     * no "Set" button); the text says "1× or {best}" when best > 1×, "0.75×" alone, or nothing.
     */
    data class SlowPhone(val speed: PlaybackSpeed, val best: PlaybackSpeed?, val canPrepare: Boolean) : ReaderBanner
}

sealed interface ReaderContent {
    data object Loading : ReaderContent

    /** "Updating this book's text…" (a re-clean after an app update). */
    data object Recleaning : ReaderContent

    data class Failed(val name: ErrorName) : ReaderContent

    data class Ready(
        val title: String,
        val blocks: List<ReaderBlock>,
        val outline: List<OutlineRow>,
    ) : ReaderContent
}

data class ReaderUiState(
    val content: ReaderContent = ReaderContent.Loading,
    val textSize: TextSize = TextSize.DEFAULT,
    val currentSentenceId: Int? = null,
    /** Index of the block holding [currentSentenceId]. */
    val currentBlockIndex: Int? = null,
    /** Bumped on every jump the reader should follow (tap, chapter, section, slider, back to current): turns follow-scroll on. */
    val followRequest: Int = 0,
    val player: PlayerInfo? = null,
    val listen: ListenInfo? = null,
    val voices: List<Voice> = emptyList(),
    val banner: ReaderBanner? = null,
    val voiceMissing: Boolean = false,
    val pillHintSeen: Boolean = true,
    val listenHintSeen: Boolean = true,
    /** Prepare (file 14): shown on slow phones only; null elsewhere. */
    val prepare: PrepareInfo? = null,
    /** Snackbar once: "Already in your library. Continuing where you left off." etc. */
    val message: ReaderMessage? = null,
    val voiceSample: VoiceSampleState = VoiceSampleState(),
    /** File 24 §11.2: the computer helper's line in the player sheet and the pill's mark; null when no computer helps. */
    val helper: HelperLineUi? = null,
)

/** The player sheet's computer-helper row and the pill's `computer` mark (file 24 §11.2, design option A). */
data class HelperLineUi(
    /** "Making audio on Kia's laptop · 12 min ready", "Kia's laptop is busy · 22 min ready", "Looking for Kia's laptop…",
     *  "Computer not reachable · 24 min ready", or a Needs-attention text. */
    val text: String,
    /** The row shows this text in `warnFg` with a "Fix" button instead of the chevron. */
    val needsAttention: Boolean,
    /** The pill shows the 16 dp `computer` mark (Connected or Busy only). */
    val pillMark: Boolean,
    /** TalkBack addition for the pill: ", audio from Kia's laptop". */
    val pillSpoken: String?,
)

/** The player sheet's "Prepare while charging" row. */
data class PrepareInfo(
    val summary: PrepareSummary,
    val active: Boolean,
    /** The computer's name while it is connected; the row then reads "Prepare on <name>" with the `bolt` icon (no
     *  charger needed). Null: the phone prepares while charging (battery icon). */
    val onComputer: String? = null,
)

/** The Prepare row's subtitle. */
sealed interface PrepareSummary {
    /** "Off" */
    data object Off : PrepareSummary

    /** "Waits for the charger" */
    data object WaitsForCharger : PrepareSummary

    /** "Preparing · 40 %", or "Preparing on {computer} · 40 %" while the computer makes it. */
    data class Preparing(val percent: Int, val computer: String?) : PrepareSummary

    /** "Ready up to {upTo}" ("Ch. 9"). */
    data class Ready(val upTo: String) : PrepareSummary
}

sealed interface ReaderMessage {
    /** File 24 §2, once per session: "Your computer isn't reachable. The phone carries on by itself." */
    data object ComputerLost : ReaderMessage

    /** "Lectrice will prepare this chapter while the phone charges." */
    data object PrepareQueued : ReaderMessage

    data object BookGone : ReaderMessage

    data class SentenceSkipped(val name: ErrorName) : ReaderMessage

    /**
     * File 23 §3.7 snackbar: `AudiobookUnreadable` "This file isn't an audiobook Lectrice can play.",
     * `AudiobookOtherBook` "This audiobook belongs to another book.", `AudiobookNotLectrice` "This audiobook can't follow
     * the text, so it can't be played here yet.", else "The audiobook couldn't be opened."
     */
    data class AudiobookFailed(val name: ErrorName) : ReaderMessage
}

/** The Prepare row's choices. */
interface PrepareActions {
    /** This chapter · Next 3 chapters · As much as fits. */
    fun prepare(scope: PrepareScope)
    fun cancelPrepare()
}

/** The "Audio" row and the "Listen with" sheet. */
interface AudiobookSourceActions {
    /** "Made on this phone" / "Audiobook file". */
    fun setAudioSource(source: AudioSource)
    /** "Use another file…" / "Find it again…": a picked `content://` URI. */
    fun linkAudiobook(uri: String)
    /** "Remove link" (the file itself stays). */
    fun removeAudiobookLink()
}

/** What the reader, its pill, sheets and the Listen view can ask for. */
interface ReaderActions : PrepareActions, AudiobookSourceActions {
    fun playPause()
    fun next()
    fun previous()
    fun nextSection()
    fun previousSection()
    fun jumpToSentence(sentenceId: Int)
    /** A tap on a code/table/caption block or a heading block as a whole (file 04 §3.5). */
    fun tapBlock(blockIndex: Int)
    /** Book slider release at [fraction] 0–1. */
    fun seekTo(fraction: Float)
    /** The bubble text while dragging the slider: "p. 265 · Ch. 7". */
    fun seekLabel(fraction: Float): String
    fun setSpeed(speed: PlaybackSpeed)
    fun setVoice(voiceId: String)
    fun playVoiceSample(voiceId: String)
    fun setReadSkipped(readSkipped: Boolean)
    fun setSleep(timer: SleepTimer)
    fun searchOutline(query: String): List<OutlineRow>
    fun pillHintDone()
    fun listenHintDone()
    fun bannerAction()
    fun dismissBanner()
    fun messageShown()
    fun openLibrary()
    fun openModelSetup()
    /** The helper row (or its "Fix") opens Settings → Computer helper. */
    fun openComputerHelper()
}
