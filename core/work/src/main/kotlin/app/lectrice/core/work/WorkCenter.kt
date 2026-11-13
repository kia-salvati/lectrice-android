package app.lectrice.core.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.lectrice.core.data.CoreExportRepository
import app.lectrice.core.data.DownloadProgress
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.Log
import app.lectrice.core.data.ModelInstaller
import app.lectrice.core.data.ModelSpec
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.PauseReason
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app-wide owner of long work (model download, exports): what the screens start, pause and watch, and what keeps
 * [BackgroundWorkService] in the foreground while it runs (Samsung throttles background work 4–8×, spike S7).
 */
class WorkCenter(
    private val context: Context,
    val installer: ModelInstaller,
    val exports: CoreExportRepository,
    val prepare: PrepareCenter,
    private val engine: EngineHost,
    private val playback: PlaybackController,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private var downloadJob: Job? = null

    init {
        prepare.onWork = {
            val runnable = prepare.hasWork()
            Log.i(SCOPE, "prepare requested runnable=$runnable")
            if (runnable) startService()
        }
    }
    private val downloadError = MutableStateFlow<ErrorName?>(null)
    val lastDownloadError: StateFlow<ErrorName?> = downloadError.asStateFlow()

    val download: StateFlow<DownloadProgress?> get() = installer.progress

    /** Starts or resumes a model download (file 01 §3.4). [wifiOnly]: "Wait for Wi-Fi". */
    fun startDownload(spec: ModelSpec, wifiOnly: Boolean) {
        if (downloadJob?.isActive == true) return
        downloadError.value = null
        installer.publish(DownloadProgress(spec, installer.downloadedBytes(spec), spec.info.downloadBytes, null, ModelInstallState.Downloading(installer.downloadedBytes(spec), spec.info.downloadBytes, false, false, false), wifiOnly))
        engine.setDownloading(0)
        downloadJob = scope.launch {
            try {
                installer.install(spec, wifiOnly)
                installer.publish(DownloadProgress(spec, spec.info.downloadBytes, spec.info.downloadBytes, null, ModelInstallState.Installed, wifiOnly))
                if (engine.activeModel() == spec || settings.settings.value.activeModelId == null) settings.update { it.copy(activeModelId = spec.info.id) }
                engine.modelChanged()
            } catch (error: LectriceError) {
                downloadError.value = error.name
                installer.publish(DownloadProgress(spec, installer.downloadedBytes(spec), spec.info.downloadBytes, null, ModelInstallState.Failed(error.name), wifiOnly))
                engine.setDownloading(null)
                Log.w(SCOPE, "download failed", error)
            }
        }
        startService()
    }

    /** Pause keeps the part file; [cancelDownload] deletes it. */
    fun pauseDownload() {
        downloadJob?.cancel()
        val current = installer.progress.value ?: return
        installer.publish(current.copy(state = ModelInstallState.Downloading(current.doneBytes, current.totalBytes, paused = true, waitingForNetwork = false, waitingForWifi = false)))
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        installer.progress.value?.spec?.let(installer::discard)
        installer.publish(null)
        engine.setDownloading(null)
    }

    val isDownloading: Boolean get() = downloadJob?.isActive == true

    /** Exports that still have work the service should run now. */
    fun hasRunnableExport(): Boolean = exports.exports.value.any { it.status == ExportStatus.Queued || it.status == ExportStatus.Running }

    fun hasWork(): Boolean = isDownloading || hasRunnableExport() || prepare.hasWork()

    /** After a start, a resume, or when the app comes to the foreground (the 6-hour budget resets then). */
    fun onExportsChanged() {
        scope.launch {
            runCatching { exports.refresh() }
            if (hasRunnableExport()) startService()
        }
    }

    /** App in front again: continue what the system paused (file 10 §3.7.5). */
    fun onAppForeground() {
        scope.launch {
            runCatching { exports.refresh() }
            if (exports.exports.value.any { it.status == ExportStatus.Paused && it.pausedBy == PauseReason.System }) exports.resumeSystemPaused()
            // Prepare also starts here: the charger may have been plugged in while the app was closed.
            if (hasRunnableExport() || prepare.hasWork()) startService()
        }
    }

    /** Listening is active: playing, or paused for less than a minute (file 10 §3.6). */
    fun listeningActive(): Boolean = playback.state.value.isPlaying

    /** Android 12+ refuses to start it from the background (e.g. the charger plugged in while the app is closed); the work then waits for the next app start. */
    fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundWorkService::class.java))
        } catch (error: IllegalStateException) {
            Log.w(SCOPE, "work service not started from the background", error)
        }
    }

    private companion object {
        const val SCOPE = "WorkCenter"
    }
}
