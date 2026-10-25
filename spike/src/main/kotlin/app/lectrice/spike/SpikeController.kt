package app.lectrice.spike

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import uniffi.lectrice_ffi.CoreException
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

private const val TAG = "LectriceSpike"
private const val SOAK_MINUTES = 10
private const val BATTERY_RUN_MINUTES = 5
private const val UNPLUG_POLL_MS = 1_000L
private const val UNPLUG_SETTLE_MS = 60_000L
private const val DEFAULT_THREADS = 4
private const val MAX_LOG_LINES = 400
private const val IMPORT_FILE_NAME = "import.pdf"

/** The document the Play button reads: the last import. */
private data class ImportedDocument(val id: String, val firstChapterSentenceId: UInt)

/** Runs every button's work on one background thread, so only one measurement runs at a time. */
class SpikeController(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "spike-worker") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val power = PowerProbe(context)
    // The external files folder: adb can read it from a release build, and so can the share sheet.
    private val resultsDir = requireNotNull(context.getExternalFilesDir("results"))
    private val texts = context.assets.open("bench-text.txt").bufferedReader().readLines().filter(String::isNotBlank)
    private val _log = MutableStateFlow(listOf<String>())
    private val _busy = MutableStateFlow(false)
    val log: StateFlow<List<String>> = _log
    val busy: StateFlow<Boolean> = _busy

    private var spike: SpikeCore? = null
    private var results: ResultFile? = null
    private var bench: Bench? = null
    private var imported: ImportedDocument? = null
    private var bestThreads: Map<ModelBundle, Int> = ModelBundle.entries.filterNot(ModelBundle::testOnly).associateWith { DEFAULT_THREADS }
    private var earnedBatteryRuns: List<EngineChoice> = emptyList()
    private var bestCpuChoice: EngineChoice? = null

    val isSherpa2: Boolean = BuildConfig.SHERPA_SET == "sherpa2"

    fun start() = launchStep("start") {
        val models = BundledModels.install(context, ::log)
        ensureCore()
        models?.let { results().add("bundledModels", it) }
    }

    fun importPdf(uri: Uri) = launchStep("import") {
        val core = ensureCore().core
        val copyStarted = SystemClock.elapsedRealtime()
        val copy = File(context.cacheDir, "import/${UUID.randomUUID()}.pdf").also { it.parentFile?.mkdirs() }
        context.contentResolver.openInputStream(uri).use { input -> copy.outputStream().use { requireNotNull(input).copyTo(it) } }
        val copyMs = SystemClock.elapsedRealtime() - copyStarted
        val bytes = copy.length()
        // A neutral name: the result file and the core's log never see the real one.
        val result = try { core.importPdf(copy.path, IMPORT_FILE_NAME) } finally { copy.delete() }
        val documentStarted = SystemClock.elapsedRealtime()
        val document = core.getDocument(result.summary.id)
        val getDocumentMs = SystemClock.elapsedRealtime() - documentStarted
        val sentenceRecords = document.blocks.sumOf { block -> block.sentences.size + (if (block.cue != null) 1 else 0) }
        val topLevel = document.toc.minOfOrNull { it.level }
        val firstChapter = document.toc.firstOrNull { it.level == topLevel }?.sentenceId ?: 0u
        imported = ImportedDocument(result.summary.id, firstChapter)
        results().add("import", JSONObject()
            .put("documentId", result.summary.id)
            .put("bytes", bytes).put("copyMs", copyMs).put("importMs", result.durationMs.toLong())
            .put("alreadyInLibrary", result.alreadyInLibrary)
            .put("pages", result.summary.pageCount.toLong()).put("sentences", result.summary.sentenceCount.toLong())
            .put("getDocumentMs", getDocumentMs).put("blocks", document.blocks.size).put("sentenceRecords", sentenceRecords)
            .put("tocEntries", document.toc.size))
        log("import: ${result.summary.pageCount} pages, ${result.summary.sentenceCount} sentences in ${result.durationMs} ms; get_document $getDocumentMs ms")
    }

    fun loadEngine(choice: EngineChoice) = launchStep("engine") { bench().loadEngine(choice) }

    /** No [bundles] means every bundle on the phone. */
    fun benchAll(threadCounts: List<Int>, cores: CorePolicy, bundles: List<ModelBundle>? = null) = launchStep("bench") {
        benchAllAndRemember(threadCounts, cores, bundles ?: ModelBundle.entries.filter { ensureCore().isAvailable(it) })
    }

    /** [threads] replaces the best thread counts this session's bench found (for a run started over adb after a restart). */
    fun accelerators(threads: Map<ModelBundle, Int>? = null) = launchStep("accelerators") {
        earnedBatteryRuns = bench().accelerators(threads ?: bestThreads, wavDir())
    }

    fun soak(choice: EngineChoice) = launchStep("soak") { bench().soak(choice, SOAK_MINUTES) }

    /**
     * Button 4b: waits until the phone is unplugged, gives the user [UNPLUG_SETTLE_MS] to put it down, then runs the
     * soak and the battery runs (the accelerators that earned one in this session, unless [batteryRuns] says otherwise).
     * No [choice] means this session's fastest CPU combination.
     */
    fun soakWhenUnplugged(choice: EngineChoice?, batteryRuns: List<EngineChoice>? = null) = launchStep("unplugged runs") {
        val soakChoice = choice ?: bestCpuChoice ?: return@launchStep log("run the bench first, or pass the combination")
        val runs = batteryRuns ?: earnedBatteryRuns
        log("armed: soak ${soakChoice.bundle} ${soakChoice.threads}t ${soakChoice.provider}, then ${runs.size} battery runs; unplug the phone")
        while (power.isCharging()) delay(UNPLUG_POLL_MS)
        log("unplugged: the soak starts in ${UNPLUG_SETTLE_MS / 1000} s")
        delay(UNPLUG_SETTLE_MS)
        val bench = bench()
        if (!bench.soak(soakChoice, SOAK_MINUTES)) return@launchStep
        runs.forEach { bench.soak(it, BATTERY_RUN_MINUTES) }
    }

    fun play() = launchStep("play") {
        val document = imported ?: return@launchStep log("import a PDF first")
        bench().loadedBundle?.takeIf(ModelBundle::testOnly)?.let {
            return@launchStep log("play refused: $it shares the int8 engine id, so its clips would mix into the cache; load fp32 or int8 first")
        }
        val core = ensureCore().core
        val json = playTwoMinutes(context, core, document.id, document.firstChapterSentenceId)
        results().add("play", json.put("documentId", document.id).put("startSentenceId", document.firstChapterSentenceId.toLong()))
        log("play: first sound ${json.opt("timeToFirstSoundMs")} ms, ${json.getInt("rebufferCount")} rebuffers (${json.getLong("rebufferTotalMs")} ms)")
    }

    /** Button 6: engine → bench → accelerators → soak (+ battery runs). The sherpa2 build only benches the chosen thread count. */
    fun runAll(choice: EngineChoice, threadCounts: List<Int>) = launchStep("run all") {
        val bench = bench()
        bench.loadEngine(choice)
        if (isSherpa2) {
            ModelBundle.entries.forEach { bench.benchCombination(choice.copy(bundle = it, provider = "cpu")) }
            return@launchStep
        }
        val bundles = ModelBundle.entries.filter { !it.testOnly && ensureCore().isAvailable(it) }
        val best = benchAllAndRemember(threadCounts, choice.cores, bundles).minByOrNull { it.value.rtf } ?: return@launchStep log("no bundle loaded, nothing to soak")
        earnedBatteryRuns = bench.accelerators(bestThreads, wavDir())
        if (bench.soak(EngineChoice(best.key, best.value.threads, "cpu", choice.cores), SOAK_MINUTES)) {
            earnedBatteryRuns.forEach { bench.soak(it, BATTERY_RUN_MINUTES) }
        }
    }

    private fun benchAllAndRemember(threadCounts: List<Int>, cores: CorePolicy, bundles: List<ModelBundle>): Map<ModelBundle, BestThreads> {
        val best = bench().benchAll(threadCounts, bundles, cores)
        bestThreads = best.mapValues { it.value.threads }
        bestCpuChoice = best.minByOrNull { it.value.rtf }?.let { EngineChoice(it.key, it.value.threads, "cpu", cores) }
        log("best threads: $bestThreads")
        return best
    }

    /** Button 7: the result file and the saved sample WAVs through the share sheet. */
    fun shareIntent(): Intent? {
        val file = results?.file ?: return null
        val files = listOf(file) + (wavDir().listFiles()?.toList() ?: emptyList())
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.results", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share spike results")
    }

    private fun wavDir() = File(resultsDir, "wav")

    private fun ensureCore(): SpikeCore = spike ?: run {
        val startedAt = SystemClock.elapsedRealtime()
        val opened = SpikeCore(context)
        val results = ResultFile(resultsDir, deviceInfo(context), JSONObject()
            .put("versionName", BuildConfig.VERSION_NAME).put("sherpaSet", BuildConfig.SHERPA_SET).put("coreRevision", BuildConfig.CORE_REVISION))
        results.add("load", JSONObject().put("openMs", SystemClock.elapsedRealtime() - startedAt).put("pdfiumBinding", opened.pdfiumBinding)
            .put("nativeLibraryDir", context.applicationInfo.nativeLibraryDir).put("memoryKb", processMemoryKb()))
        log("core open (${Build.MODEL}, ${BuildConfig.SHERPA_SET}), pdfium by ${opened.pdfiumBinding}")
        spike = opened
        this.results = results
        bench = Bench(opened, results, power, texts, ::log)
        opened
    }

    private fun results(): ResultFile = requireNotNull(results)

    private fun bench(): Bench = ensureCore().let { requireNotNull(bench) }

    private fun launchStep(name: String, block: suspend () -> Unit) {
        if (_busy.value) return log("busy: wait for the current run")
        _busy.value = true
        scope.launch {
            try {
                block()
                log("$name done")
            } catch (error: CoreException.Failed) {
                log("$name failed: ${error.name}")
                results?.add("error", JSONObject().put("step", name).put("error", error.name))
            } catch (error: Exception) {
                Log.e(TAG, "$name failed", error)
                log("$name failed: ${error.javaClass.simpleName}")
                results?.add("error", JSONObject().put("step", name).put("error", error.javaClass.simpleName))
            } finally {
                _busy.value = false
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _log.value = (_log.value + line).takeLast(MAX_LOG_LINES)
    }
}
