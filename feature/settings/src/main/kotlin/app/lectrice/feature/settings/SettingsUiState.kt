package app.lectrice.feature.settings

import app.lectrice.core.model.AppSettings
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.ReadAheadMinutes
import app.lectrice.core.model.TextSize
import app.lectrice.core.model.ThemeSetting
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceSampleState

/** T1 (file 11 §3.1). */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val voices: List<Voice> = emptyList(),
    /** "Heart · US · female" */
    val defaultVoiceSummary: String = "",
    /** "Full quality · 370 MB · in use", or "Not installed". */
    val modelSummary: String = "",
    /** "1.2 GB used · 23.4 GB free"; null while loading ("…"). */
    val storageSummary: String? = null,
    /** "Version 0.1.0 · core 0.3.0" */
    val versionLine: String = "",
    val voiceSample: VoiceSampleState = VoiceSampleState(),
    /** File 24 §11.1: "Off" / "Kia's laptop · connected" / "Kia's laptop · not reachable". */
    val helperSummary: String = "Off",
)

interface SettingsActions {
    fun setTheme(theme: ThemeSetting)
    fun setTextSize(size: TextSize)
    fun setDefaultVoice(voiceId: String)
    fun playVoiceSample(voiceId: String)
    fun setDefaultSpeed(speed: PlaybackSpeed)
    fun setReadSkipped(readSkipped: Boolean)
    fun setReadAhead(minutes: ReadAheadMinutes)
    fun setSentenceOnLockScreen(on: Boolean)
    fun setListenOverLockScreen(on: Boolean)
    fun openModelPage()
    fun openStorage()
    fun openBatterySettings()
    fun openLicenses()
    fun openSourceCode()
    fun copyVersion()
    fun openComputerHelper()
}
