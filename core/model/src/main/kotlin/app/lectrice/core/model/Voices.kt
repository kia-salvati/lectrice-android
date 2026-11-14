package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

enum class VoiceLanguage { EnUs, EnGb }

enum class VoiceGender { Female, Male }

data class Voice(val id: String, val name: String, val language: VoiceLanguage, val gender: VoiceGender) {
    /** "Heart · US · female" */
    val summary: String
        get() = "$name · ${if (language == VoiceLanguage.EnUs) "US" else "UK"} · ${gender.name.lowercase()}"

    /** "American English · female" */
    val longSummary: String
        get() = "${if (language == VoiceLanguage.EnUs) "American" else "British"} English · ${gender.name.lowercase()}"
}

object Voices {
    const val DEFAULT_VOICE_ID = "af_heart"
}

/** Plays the fixed voice sample (file 16); the book's playback pauses meanwhile and resumes after. */
interface VoiceSamplePlayer {
    /** Which sample is being made or playing, for the row's spinner and equaliser (file 16). */
    val state: StateFlow<VoiceSampleState>

    fun play(voiceId: String)
}

/** One voice row's sample: [Phase.Loading] while the clip is made (a spinner), [Phase.Playing] while it plays (an equaliser). */
data class VoiceSampleState(val voiceId: String? = null, val phase: Phase = Phase.Idle) {
    enum class Phase { Idle, Loading, Playing }

    fun phaseOf(voiceId: String): Phase = if (voiceId == this.voiceId) phase else Phase.Idle
}
