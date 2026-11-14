package app.lectrice.core.data

import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.VoiceAvailability
import app.lectrice.core.model.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.lectrice_ffi.EngineStatus

/**
 * Owns the speech engine's lifetime (spec §5.4): loaded lazily on the first play, prepare or export, kept while anything
 * needs it, unloaded after 5 idle minutes or when Android asks for memory. One engine at a time.
 */
class EngineHost(
    private val core: RustCore,
    private val models: ModelStore,
    private val factory: EngineFactory,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : VoiceAvailability {
    private val mutex = Mutex()
    private val voiceState = MutableStateFlow(initialState())
    private var loadedModelId: String? = null
    private var loadedThreads: Int? = null
    private var idleTimer: Job? = null
    private var users = 0
    private var loads = 0L
    private val assembledLoad = MutableStateFlow<Long?>(null)

    override val state: StateFlow<VoiceState> = voiceState.asStateFlow()

    /**
     * A new number each time the core's speech services are built around a fresh engine, `null` while there is none.
     * Settings the core keeps per engine (the read-ahead minutes) are applied again on each new number.
     */
    val assembled: StateFlow<Long?> = assembledLoad.asStateFlow()

    /** Downloads report here (file 01); the model page and the library banner read it. */
    fun setDownloading(percent: Int?) {
        voiceState.value = when {
            percent != null && models.installed().isEmpty() -> VoiceState.Downloading(percent)
            else -> initialState()
        }
    }

    /** The model the user listens with: the setting if installed, else the first installed one. */
    fun activeModel(): ModelSpec? {
        val chosen = ModelSpec.byId(settings.settings.value.activeModelId)
        return chosen?.takeIf(models::isInstalled) ?: models.installed().firstOrNull()
    }

    fun threads(): Int = settings.settings.value.threadsOverride ?: DeviceProfile.defaultThreads()

    /** Exports run on at most 4 threads (user 2026-11-13: cooler); playback reloads with [threads] when it starts. */
    fun exportThreads(): Int = minOf(threads(), EXPORT_THREADS)

    /**
     * Makes sure the active model is loaded; returns its engine id, or null (missing or failed: [state] says which).
     * Blocking callers (ExoPlayer's loader thread) use [ensureLoadedBlocking].
     */
    suspend fun ensureLoaded(): String? = withContext(Dispatchers.IO) { mutex.withLock { loadLocked(activeModel()) } }

    /**
     * The engine for an export while nobody listens (file 10 §3.6): [spec] (the export's model) or the active one, on
     * [exportThreads]. Never two engines at once.
     */
    suspend fun ensureLoadedForExport(spec: ModelSpec? = null): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val model = spec ?: activeModel()
            if (model != null && models.isInstalled(model)) loadLocked(model, exportThreads()) else null
        }
    }

    /** The model whose engine is loaded now, if any. */
    fun loadedModel(): ModelSpec? = ModelSpec.byId(loadedModelId)

    fun ensureLoadedBlocking(): String? = kotlinx.coroutines.runBlocking { ensureLoaded() }

    private fun loadLocked(model: ModelSpec?, threads: Int = threads()): String? {
        val spec = model ?: return null.also { voiceState.value = VoiceState.NotInstalled }
        if (loadedModelId == spec.info.id && loadedThreads == threads) return spec.info.engineId
        val started = System.nanoTime()
        val status = core.blocking("reload_engine") { it.reloadEngine(factory.config(spec, threads)) }
        return when (status) {
            is EngineStatus.Ready -> {
                loadedModelId = spec.info.id
                loadedThreads = threads
                voiceState.value = VoiceState.Installed
                assembledLoad.value = ++loads
                Log.i(SCOPE, "engine loaded id=${status.engineId} threads=$threads ms=${(System.nanoTime() - started) / NANOS_PER_MS}")
                status.engineId
            }
            is EngineStatus.Failed -> {
                loadedModelId = null
                assembledLoad.value = null
                voiceState.value = VoiceState.Failed
                Log.w(SCOPE, "engine failed ${status.error.name}")
                null
            }
            EngineStatus.Missing, EngineStatus.Unloaded -> {
                loadedModelId = null
                assembledLoad.value = null
                voiceState.value = VoiceState.NotInstalled
                null
            }
        }
    }

    /** Something needs the engine (playback, an export, a prepare job); the idle timer stops while any user holds it. */
    fun acquire() {
        synchronized(this) {
            users++
            idleTimer?.cancel()
        }
    }

    fun release() {
        synchronized(this) {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) {
                idleTimer?.cancel()
                idleTimer = scope.launch {
                    delay(IDLE_UNLOAD_MS)
                    unload("idle")
                }
            }
        }
    }

    /** `onTrimMemory` (background or worse) with nothing playing, or 5 idle minutes. */
    suspend fun unload(reason: String) = withContext(Dispatchers.IO) { unloadLocked(reason) }

    private suspend fun unloadLocked(reason: String) = mutex.withLock {
        if (loadedModelId == null || users > 0) return@withLock
        core.blocking("unload_engine") { it.unloadEngine() }
        loadedModelId = null
        assembledLoad.value = null
        Log.i(SCOPE, "engine unloaded ($reason)")
    }

    /** The user picked another model, or a download finished: the next [ensureLoaded] loads it. */
    suspend fun modelChanged() = mutex.withLock {
        loadedModelId = null
        voiceState.value = initialState()
    }

    override fun retry() {
        scope.launch {
            mutex.withLock { loadedModelId = null }
            ensureLoaded()
        }
    }

    private fun initialState(): VoiceState = if (models.installed().isEmpty()) VoiceState.NotInstalled else VoiceState.Installed

    private companion object {
        const val SCOPE = "Engine"
        const val EXPORT_THREADS = 4
        const val IDLE_UNLOAD_MS = 5 * 60_000L
        const val NANOS_PER_MS = 1_000_000
    }
}
