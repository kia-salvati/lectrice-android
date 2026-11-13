package app.lectrice.core.work

import android.content.Context
import android.net.Uri
import app.lectrice.core.data.DeviceProfile
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.ModelInstaller
import app.lectrice.core.data.ModelSpec
import app.lectrice.core.data.ModelStore
import app.lectrice.core.model.DownloadInfo
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.ModelStatus
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.VoiceModelInfo
import app.lectrice.core.model.VoiceModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The three voice models on this phone (files 01, 11 §3.2). Measured real-time factors are remembered per model;
 * before any is measured the spike's numbers for this phone class are used (S10+ class: 4 big cores; S26 class: 8).
 */
class AndroidVoiceModels(
    private val context: Context,
    private val models: ModelStore,
    private val installer: ModelInstaller,
    private val work: WorkCenter,
    private val engine: EngineHost,
    private val settings: SettingsStore,
    private val playback: PlaybackController,
    private val onModelChanged: () -> Unit,
    private val scope: CoroutineScope,
) : VoiceModelRepository {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutableStatuses = MutableStateFlow(compute())
    override val statuses: StateFlow<List<ModelStatus>> = mutableStatuses.asStateFlow()

    override val download: StateFlow<DownloadInfo?> = installer.progress
        .map { progress -> progress?.let { DownloadInfo(it.spec.info.id, it.doneBytes, it.totalBytes, it.bytesPerSecond, it.state, it.wifiOnly) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            installer.progress.collect { refresh() }
        }
        scope.launch {
            playback.state.collect { state ->
                val rtf = state.rtf ?: return@collect
                engine.loadedModel()?.let { prefs.edit().putFloat(RTF_PREFIX + it.info.id, rtf).apply() }
            }
        }
    }

    override fun refresh() {
        mutableStatuses.value = compute()
    }

    private fun compute(): List<ModelStatus> {
        val active = engine.activeModel()
        val progress = installer.progress.value
        return ModelSpec.entries.map { spec ->
            val state = when {
                models.isInstalled(spec) -> ModelInstallState.Installed
                progress != null && progress.spec == spec -> progress.state
                installer.downloadedBytes(spec) > 0 -> ModelInstallState.Downloading(installer.downloadedBytes(spec), spec.info.downloadBytes, paused = true, waitingForNetwork = false, waitingForWifi = false)
                else -> ModelInstallState.NotInstalled
            }
            ModelStatus(spec.info, state, inUse = spec == active)
        }
    }

    override fun startDownload(modelId: String, wifiOnly: Boolean) {
        work.startDownload(spec(modelId), wifiOnly)
        refresh()
    }

    override fun pauseDownload() {
        work.pauseDownload()
        refresh()
    }

    override fun cancelDownload() {
        work.cancelDownload()
        refresh()
    }

    override suspend fun importArchive(uri: String): VoiceModelInfo = withContext(Dispatchers.IO) {
        val spec = installer.importArchive { context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw LectriceError(ErrorName.FileUnreadable) }
        if (settings.settings.value.activeModelId == null || engine.activeModel() == null) settings.update { it.copy(activeModelId = spec.info.id) }
        engine.modelChanged()
        refresh()
        spec.info
    }

    override fun use(modelId: String) {
        settings.update { it.copy(activeModelId = modelId) }
        onModelChanged()
        refresh()
    }

    override suspend fun delete(modelId: String) = withContext(Dispatchers.IO) {
        val spec = spec(modelId)
        if (engine.activeModel() == spec) throw LectriceError(ErrorName.RequestInvalid)
        models.delete(spec)
        refresh()
    }

    override fun isMetered(): Boolean = installer.isMetered()

    override suspend fun freeBytes(): Long = withContext(Dispatchers.IO) { installer.freeBytes() }

    override fun spaceNeeded(modelId: String): Long = installer.spaceNeeded(spec(modelId))

    override fun rtf(modelId: String): Float {
        val measured = prefs.getFloat(RTF_PREFIX + modelId, 0f)
        if (measured > 0f) return measured
        val slowClass = DeviceProfile.defaultThreads() <= SLOW_CLASS_THREADS
        return when (spec(modelId)) {
            ModelSpec.Fast -> if (slowClass) 1.11f else 0.38f
            ModelSpec.Full, ModelSpec.Small -> if (slowClass) 1.76f else 0.58f
        }
    }

    override fun isSlowPhone(): Boolean = rtf((engine.activeModel() ?: ModelSpec.DEFAULT).info.id) > 1f

    private fun spec(modelId: String): ModelSpec = ModelSpec.byId(modelId) ?: throw LectriceError(ErrorName.RequestInvalid)

    private companion object {
        const val FILE = "models"
        const val RTF_PREFIX = "rtf."
        /** Phones with 4 or fewer big cores are the S10+ class in the spike (variants-cores). */
        const val SLOW_CLASS_THREADS = 4
    }
}
