package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

/** The six speeds (README §4.7), stored as percent. */
enum class PlaybackSpeed(val percent: Int) {
    X0_75(75), X1(100), X1_25(125), X1_5(150), X1_75(175), X2(200);

    val rate: Float get() = percent / 100f

    /** "0.75×", "1×", "1.5×" */
    val label: String get() = (if (percent % 100 == 0) "${percent / 100}" else "${percent / 100.0}".trimEnd('0')) + "×"

    companion object {
        val DEFAULT = X1

        fun fromPercent(percent: Int): PlaybackSpeed = entries.firstOrNull { it.percent == percent } ?: DEFAULT
    }
}

/** File 05 §3.8. */
enum class PlaybackStatus { Idle, VoiceMissing, Paused, Preparing, Playing, CatchingUp, Finished, VoiceFailed }

/** The sleep timer (file 15). */
sealed interface SleepTimer {
    data object Off : SleepTimer

    /** Stops after [remainingMs] of playing (pausing doesn't count down). */
    data class Minutes(val totalMinutes: Int, val remainingMs: Long) : SleepTimer

    /** Stops before the next chapter's first sentence. */
    data object EndOfChapter : SleepTimer
}

data class PlaybackState(
    val documentId: String? = null,
    val currentSentenceId: Int? = null,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val speed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    val voiceId: String = Voices.DEFAULT_VOICE_ID,
    val readSkipped: Boolean = false,
    val sleep: SleepTimer = SleepTimer.Off,
    /** Estimated 1× audio already made after the current sentence (core C25). */
    val readyAheadMs: Long = 0,
    val error: ErrorName? = null,
    /** Sustained real-time factor of the loaded engine (median of the last 50 sentences); null until measured. */
    val rtf: Float? = null,
    /** Counts sentences skipped after failing twice (file 09 §3.5); the reader shows a snackbar when it grows. */
    val skippedCount: Int = 0,
    /** File 23: where this book's audio comes from. */
    val source: AudioSource = AudioSource.Generated,
    /** The book is set to its audiobook but the file can't be read. */
    val audiobookMissing: Boolean = false,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.Playing || status == PlaybackStatus.Preparing || status == PlaybackStatus.CatchingUp
}

/** What the UI may ask of playback. The Media3 adapter (M3) and the fake (M2) implement it. */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** Makes [document] the current book at [sentenceId] (paused unless [play]). */
    fun open(document: Document, sentenceId: Int?, play: Boolean)

    fun play()

    fun pause()

    fun togglePlayPause() = if (state.value.isPlaying) pause() else play()

    fun next()

    fun previous()

    /** Moves to [sentenceId] (resolved to a playable one); play/pause is kept. */
    fun jumpTo(sentenceId: Int)

    fun setSpeed(speed: PlaybackSpeed)

    fun setVoice(voiceId: String)

    fun setReadSkipped(readSkipped: Boolean)

    fun setSleepTimer(timer: SleepTimer)

    /** File 23: the current book plays from its audiobook file or from speech made on the phone. */
    fun setAudioSource(source: AudioSource)

    /** Stops and forgets the current book (remove from library). */
    fun stop()
}
