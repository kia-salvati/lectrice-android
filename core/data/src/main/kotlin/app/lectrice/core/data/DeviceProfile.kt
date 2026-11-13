package app.lectrice.core.data

import java.io.File

/**
 * The phone class for speech threads (variants-cores, 2026-10-25): use every "big" core (capacity ≥ half the fastest),
 * no pinning. Galaxy S10+ (4 little + 4 big) → 4; Galaxy S26 Ultra (no little cores) → 8.
 */
object DeviceProfile {
    private const val MIN_THREADS = 2
    private const val MAX_THREADS = 8
    private const val BIG_CORE_SHARE = 0.5

    /** Read once: the CPU layout doesn't change while the app runs. */
    private val cached: Int by lazy { threadsFor(File("/sys/devices/system/cpu")) }

    fun defaultThreads(): Int = cached

    fun threadsFor(cpuRoot: File): Int {
        val capacities = cpuRoot.listFiles { file -> file.name.matches(Regex("cpu\\d+")) }.orEmpty().mapNotNull(::capacity)
        if (capacities.isEmpty()) return (Runtime.getRuntime().availableProcessors()).coerceIn(MIN_THREADS, MAX_THREADS)
        val fastest = capacities.max()
        return capacities.count { it >= fastest * BIG_CORE_SHARE }.coerceIn(MIN_THREADS, MAX_THREADS)
    }

    /** `cpu_capacity`, else the max frequency (older kernels). */
    private fun capacity(cpu: File): Long? =
        File(cpu, "cpu_capacity").readLongOrNull() ?: File(cpu, "cpufreq/cpuinfo_max_freq").readLongOrNull()

    private fun File.readLongOrNull(): Long? = runCatching { readText().trim().toLong() }.getOrNull()
}
