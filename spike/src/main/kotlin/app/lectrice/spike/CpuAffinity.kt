package app.lectrice.spike

import com.sun.jna.Function
import com.sun.jna.LastErrorException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val CPU_ROOT = "/sys/devices/system/cpu"
/** bionic's `cpu_set_t` on arm64: 1024 bits. */
private const val CPU_SET_WORDS = 16
private const val BITS_PER_WORD = 64
private const val CPU_SET_BYTES = CPU_SET_WORDS * Long.SIZE_BYTES
/** A core counts as "big" from this share of the fastest core's capacity: drops the little A55-class cores. */
private const val BIG_CORE_SHARE = 0.5
/** Field 39 of `/proc/<pid>/task/<tid>/stat`: the CPU the thread last ran on. */
private const val STAT_PROCESSOR_FIELD = 39
/** `/proc/self/clear_refs` value that resets the peak RSS (VmHWM). */
private const val RESET_PEAK_RSS = "5"

/** Which cores the speech thread (and the ONNX Runtime workers it creates) may use. */
sealed class CorePolicy(val label: String) {
    data object All : CorePolicy("all")
    data object Big : CorePolicy("big")
    /** An explicit list, e.g. `6-7` or `0,2`. */
    data class Cpus(val cpus: List<Int>) : CorePolicy(formatCpuList(cpus))

    companion object {
        fun parse(value: String): CorePolicy = when (value.lowercase()) {
            "all" -> All
            "big" -> Big
            else -> Cpus(parseCpuList(value))
        }
    }
}

/**
 * Pins the calling thread with `sched_setaffinity`. Threads created afterwards inherit the mask, so calling this on the
 * speech thread before the engine loads also pins ONNX Runtime's worker threads.
 */
object CpuAffinity {
    // JNA's Function calls rather than a Library interface: R8 rewrites the interface and breaks JNA's proxy.
    private val setAffinity by lazy { Function.getFunction("c", "sched_setaffinity", Function.THROW_LAST_ERROR) }
    private val getAffinity by lazy { Function.getFunction("c", "sched_getaffinity") }

    /** Core capacity per CPU from `/sys` (`cpu_capacity`, else the max frequency). */
    fun capacities(): Map<Int, Long> {
        val cpus = parseCpuList(File("$CPU_ROOT/possible").readText().trim())
        return cpus.associateWith { cpu ->
            readLong("$CPU_ROOT/cpu$cpu/cpu_capacity") ?: readLong("$CPU_ROOT/cpu$cpu/cpufreq/cpuinfo_max_freq") ?: 0L
        }
    }

    fun cpusFor(policy: CorePolicy): List<Int> = when (policy) {
        CorePolicy.All -> capacities().keys.sorted()
        CorePolicy.Big -> capacities().let { capacity ->
            val fastest = capacity.values.maxOrNull() ?: 0L
            capacity.filterValues { it >= fastest * BIG_CORE_SHARE }.keys.sorted()
        }
        is CorePolicy.Cpus -> policy.cpus
    }

    /** Applies [policy] to the calling thread; returns what was asked, what the kernel kept, and the capacities. */
    fun pinCurrentThread(policy: CorePolicy): JSONObject {
        val requested = cpusFor(policy)
        val mask = LongArray(CPU_SET_WORDS)
        requested.forEach { cpu -> mask[cpu / BITS_PER_WORD] = mask[cpu / BITS_PER_WORD] or (1L shl (cpu % BITS_PER_WORD)) }
        val json = JSONObject().put("policy", policy.label).put("requestedCpus", formatCpuList(requested))
            .put("capacities", JSONObject(capacities().mapKeys { it.key.toString() }))
        try {
            setAffinity.invokeInt(arrayOf(0, CPU_SET_BYTES.toLong(), mask))
        } catch (error: LastErrorException) {
            json.put("setAffinityErrno", error.errorCode)
        }
        return json.put("appliedCpus", currentThreadCpus())
    }

    private fun currentThreadCpus(): String {
        val mask = LongArray(CPU_SET_WORDS)
        if (getAffinity.invokeInt(arrayOf(0, CPU_SET_BYTES.toLong(), mask)) != 0) return "unknown"
        val cpus = (0 until CPU_SET_WORDS * BITS_PER_WORD).filter { cpu -> mask[cpu / BITS_PER_WORD] and (1L shl (cpu % BITS_PER_WORD)) != 0L }
        return formatCpuList(cpus)
    }

    private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()
}

/** This process's threads: id, name, allowed CPUs and the CPU each last ran on. Thread names only, no user data. */
object ThreadCensus {
    fun threadIds(): Set<Int> = File("/proc/self/task").list()?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

    fun describe(tids: Collection<Int>): JSONArray = JSONArray(tids.sorted().mapNotNull { tid ->
        runCatching {
            val dir = "/proc/self/task/$tid"
            val allowed = File("$dir/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(":")?.trim()
            // The name sits in brackets and may hold spaces, so count fields after the closing bracket.
            val stat = File("$dir/stat").readText()
            val lastCpu = stat.substringAfterLast(") ").split(" ").getOrNull(STAT_PROCESSOR_FIELD - 3)?.toIntOrNull()
            JSONObject().put("tid", tid).put("name", File("$dir/comm").readText().trim()).put("cpusAllowed", allowed).put("lastCpu", lastCpu)
        }.getOrNull()
    })

    /** Resets VmHWM so the next reading is this run's peak. False where the kernel or SELinux says no. */
    fun resetPeakMemory(): Boolean = runCatching { File("/proc/self/clear_refs").writeText(RESET_PEAK_RSS) }.isSuccess
}

private fun parseCpuList(value: String): List<Int> = value.split(",").filter(String::isNotBlank).flatMap { part ->
    val bounds = part.trim().split("-").map(String::toInt)
    (bounds.first()..bounds.last()).toList()
}.distinct().sorted()

private fun formatCpuList(cpus: List<Int>): String {
    if (cpus.isEmpty()) return ""
    val ranges = mutableListOf<IntRange>()
    for (cpu in cpus.sorted()) {
        val last = ranges.lastOrNull()
        if (last != null && last.last + 1 == cpu) ranges[ranges.lastIndex] = last.first..cpu else ranges += cpu..cpu
    }
    return ranges.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
}
