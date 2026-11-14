package app.lectrice.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.DownloadInfo
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.ModelStatus
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.Sizes
import app.lectrice.core.model.VoiceModelRepository
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.core.model.VoiceSampleState
import app.lectrice.core.model.Voices
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One card of S2 / T2 (file 01 §2, file 11 §3.2). */
data class ModelCard(
    val id: String,
    val name: String,
    val description: String,
    /** "Download 140 MB · 169 MB on the phone" */
    val sizeLine: String,
    /** "Faster than real time on this phone" / "Slower than real time here: prepare chapters while charging" */
    val speedNote: String,
    /** "Recommended" on Fast. */
    val recommended: Boolean,
    val state: ModelInstallState,
    val inUse: Boolean,
    val downloadBytes: Long,
    val installedBytes: Long,
)

/** S3 / S3a–S3d and the T2 card's progress. */
data class DownloadUi(
    val modelId: String,
    val modelName: String,
    /** "142 MB of 370 MB · 38 %" */
    val progressLine: String,
    val percent: Int,
    /** "2.1 MB/s · about 2 min left"; null in the first seconds. */
    val speedLine: String?,
    val paused: Boolean,
    val waitingForNetwork: Boolean,
    val waitingForWifi: Boolean,
    val installing: Boolean,
    val done: Boolean,
    val error: ErrorName?,
    /** S3d numbers, when the error is `StorageFull`. */
    val neededBytes: Long?,
)

sealed interface SetupDialog {
    /** S2b: "You're on mobile data". */
    data class MobileData(val modelId: String, val downloadBytes: Long) : SetupDialog

    /** T2c: confirm deleting a model that isn't in use. */
    data class Delete(val modelId: String, val name: String, val bytes: Long) : SetupDialog

    /** The import picked a file that isn't one of our models (or couldn't be read / no space). */
    data class ImportFailed(val name: ErrorName, val neededBytes: Long?) : SetupDialog
}

data class ModelSetupUiState(
    val cards: List<ModelCard> = emptyList(),
    /** S2: the card the user picked (none at first). */
    val selectedId: String? = null,
    /** "Free space: 23.4 GB" */
    val freeLine: String = "",
    val download: DownloadUi? = null,
    val dialog: SetupDialog? = null,
    val importing: Boolean = false,
    /** S4: the voice is ready; the sample plays "Heart · American English". */
    val ready: Boolean = false,
    /** The S4 line for slow phones (file 14 §2). */
    val slowPhone: Boolean = false,
    /** S4's sample button: spinner while made, pause icon while playing. */
    val voiceSample: VoiceSampleState = VoiceSampleState(),
)

interface ModelSetupActions {
    /** S1 "Not now, just read". */
    fun skip()
    fun select(modelId: String)
    fun download(modelId: String)
    fun confirmMobileData(waitForWifi: Boolean)
    fun pause()
    fun resume()
    fun cancel()
    fun retry()
    fun importPicked(uri: String)
    fun use(modelId: String)
    fun askDelete(modelId: String)
    fun confirmDelete()
    fun dismissDialog()
    fun playSample()
}

interface ModelSetupNavigation {
    fun toLibrary()
    fun toDownload()
    fun pickPdf()
}

/** S1–S4 (file 01) and the model page T2 (file 11 §3.2). */
class ModelSetupViewModel(
    private val models: VoiceModelRepository,
    private val settings: SettingsStore,
    private val samples: VoiceSamplePlayer,
    private val navigation: ModelSetupNavigation,
) : ViewModel(), ModelSetupActions {
    private val state = MutableStateFlow(ModelSetupUiState())
    val uiState: StateFlow<ModelSetupUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(models.statuses, models.download) { statuses, download -> statuses to download }.collect { (statuses, download) ->
                state.update { it.copy(cards = statuses.map(::card), download = download?.let { info -> downloadUi(info, statuses) }, ready = download?.state == ModelInstallState.Installed || it.ready, slowPhone = models.isSlowPhone()) }
            }
        }
        viewModelScope.launch { samples.state.collect { sample -> state.update { it.copy(voiceSample = sample) } } }
        refreshFree()
    }

    private fun refreshFree() {
        viewModelScope.launch { state.update { it.copy(freeLine = "Free space: ${Sizes.format(models.freeBytes())}") } }
    }

    private fun card(status: ModelStatus): ModelCard {
        val info = status.model
        val rtf = models.rtf(info.id)
        val speedNote = if (rtf <= 1f) "Faster than real time on this phone" else "Slower than real time on this phone: prepare chapters while charging"
        return ModelCard(
            id = info.id,
            name = info.name,
            description = info.description,
            sizeLine = "Download ${Sizes.format(info.downloadBytes)} · ${Sizes.format(info.installedBytes)} on the phone",
            speedNote = speedNote,
            recommended = info.recommended,
            state = status.state,
            inUse = status.inUse,
            downloadBytes = info.downloadBytes,
            installedBytes = info.installedBytes,
        )
    }

    private fun downloadUi(info: DownloadInfo, statuses: List<ModelStatus>): DownloadUi {
        val percent = if (info.totalBytes > 0) (info.doneBytes * PERCENT / info.totalBytes).toInt() else 0
        val downloading = info.state as? ModelInstallState.Downloading
        val failed = info.state as? ModelInstallState.Failed
        val speed = info.bytesPerSecond?.takeIf { it > 0 }?.let { perSecond ->
            val seconds = (info.totalBytes - info.doneBytes) / perSecond
            val left = if (seconds < SECONDS_PER_MINUTE) "under a minute left" else "about ${seconds / SECONDS_PER_MINUTE} min left"
            "${"%.1f".format(perSecond / BYTES_PER_MB)} MB/s · $left"
        }
        return DownloadUi(
            modelId = info.modelId,
            modelName = statuses.firstOrNull { it.model.id == info.modelId }?.model?.name.orEmpty(),
            progressLine = "${Sizes.format(info.doneBytes)} of ${Sizes.format(info.totalBytes)} · $percent %",
            percent = percent,
            speedLine = speed,
            paused = downloading?.paused == true,
            waitingForNetwork = downloading?.waitingForNetwork == true,
            waitingForWifi = downloading?.waitingForWifi == true,
            installing = info.state == ModelInstallState.Installing,
            done = info.state == ModelInstallState.Installed,
            error = failed?.name,
            neededBytes = if (failed?.name == ErrorName.StorageFull) models.spaceNeeded(info.modelId) else null,
        )
    }

    override fun skip() {
        settings.update { it.copy(firstRunDone = true) }
        navigation.toLibrary()
    }

    override fun select(modelId: String) = state.update { it.copy(selectedId = modelId) }

    override fun download(modelId: String) {
        viewModelScope.launch {
            val needed = models.spaceNeeded(modelId)
            if (models.freeBytes() < needed) {
                state.update { it.copy(dialog = SetupDialog.ImportFailed(ErrorName.StorageFull, needed)) }
                return@launch
            }
            val card = state.value.cards.firstOrNull { it.id == modelId } ?: return@launch
            if (models.isMetered()) state.update { it.copy(dialog = SetupDialog.MobileData(modelId, card.downloadBytes)) } else start(modelId, wifiOnly = false)
        }
    }

    private fun start(modelId: String, wifiOnly: Boolean) {
        settings.update { it.copy(firstRunDone = true) }
        models.startDownload(modelId, wifiOnly)
        navigation.toDownload()
    }

    override fun confirmMobileData(waitForWifi: Boolean) {
        val dialog = state.value.dialog as? SetupDialog.MobileData ?: return
        state.update { it.copy(dialog = null) }
        start(dialog.modelId, waitForWifi)
    }

    override fun pause() = models.pauseDownload()

    override fun resume() {
        val download = models.download.value ?: return
        models.startDownload(download.modelId, download.wifiOnly)
    }

    override fun cancel() = models.cancelDownload()

    override fun retry() = resume()

    override fun importPicked(uri: String) {
        state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                models.importArchive(uri)
                settings.update { it.copy(firstRunDone = true) }
                state.update { it.copy(importing = false, ready = true) }
                navigation.toDownload()
            } catch (error: LectriceError) {
                state.update { it.copy(importing = false, dialog = SetupDialog.ImportFailed(error.name, error.bytes)) }
            }
        }
    }

    override fun use(modelId: String) = models.use(modelId)

    override fun askDelete(modelId: String) {
        val card = state.value.cards.firstOrNull { it.id == modelId } ?: return
        state.update { it.copy(dialog = SetupDialog.Delete(modelId, card.name, card.installedBytes)) }
    }

    override fun confirmDelete() {
        val dialog = state.value.dialog as? SetupDialog.Delete ?: return
        state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            suspendRunCatching { models.delete(dialog.modelId) }
            refreshFree()
        }
    }

    override fun dismissDialog() = state.update { it.copy(dialog = null) }

    override fun playSample() = samples.play(Voices.DEFAULT_VOICE_ID)

    private companion object {
        const val PERCENT = 100
        const val SECONDS_PER_MINUTE = 60L
        const val BYTES_PER_MB = 1_000_000.0
    }
}
