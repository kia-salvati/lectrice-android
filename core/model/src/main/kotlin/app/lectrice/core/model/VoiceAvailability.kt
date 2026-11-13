package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

/** Whether listening is possible (drives the L5 banner, the pill's "Voice not installed", R9). */
sealed interface VoiceState {
    data object NotInstalled : VoiceState

    data class Downloading(val percent: Int) : VoiceState

    /** Installed; the engine loads on the first play. */
    data object Installed : VoiceState

    data object Failed : VoiceState
}

interface VoiceAvailability {
    val state: StateFlow<VoiceState>

    /** "Try again" after a failed engine load. */
    fun retry()
}
