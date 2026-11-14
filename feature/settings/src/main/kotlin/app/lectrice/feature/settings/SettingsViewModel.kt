package app.lectrice.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.ComputerHelperRepository
import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.ReadAheadMinutes
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.TextSize
import app.lectrice.core.model.ThemeSetting
import app.lectrice.core.model.VoiceRepository
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Settings needs from the platform: numbers and system pages it can't get from core:model. */
interface SettingsPlatform {
    /** "Full quality · 370 MB · in use" */
    suspend fun modelSummary(): String

    /** "1.2 GB used · 23.4 GB free" */
    suspend fun storageSummary(): String

    fun versionLine(): String

    fun openBatterySettings()

    fun openSourceCode()

    fun copyToClipboard(text: String)
}

interface SettingsNavigation {
    fun openModelPage()
    fun openStorage()
    fun openLicenses()
    fun openComputerHelper()
}

class SettingsViewModel(
    private val settings: SettingsStore,
    private val voices: VoiceRepository,
    private val samples: VoiceSamplePlayer,
    private val platform: SettingsPlatform,
    private val navigation: SettingsNavigation,
    private val helper: ComputerHelperRepository? = null,
) : ViewModel(), SettingsActions {
    private val state = MutableStateFlow(SettingsUiState(versionLine = platform.versionLine()))
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                state.update { it.copy(settings = s, defaultVoiceSummary = it.voices.firstOrNull { v -> v.id == s.defaultVoiceId }?.summary ?: it.defaultVoiceSummary) }
            }
        }
        viewModelScope.launch { samples.state.collect { sample -> state.update { it.copy(voiceSample = sample) } } }
        helper?.let { repository -> viewModelScope.launch { repository.state.collect { h -> state.update { it.copy(helperSummary = helperSummary(h)) } } } }
        refresh()
    }

    /** Numbers that change elsewhere (a book removed, the cache cleared): recomputed on return. */
    fun refresh() {
        viewModelScope.launch {
            val list = suspendRunCatching { voices.listVoices() }.getOrDefault(emptyList())
            val voiceId = settings.settings.value.defaultVoiceId
            state.update { it.copy(voices = list, defaultVoiceSummary = list.firstOrNull { v -> v.id == voiceId }?.summary ?: "Heart · US · female") }
        }
        viewModelScope.launch { state.update { it.copy(modelSummary = suspendRunCatching { platform.modelSummary() }.getOrDefault("—")) } }
        viewModelScope.launch { state.update { it.copy(storageSummary = suspendRunCatching { platform.storageSummary() }.getOrDefault("—")) } }
    }

    override fun setTheme(theme: ThemeSetting) = settings.update { it.copy(theme = theme) }

    override fun setTextSize(size: TextSize) = settings.update { it.copy(textSize = size) }

    override fun setDefaultVoice(voiceId: String) = settings.update { it.copy(defaultVoiceId = voiceId) }

    override fun playVoiceSample(voiceId: String) = samples.play(voiceId)

    override fun setDefaultSpeed(speed: PlaybackSpeed) = settings.update { it.copy(defaultSpeed = speed) }

    override fun setReadSkipped(readSkipped: Boolean) = settings.update { it.copy(readSkipped = readSkipped) }

    override fun setReadAhead(minutes: ReadAheadMinutes) = settings.update { it.copy(readAhead = minutes) }

    override fun setSentenceOnLockScreen(on: Boolean) = settings.update { it.copy(sentenceOnLockScreen = on) }

    override fun setListenOverLockScreen(on: Boolean) = settings.update { it.copy(listenOverLockScreen = on) }

    override fun openModelPage() = navigation.openModelPage()

    override fun openStorage() = navigation.openStorage()

    override fun openBatterySettings() = platform.openBatterySettings()

    override fun openLicenses() = navigation.openLicenses()

    override fun openSourceCode() = platform.openSourceCode()

    override fun copyVersion() = platform.copyToClipboard(state.value.versionLine)

    override fun openComputerHelper() = navigation.openComputerHelper()
}

/** File 24 §11.1: the Settings row's meta line. */
internal fun helperSummary(state: HelperState): String {
    val name = state.active?.shownName ?: return "Off"
    if (!state.enabled) return "Off"
    return when (state.link) {
        HelperLink.Connected, HelperLink.Busy -> "$name · connected"
        HelperLink.Lost -> "$name · not reachable"
        is HelperLink.NeedsAttention -> "$name · needs attention"
        HelperLink.Searching, HelperLink.Off -> name
    }
}
