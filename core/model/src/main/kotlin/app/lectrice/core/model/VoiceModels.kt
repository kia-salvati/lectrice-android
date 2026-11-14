package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

/** One downloadable voice model (file 01; user 2026-10-25: Fast, Full quality, Small). */
data class VoiceModelInfo(
    val id: String,
    /** The core's engine id: cached clips and exports are keyed by it, so models never mix. */
    val engineId: String,
    val name: String,
    val description: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    /** The one the setup screens suggest. */
    val recommended: Boolean = false,
)

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState

    data class Downloading(val doneBytes: Long, val totalBytes: Long, val paused: Boolean, val waitingForNetwork: Boolean, val waitingForWifi: Boolean) : ModelInstallState

    /** Checking the archive's SHA-256, then unpacking. */
    data object Installing : ModelInstallState

    data object Installed : ModelInstallState

    data class Failed(val name: ErrorName) : ModelInstallState
}

data class ModelStatus(val model: VoiceModelInfo, val state: ModelInstallState, val inUse: Boolean)

/** S3's numbers (file 01 §3.3). */
data class DownloadInfo(
    val modelId: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long?,
    val state: ModelInstallState,
    val wifiOnly: Boolean,
)

/** The voice models: what's installed and in use, downloads, switching and deleting (files 01, 11 §3.2). */
interface VoiceModelRepository {
    val statuses: StateFlow<List<ModelStatus>>
    val download: StateFlow<DownloadInfo?>

    fun refresh()

    fun startDownload(modelId: String, wifiOnly: Boolean)

    fun pauseDownload()

    fun cancelDownload()

    /** "Import from a file…": returns the model it was. */
    suspend fun importArchive(uri: String): VoiceModelInfo

    /** "Use this": the next play loads it (playback reloads at the same sentence if it is playing). */
    fun use(modelId: String)

    suspend fun delete(modelId: String)

    fun isMetered(): Boolean

    suspend fun freeBytes(): Long

    /** Free space a download of the model needs now (file 01 §3.2). */
    fun spaceNeeded(modelId: String): Long

    /** Real-time factor to plan with: measured on this phone if known, else the spike's number for this phone class. */
    fun rtf(modelId: String): Float

    /** Whether this phone makes speech slower than real time with the default model (S10+ class, file 14). */
    fun isSlowPhone(): Boolean
}
