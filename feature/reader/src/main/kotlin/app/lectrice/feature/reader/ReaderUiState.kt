package app.lectrice.feature.reader

import app.lectrice.core.model.BlockKind
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
    /** "2 h 41 min · made at 1.5× · from this phone" (or "· imported") */
    val detail: String,
    /** "Text follows each sentence" */
    val follows: String,
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
)

/** The player sheet's "Prepare while charging" row (file 14 §2). */
data class PrepareInfo(
    /** "Off", "Waits for the charger", "Preparing · 40 %", "Ready up to Ch. 9". */
    val summary: String,
    val active: Boolean,
    /** "Prepared with Heart. Switch back to use it." when the voice changed. */
    val voiceWarning: String?,
)

sealed interface ReaderMessage {
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

/** What the reader, its pill, sheets and the Listen view can ask for. */
interface ReaderActions {
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
    fun openExport()
    /** File 14: This chapter · Next 3 chapters · As much as fits. */
    fun prepare(scope: app.lectrice.core.model.PrepareScope)
    fun cancelPrepare()
    /** File 23 B2: "Made on this phone" / "Audiobook file". */
    fun setAudioSource(source: app.lectrice.core.model.AudioSource)
    /** B2 "Use another file…" / B4 "Find it again…": a picked `content://` URI. */
    fun linkAudiobook(uri: String)
    /** B2 "Remove link" (the file itself stays). */
    fun removeAudiobookLink()
}
