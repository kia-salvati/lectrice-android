package app.lectrice.core.playback

import app.lectrice.core.model.ErrorName

/** What the player does about a failed sentence. Pure, so every case is tested on the JVM. */
internal object PlaybackErrorPolicy {
    sealed interface Action {
        /** The book's audiobook file can't be read: stop and say so. */
        data object AudiobookMissing : Action

        /** No voice model is installed: stop and offer the download. */
        data object VoiceMissing : Action

        /** The engine can't speak: stop with the voice-failed banner. */
        data object VoiceFailed : Action

        /** The book's playback order changed under the player: rebuild it and go on. */
        data object ReloadOrder : Action

        /** The voice is gone (an older setting): switch to the default voice. */
        data object DefaultVoice : Action

        /** First failure of this sentence: try it once more. */
        data object Retry : Action

        /** It failed again: skip to the next sentence. */
        data object Skip : Action
    }

    /** [retried]: this sentence was already retried once; [failuresInARow] counts skips before this one. */
    fun decide(name: ErrorName, fromFile: Boolean, retried: Boolean, failuresInARow: Int): Action = when {
        fromFile && (name == ErrorName.FileUnreadable || name == ErrorName.AudiobookUnreadable) -> Action.AudiobookMissing
        name == ErrorName.ModelMissing -> Action.VoiceMissing
        name == ErrorName.SpeechEngineUnavailable -> Action.VoiceFailed
        name == ErrorName.SentenceNotFound -> Action.ReloadOrder
        name == ErrorName.VoiceUnknown -> Action.DefaultVoice
        !retried -> Action.Retry
        failuresInARow + 1 >= MAX_FAILURES_IN_A_ROW -> Action.VoiceFailed
        else -> Action.Skip
    }

    const val MAX_FAILURES_IN_A_ROW = 3
}
