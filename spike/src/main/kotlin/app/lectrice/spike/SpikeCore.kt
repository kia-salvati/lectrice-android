package app.lectrice.spike

import android.content.Context
import android.os.Process
import android.util.Log
import uniffi.lectrice_ffi.CoreConfig
import uniffi.lectrice_ffi.CoreException
import uniffi.lectrice_ffi.KokoroVariant
import uniffi.lectrice_ffi.LectriceCore
import uniffi.lectrice_ffi.LogLevel
import uniffi.lectrice_ffi.LogSink
import java.io.File

private const val TAG = "LectriceCore"
private const val PDFIUM_LIBRARY = "libpdfium.so"
private const val DEFAULT_VOICE = "af_heart"
private const val READ_AHEAD_MINUTES = 10u
private const val KEEP_MINUTES_PER_DOCUMENT = 60u
private const val CACHE_MAX_MB = 1024uL
private const val IMPORT_MAX_MB = 200uL

/** The two model bundles, by their sherpa-onnx folder names. */
enum class ModelBundle(val dirName: String, val variant: KokoroVariant) {
    FP32("kokoro-multi-lang-v1_0", KokoroVariant.FP32),
    INT8("kokoro-int8-multi-lang-v1_0", KokoroVariant.INT8),
}

/** The core's `tracing` events go to logcat; they hold ids, counts and durations only. */
private object LogcatSink : LogSink {
    override fun log(level: LogLevel, target: String, message: String, fieldsJson: String) {
        val priority = when (level) {
            LogLevel.ERROR -> Log.ERROR
            LogLevel.WARN -> Log.WARN
            LogLevel.INFO -> Log.INFO
            LogLevel.DEBUG, LogLevel.TRACE -> Log.DEBUG
        }
        Log.println(priority, TAG, "$target: $message $fieldsJson")
    }
}

/** The core, opened with pdfium found by bare name, or by full path if that fails (M-a records which one worked). */
class SpikeCore(private val context: Context) {
    val pdfiumBinding: String
    val core: LectriceCore

    init {
        val fullPath = File(context.applicationInfo.nativeLibraryDir, PDFIUM_LIBRARY).path
        val opened = runCatching { open(PDFIUM_LIBRARY) }
        if (opened.isSuccess) {
            core = opened.getOrThrow()
            pdfiumBinding = "bareName"
        } else {
            Log.w(TAG, "pdfium by bare name failed: ${(opened.exceptionOrNull() as? CoreException.Failed)?.name}")
            core = open(fullPath)
            pdfiumBinding = "fullPath"
        }
    }

    fun modelDir(bundle: ModelBundle): File = File(context.filesDir, "models/${bundle.dirName}")

    /** `push-model.sh` puts a bundle in the app's external files folder (no run-as needed); move it in on first use. */
    fun adoptPushedModel(bundle: ModelBundle): Boolean {
        val pushed = File(context.getExternalFilesDir(null), "models/${bundle.dirName}")
        if (!pushed.isDirectory) return false
        val target = modelDir(bundle)
        target.deleteRecursively()
        pushed.copyRecursively(target, overwrite = true)
        pushed.deleteRecursively()
        return true
    }

    private fun open(pdfiumLibrary: String): LectriceCore {
        val config = CoreConfig(
            dataDir = File(context.filesDir, "lectrice").path,
            cacheDir = File(context.cacheDir, "lectrice").path,
            pdfiumLibrary = pdfiumLibrary,
            defaultVoice = DEFAULT_VOICE,
            readAheadMinutes = READ_AHEAD_MINUTES,
            keepMinutesPerDocument = KEEP_MINUTES_PER_DOCUMENT,
            cacheMaxMb = CACHE_MAX_MB,
            importMaxMb = IMPORT_MAX_MB,
        )
        return LectriceCore(config, LogcatSink)
    }

    companion object {
        const val VOICE = DEFAULT_VOICE
    }
}

/** sherpa-onnx logs through Android's log, not through `LogSink`: read this process's provider lines back. */
object SherpaLog {
    private val providerLine = Regex("(?i)provider|nnapi|xnnpack|fallback")
    private val fallback = Regex("(?i)fallback to cpu|not available|too old")

    /** Lines since `sinceEpochMs` that mention providers. Word-level lines (sherpa 2.0's OOV notes) are left out. */
    fun providerLines(sinceEpochMs: Long): List<String> {
        val since = "%d.%03d".format(sinceEpochMs / 1000, sinceEpochMs % 1000)
        val process = ProcessBuilder("logcat", "-d", "-v", "epoch", "--pid=${Process.myPid()}", "-T", since).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        return lines.filter { providerLine.containsMatchIn(it) && !it.contains("OOV") && !it.contains(TAG) }
    }

    /** No fallback line means the provider was taken (cpu always is). */
    fun engaged(provider: String, lines: List<String>): Boolean = provider == "cpu" || lines.none { fallback.containsMatchIn(it) }
}
