package app.lectrice.spike

import uniffi.lectrice_ffi.BenchResult
import uniffi.lectrice_ffi.EngineConfig
import uniffi.lectrice_ffi.EngineStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

private const val RUNS = 3
private const val WARM_UP_RUNS = 1
private const val DURATION_TOLERANCE = 0.03
private const val SOAK_SAMPLE_MS = 10_000L
private const val MS_PER_MINUTE = 60_000.0
private const val UAH_PER_MAH = 1000.0

data class BestThreads(val threads: Int, val rtf: Double)

data class EngineChoice(val bundle: ModelBundle, val threads: Int, val provider: String, val cores: CorePolicy = CorePolicy.All) {
    fun toJson(): JSONObject = JSONObject().put("bundle", bundle.name).put("threads", threads).put("provider", provider).put("cores", cores.label)
}

/** The bench, accelerator and soak runs (plan §5.2, buttons 2–4). Every method blocks; call them off the main thread. */
class Bench(private val spike: SpikeCore, private val results: ResultFile, private val power: PowerProbe, private val texts: List<String>, private val log: (String) -> Unit) {
    private val core = spike.core
    /** The process's threads before the last engine load: the ones that appear after it are ONNX Runtime's. */
    private var threadsBeforeLoad: Set<Int> = emptySet()

    /** The bundle of the engine loaded last (null before the first load). */
    var loadedBundle: ModelBundle? = null
        private set

    /**
     * Button 2. Records the load time and whether the provider was really taken. Pins the calling thread (the speech
     * thread) to [EngineChoice.cores] first, so the engine's worker threads inherit the mask, and records each new
     * thread's allowed CPUs.
     */
    fun loadEngine(choice: EngineChoice): JSONObject {
        if (spike.adoptPushedModel(choice.bundle)) log("model ${choice.bundle} moved in from the pushed folder")
        val modelSha256 = spike.modelSha256(choice.bundle)
        val pin = CpuAffinity.pinCurrentThread(choice.cores)
        threadsBeforeLoad = ThreadCensus.threadIds()
        val startedAt = System.currentTimeMillis()
        val config = EngineConfig.Kokoro(spike.modelDir(choice.bundle).path, choice.bundle.variant, choice.threads.toUInt(), choice.provider)
        val status = core.reloadEngine(config)
        loadedBundle = choice.bundle
        // After the load, so VmHWM later is this engine's synthesis peak (the old engine is gone by now).
        val peakReset = ThreadCensus.resetPeakMemory()
        val lines = SherpaLog.providerLines(startedAt)
        val json = choice.toJson().put("wallMs", System.currentTimeMillis() - startedAt).put("providerLines", JSONArray(lines))
            .put("providerEngaged", SherpaLog.engaged(choice.provider, lines)).put("memoryKb", processMemoryKb())
            .put("modelSha256", modelSha256 ?: JSONObject.NULL).put("affinity", pin).put("peakMemoryReset", peakReset)
            .put("newThreads", engineThreads())
        log("cores ${pin.getString("policy")}: asked ${pin.getString("requestedCpus")}, speech thread on ${pin.getString("appliedCpus")}; engine threads ${allowedSummary(json.getJSONArray("newThreads"))}")
        when (status) {
            is EngineStatus.Ready -> json.put("status", "ready").put("engineId", status.engineId).put("loadMs", status.loadMs.toLong())
            is EngineStatus.Missing -> json.put("status", "missing")
            is EngineStatus.Failed -> json.put("status", "failed").put("error", status.error.name)
        }
        results.add("engine", json)
        log("engine ${choice.bundle} ${choice.threads}t ${choice.provider}: ${json.getString("status")} ${json.optLong("loadMs")} ms")
        return json
    }

    /** Button 3 for one combination: [RUNS] runs, the first ones discarded as warm-up. */
    fun benchCombination(choice: EngineChoice): JSONObject? {
        val engine = loadEngine(choice)
        if (engine.getString("status") != "ready") return null
        val powerBefore = power.sample()
        val runs = (1..RUNS).map { core.benchSynthesize(texts, SpikeCore.VOICE) }
        val kept = runs.drop(WARM_UP_RUNS)
        val json = choice.toJson()
            .put("providerEngaged", engine.getBoolean("providerEngaged"))
            .put("loadMs", engine.getLong("loadMs"))
            .put("modelSha256", engine.get("modelSha256"))
            .put("medianRtf", median(kept.map(BenchResult::rtf)))
            .put("runs", JSONArray(runs.mapIndexed { index, run -> run.toJson().put("warmUp", index < WARM_UP_RUNS) }))
            .put("memoryKb", processMemoryKb())
            .put("peakMemoryReset", engine.getBoolean("peakMemoryReset"))
            .put("affinity", engine.getJSONObject("affinity"))
            // Read again after synthesis, in case ONNX Runtime starts some workers on the first run.
            .put("engineThreads", engineThreads())
            .put("powerBefore", powerBefore).put("powerAfter", power.sample())
        results.add("bench", json)
        log("bench ${choice.bundle} ${choice.threads}t ${choice.provider} cores ${choice.cores.label}: RTF ${"%.3f".format(json.getDouble("medianRtf"))}; threads ${allowedSummary(json.getJSONArray("engineThreads"))}")
        return json
    }

    /** Button 3: every bundle × thread count on the CPU. Returns the fastest thread count per bundle that loaded. */
    fun benchAll(threadCounts: List<Int>, bundles: List<ModelBundle>, cores: CorePolicy): Map<ModelBundle, BestThreads> = bundles.mapNotNull { bundle ->
        val rtfs = threadCounts.mapNotNull { threads -> benchCombination(EngineChoice(bundle, threads, "cpu", cores))?.let { BestThreads(threads, it.getDouble("medianRtf")) } }
        rtfs.minByOrNull(BestThreads::rtf)?.let { bundle to it }
    }.toMap()

    /** The threads that appeared with the last engine load and are still alive. */
    private fun engineThreads(): JSONArray = ThreadCensus.describe(ThreadCensus.threadIds() - threadsBeforeLoad)

    /** e.g. "3× 0-3, 1× 0-7" (allowed-CPU lists and how many threads have each). */
    private fun allowedSummary(threads: JSONArray): String =
        (0 until threads.length()).map { threads.getJSONObject(it).optString("cpusAllowed") }.groupingBy { it }.eachCount()
            .entries.joinToString(", ") { "${it.value}× ${it.key}" }.ifEmpty { "none new" }

    /**
     * Button 3b: xnnpack and nnapi at the best thread count, compared with the CPU (clip durations within ±3 %).
     * Returns the providers that were taken, agree with the CPU and are at least as fast: they earn a battery run.
     */
    fun accelerators(bestThreads: Map<ModelBundle, Int>, wavDir: File): List<EngineChoice> {
        wavDir.mkdirs()
        val worthABatteryRun = mutableListOf<EngineChoice>()
        for ((bundle, threads) in bestThreads) {
            val cpu = benchCombination(EngineChoice(bundle, threads, "cpu")) ?: continue
            saveSample(bundle, "cpu", wavDir)
            for (provider in listOf("xnnpack", "nnapi")) {
                val run = benchCombination(EngineChoice(bundle, threads, provider)) ?: continue
                saveSample(bundle, provider, wavDir)
                val deviation = maxDurationDeviation(cpu, run)
                val summary = EngineChoice(bundle, threads, provider).toJson()
                    .put("providerEngaged", run.getBoolean("providerEngaged"))
                    .put("medianRtf", run.getDouble("medianRtf"))
                    .put("cpuMedianRtf", cpu.getDouble("medianRtf"))
                    .put("maxDurationDeviation", deviation)
                    .put("durationsAgree", deviation <= DURATION_TOLERANCE)
                results.add("accelerator", summary)
                log("$provider ${bundle.name}: engaged=${summary.getBoolean("providerEngaged")} RTF ${"%.3f".format(summary.getDouble("medianRtf"))} vs cpu ${"%.3f".format(summary.getDouble("cpuMedianRtf"))}")
                val notSlower = summary.getDouble("medianRtf") <= summary.getDouble("cpuMedianRtf")
                if (summary.getBoolean("providerEngaged") && summary.getBoolean("durationsAgree") && notSlower) {
                    worthABatteryRun += EngineChoice(bundle, threads, provider)
                }
            }
        }
        return worthABatteryRun
    }

    /** Button 4: the bench text on a loop, sampling heat, memory and battery every 10 s. Refuses while charging. */
    fun soak(choice: EngineChoice, minutes: Int): Boolean {
        if (power.isCharging()) {
            log("soak refused: the phone is charging, unplug it first")
            return false
        }
        val engine = loadEngine(choice)
        if (engine.getString("status") != "ready") return false
        val durationMs = minutes * MS_PER_MINUTE.toLong()
        val startedAt = System.currentTimeMillis()
        val startCharge = power.chargeCounterUah()
        val samples = JSONArray()
        var windowAudio = 0L
        var windowSynth = 0L
        var totalAudio = 0L
        var lastSampleAt = startedAt
        var index = 0
        samples.put(power.sample().put("atMs", 0).put("memoryKb", processMemoryKb()))
        while (System.currentTimeMillis() - startedAt < durationMs) {
            val sentence = core.benchSynthesize(listOf(texts[index % texts.size]), SpikeCore.VOICE)
            index++
            windowAudio += sentence.audioMs.toLong()
            windowSynth += sentence.synthMs.toLong()
            totalAudio += sentence.audioMs.toLong()
            val now = System.currentTimeMillis()
            if (now - lastSampleAt >= SOAK_SAMPLE_MS) {
                val rtf = if (windowAudio == 0L) 0.0 else windowSynth.toDouble() / windowAudio
                samples.put(power.sample().put("atMs", now - startedAt).put("rtf", rtf).put("memoryKb", processMemoryKb()))
                windowAudio = 0
                windowSynth = 0
                lastSampleAt = now
            }
        }
        val usedMah = (startCharge - power.chargeCounterUah()) / UAH_PER_MAH
        val audioMinutes = totalAudio / MS_PER_MINUTE
        val json = choice.toJson().put("minutes", minutes).put("sentences", index).put("audioMs", totalAudio)
            .put("usedMah", usedMah).put("mahPerAudioMinute", if (audioMinutes > 0) usedMah / audioMinutes else JSONObject.NULL)
            .put("samples", samples)
            .put("modelSha256", engine.get("modelSha256")).put("affinity", engine.getJSONObject("affinity")).put("engineThreads", engineThreads())
        results.add("soak", json)
        log("soak done: $index sentences, ${"%.1f".format(audioMinutes)} audio min, ${"%.1f".format(usedMah)} mAh")
        return true
    }

    private fun saveSample(bundle: ModelBundle, provider: String, wavDir: File) {
        File(wavDir, "${bundle.name.lowercase()}-$provider.wav").writeBytes(core.benchSpeak(texts.first(), SpikeCore.VOICE))
    }
}

private fun BenchResult.toJson(): JSONObject = JSONObject()
    .put("engineLoadMs", engineLoadMs.toLong())
    .put("sentences", sentences.toLong())
    .put("audioMs", audioMs.toLong())
    .put("synthMs", synthMs.toLong())
    .put("rtf", rtf)
    .put("firstSentenceMs", firstSentenceMs.toLong())
    .put("perSentence", JSONArray(perSentence.map {
        JSONObject().put("index", it.index.toLong()).put("chars", it.chars.toLong()).put("audioMs", it.audioMs.toLong())
            .put("synthMs", it.synthMs.toLong()).put("atMs", it.atMs.toLong())
    }))

/** The largest relative difference between the two runs' clip durations, over the last (kept) run of each. */
private fun maxDurationDeviation(cpu: JSONObject, other: JSONObject): Double {
    fun durations(run: JSONObject): List<Long> {
        val runs = run.getJSONArray("runs")
        val sentences = runs.getJSONObject(runs.length() - 1).getJSONArray("perSentence")
        return (0 until sentences.length()).map { sentences.getJSONObject(it).getLong("audioMs") }
    }
    return durations(cpu).zip(durations(other)).maxOfOrNull { (a, b) -> if (a == 0L) 0.0 else abs(a - b).toDouble() / a } ?: 0.0
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    if (sorted.isEmpty()) return 0.0
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
