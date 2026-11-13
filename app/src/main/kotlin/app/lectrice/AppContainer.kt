package app.lectrice

import android.content.Context
import app.lectrice.core.data.AndroidAudiobookRepository
import app.lectrice.core.data.CoreExportRepository
import app.lectrice.core.data.CoreLibraryRepository
import app.lectrice.core.data.CoreVoiceRepository
import app.lectrice.core.data.EngineFactory
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.ModelInstaller
import app.lectrice.core.data.ModelStore
import app.lectrice.core.data.PreferencesSettingsStore
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.ImportQueue
import app.lectrice.core.model.IncomingFiles
import app.lectrice.core.playback.CoreVoiceSamplePlayer
import app.lectrice.core.playback.DeviceConditions
import app.lectrice.core.playback.Media3PlaybackController
import app.lectrice.core.playback.PlaybackService
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.PositionSaver
import app.lectrice.core.model.RemovalController
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.VoiceRepository
import app.lectrice.core.model.SystemPauseCause
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.core.model.VoiceModelRepository
import app.lectrice.core.work.AacEncoderSink
import app.lectrice.core.work.AndroidVoiceModels
import app.lectrice.core.work.PrepareCenter
import app.lectrice.core.work.WorkCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Builds everything once per process, by hand (no DI framework, spec §4.1). Only this class knows concrete types. */
@androidx.media3.common.util.UnstableApi
class AppContainer(context: Context, debug: Boolean) {
    private val appContext = context.applicationContext

    /** Lives as long as the process: imports, purges and position saves outlive any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val core = RustCore(appContext, debug)
    val settings: SettingsStore = PreferencesSettingsStore(appContext)
    val library: LibraryRepository = CoreLibraryRepository(appContext, core)
    val voices: VoiceRepository = CoreVoiceRepository(core)
    val models = ModelStore(appContext)
    val engine = EngineHost(core, models, EngineFactory(models), settings, scope)
    val positions = PositionSaver(library, scope)
    val audiobooks: AudiobookRepository = AndroidAudiobookRepository(appContext, core)

    /** Files from "Open with" / "Share", waiting for the user's yes on the library screen. */
    val incoming = IncomingFiles()
    val media3 = Media3PlaybackController(appContext, core, engine, library, settings, positions, scope, PlaybackService::class.java, audiobooks)
    val playback: PlaybackController = media3
    val voiceSamples: VoiceSamplePlayer = CoreVoiceSamplePlayer(appContext, core, engine, media3, scope)
    private val conditions = DeviceConditions(appContext)
    val installer = ModelInstaller(appContext, models)
    val exports = CoreExportRepository(appContext, core, core.dataDir, engine)
    val prepare = PrepareCenter(appContext, core, scope)
    val work = WorkCenter(appContext, installer, exports, prepare, engine, playback, settings, scope)
    val voiceModels: VoiceModelRepository = AndroidVoiceModels(appContext, models, installer, work, engine, settings, playback, { media3.modelChanged() }, scope)
    val imports = ImportQueue(library, scope)
    val removal = RemovalController(library, playback, settings, scope) { documentId -> scope.launch { audiobooks.unlink(documentId) } }

    /** Battery and heat feed the read-ahead governor (file 08 §3.3). Main thread. */
    fun start() {
        conditions.start()
        scope.launch { runCatching { core.call("set_encoder_sink") { it.setEncoderSink(AacEncoderSink()) } } }
        // Prepare runs while charging (file 14): plugging in starts the work service if a book waits for it.
        appContext.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (prepare.hasWork()) work.startService()
            }
        }, android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED))
        scope.launch(Dispatchers.Main) { conditions.conditions.collect(media3.readAhead::onConditions) }
    }

    /** File 10 §3.7: exports pause at thermal CRITICAL, and in battery saver or at ≤ 15 % while not charging. */
    fun systemPauseCause(): SystemPauseCause? {
        val now = conditions.conditions.value
        return when {
            now.thermalStatus >= THERMAL_CRITICAL -> SystemPauseCause.Heat
            now.batterySaver || now.batteryLow(wasLow = false) -> SystemPauseCause.Battery
            else -> null
        }
    }

    private companion object {
        const val THERMAL_CRITICAL = 4
    }
}
