package app.lectrice.core.data

import android.content.Context
import android.os.Looper
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.lectrice_ffi.CoreConfig
import uniffi.lectrice_ffi.CoreException
import uniffi.lectrice_ffi.LectriceCore
import uniffi.lectrice_ffi.LogLevel
import uniffi.lectrice_ffi.LogSink
import java.io.File

/**
 * Owns the uniffi `LectriceCore` (one per process). Every call runs on [Dispatchers.IO] and maps core errors to
 * [LectriceError]; debug builds throw if a blocking call is made on the main thread (StrictMode doesn't see JNA calls).
 */
class RustCore(private val context: Context, private val debug: Boolean) {
    private val core: LectriceCore by lazy { open() }

    val dataDir: File get() = File(context.filesDir, DATA_DIR)

    /** Runs [block] on IO with the core; errors become [LectriceError]. */
    suspend fun <T> call(name: String, block: (LectriceCore) -> T): T = withContext(Dispatchers.IO) { blocking(name, block) }

    /** For callers already on a background thread (ExoPlayer's loader, the export callbacks). */
    fun <T> blocking(name: String, block: (LectriceCore) -> T): T {
        if (debug && Looper.myLooper() == Looper.getMainLooper()) error("core call $name on the main thread")
        val started = System.nanoTime()
        try {
            return block(core)
        } catch (failed: CoreException.Failed) {
            Log.w(SCOPE, "$name failed: ${failed.name} (${failed.code})")
            throw LectriceError(ErrorName.fromName(failed.name), failed)
        } catch (error: CoreException) {
            Log.w(SCOPE, "$name failed", error)
            throw LectriceError(ErrorName.Internal, error)
        } finally {
            Log.d(SCOPE, "$name ${(System.nanoTime() - started) / NANOS_PER_MS} ms")
        }
    }

    private fun open(): LectriceCore {
        val config = CoreConfig(
            dataDir = dataDir.path,
            cacheDir = File(context.cacheDir, DATA_DIR).path,
            pdfiumLibrary = PDFIUM_LIBRARY,
            defaultVoice = DEFAULT_VOICE,
            readAheadMinutes = READ_AHEAD_MINUTES,
            keepMinutesPerDocument = KEEP_MINUTES_PER_DOCUMENT,
            cacheMaxMb = CACHE_MAX_MB,
            importMaxMb = IMPORT_MAX_MB,
        )
        return try {
            LectriceCore(config, LogcatSink)
        } catch (failed: CoreException.Failed) {
            throw LectriceError(ErrorName.fromName(failed.name), failed)
        }
    }

    /** The core's `tracing` events (ids, counts, durations) go to logcat. */
    private object LogcatSink : LogSink {
        override fun log(level: LogLevel, target: String, message: String, fieldsJson: String) {
            val line = "$target: $message $fieldsJson"
            when (level) {
                LogLevel.ERROR, LogLevel.WARN -> Log.w(CORE_SCOPE, line)
                LogLevel.INFO -> Log.i(CORE_SCOPE, line)
                LogLevel.DEBUG, LogLevel.TRACE -> Log.d(CORE_SCOPE, line)
            }
        }
    }

    companion object {
        const val IMPORT_MAX_MB = 200uL
        private const val SCOPE = "RustCore"
        private const val CORE_SCOPE = "core"
        private const val DATA_DIR = "lectrice"
        /** A bare name: the system linker finds it in the app's native library folder. */
        private const val PDFIUM_LIBRARY = "libpdfium.so"
        private const val DEFAULT_VOICE = "af_heart"
        private const val READ_AHEAD_MINUTES = 10u
        private const val KEEP_MINUTES_PER_DOCUMENT = 60u
        /** The phone's audio cache cap (file 08 §3.2). */
        private const val CACHE_MAX_MB = 1024uL
        private const val NANOS_PER_MS = 1_000_000
    }
}
