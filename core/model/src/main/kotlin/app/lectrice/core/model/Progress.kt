package app.lectrice.core.model

import kotlin.math.ceil
import kotlin.math.floor

/** Ports of `frontend/src/utils/progress.ts` and `time.ts` (README §4.7). */
object Progress {
    /** Measured: 18 characters per second of 1× speech (≈ 189 words a minute). */
    const val CHARS_PER_SECOND = 18.0

    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val PERCENT = 100

    /** Share read, 0–100, from the saved sentence id; null if never started. */
    fun percentRead(lastSentenceId: Int?, sentenceCount: Int): Int? {
        if (lastSentenceId == null || sentenceCount <= 0) return null
        return minOf(PERCENT, Math.round((lastSentenceId + 1).toDouble() / sentenceCount * PERCENT).toInt())
    }

    /** A finished book in the library: read to 100 % (rows know only the summary; the card also asks the playback order). */
    fun isFinished(percent: Int?): Boolean = percent == PERCENT

    /** "under a minute left", "12 min left", "4 h 5 min left". */
    fun formatTimeLeft(seconds: Double): String {
        val minutes = ceil(seconds / SECONDS_PER_MINUTE).toInt()
        if (minutes <= 1) return "under a minute left"
        if (minutes < MINUTES_PER_HOUR) return "$minutes min left"
        return "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min left"
    }

    /** "about 8 h 10 min", "about 9 min" (export estimates). */
    fun formatDuration(seconds: Double): String {
        val minutes = ceil(seconds / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)
        if (minutes < MINUTES_PER_HOUR) return "$minutes min"
        val rest = minutes % MINUTES_PER_HOUR
        return if (rest == 0) "${minutes / MINUTES_PER_HOUR} h" else "${minutes / MINUTES_PER_HOUR} h $rest min"
    }

    private val UNITS = listOf("year" to 31_536_000L, "month" to 2_592_000L, "week" to 604_800L, "day" to 86_400L, "hour" to 3_600L, "minute" to 60L)

    /** "just now", "2 hours ago", "yesterday", "last week" (Intl's `numeric: 'auto'` wording). */
    fun formatRelativeTime(epochSeconds: Long, nowEpochSeconds: Long): String {
        val elapsed = nowEpochSeconds - epochSeconds
        for ((unit, size) in UNITS) {
            if (elapsed >= size) {
                val count = floor(elapsed.toDouble() / size).toLong()
                return when {
                    count == 1L && unit == "day" -> "yesterday"
                    count == 1L && unit in setOf("week", "month", "year") -> "last $unit"
                    count == 1L -> "1 $unit ago"
                    else -> "$count ${unit}s ago"
                }
            }
        }
        return "just now"
    }
}
