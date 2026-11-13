package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

enum class ExportStatus { Queued, Running, Paused, Done, Failed, Cancelled }

enum class PauseReason { User, System, StorageFull, EngineSwitch }

/** Why the work service paused an export for the system (file 10 §3.7), so E3 can say it. */
enum class SystemPauseCause { TimeLimit, Heat, Battery }

data class AudioExport(
    val id: String,
    val documentId: String,
    val voiceId: String,
    val speed: PlaybackSpeed,
    val engineId: String,
    val range: IntRange?,
    val rangeLabel: String?,
    val title: String,
    val status: ExportStatus,
    val pausedBy: PauseReason?,
    val assembling: Boolean,
    val error: ErrorName?,
    val sentencesDone: Int,
    val sentencesTotal: Int,
    val partsDone: Int,
    val partsTotal: Int,
    val secondsLeft: Int?,
    val fileBytes: Long?,
    /** In app storage while there; null once moved to Audiobooks. */
    val filePath: String?,
    /** The MediaStore URI once saved to Audiobooks. */
    val sharedUri: String?,
    val waitingForEngine: Boolean,
) {
    val isUnfinished: Boolean get() = status == ExportStatus.Queued || status == ExportStatus.Running || status == ExportStatus.Paused

    val percent: Int get() = if (sentencesTotal == 0) 0 else (sentencesDone * PERCENT / sentencesTotal).coerceIn(0, PERCENT)

    private companion object {
        const val PERCENT = 100
    }
}

/** What to export (file 10 §3.2). M4B only. */
data class ExportRequest(
    val documentId: String,
    val voiceId: String,
    val modelId: String,
    val readSkipped: Boolean,
    val speed: PlaybackSpeed,
    val range: IntRange? = null,
    val rangeLabel: String? = null,
)

/** The core's estimate (C18): lengths in ms of 1× audio, plus offsets to size any chapter range. */
data class ExportEstimate(
    val audioMs1x: Long,
    val outputMs: Long,
    val cachedMs1x: Long,
    val totalAudioMs1x: Long,
    /** TOC index → 1× audio before that entry, over the whole book. */
    val tocOffsetsMs: Map<Int, Long>,
    /** Planned parts of the whole book: first sentence, its page label, 1× audio before it (books without a TOC). */
    val partOffsets: List<PartOffset>,
)

data class PartOffset(val firstSentence: Int, val pageLabel: String, val audioMs1xBefore: Long)

interface ExportRepository {
    /** Every export of every book, newest first; refreshed by the work service while jobs run. */
    val exports: StateFlow<List<AudioExport>>

    suspend fun refresh()

    suspend fun estimate(request: ExportRequest): ExportEstimate

    /** Starts (or continues the same target), then the work service runs it in the foreground. */
    suspend fun start(request: ExportRequest): AudioExport

    suspend fun pause(exportId: String)

    suspend fun resume(exportId: String)

    suspend fun cancel(exportId: String)

    suspend fun delete(exportId: String)

    /** Moves the finished file to `Audiobooks/Lectrice/` (file 20 §3.4). */
    suspend fun saveToAudiobooks(exportId: String)

    /** The bytes the phone can still take (`getAllocatableBytes`). */
    suspend fun freeBytes(): Long

    /** Bytes of this export's finished parts (for the resume space check). */
    fun partsBytes(exportId: String): Long

    /** Why the service paused it for the system, when it did. */
    val systemPauseCause: StateFlow<Map<String, SystemPauseCause>>
}

/** File 10 §3.11: predict the space an export needs. Pure. */
object ExportSpace {
    /** AAC-LC mono 64 kbit/s. */
    const val BYTES_PER_SECOND = 8_000L
    const val CONTAINER_OVERHEAD = 0.03
    const val MIN_MARGIN_BYTES = 100_000_000L
    const val MARGIN_SHARE = 0.10

    fun outputSeconds(audioMs1x: Long, speed: PlaybackSpeed): Double = audioMs1x / 1000.0 / speed.rate

    /** The size shown to the user ("about 231 MB"). */
    fun shownBytes(outputSeconds: Double): Long = (outputSeconds * BYTES_PER_SECOND).toLong()

    fun finalBytes(outputSeconds: Double): Long = (outputSeconds * BYTES_PER_SECOND * (1 + CONTAINER_OVERHEAD)).toLong()

    fun margin(finalBytes: Long): Long = maxOf(MIN_MARGIN_BYTES, (finalBytes * MARGIN_SHARE).toLong())

    /** New export: parts + final file + margin. */
    fun neededNew(finalBytes: Long): Long = 2 * finalBytes + margin(finalBytes)

    fun neededResumeParts(finalBytes: Long, partsBytes: Long): Long = (finalBytes - partsBytes).coerceAtLeast(0) + finalBytes + margin(finalBytes)

    fun neededResumeAssemble(finalBytes: Long): Long = finalBytes + margin(finalBytes)

    /**
     * The suggested range (file 10 §3.11 E7): from the chapter at [startIndex] (in [chapterMs1x], the 1× ms of each
     * chapter-level entry in order), as many chapters as fit. Returns the last chapter index, or null if the first
     * doesn't fit.
     */
    fun suggestEnd(chapterMs1x: List<Long>, startIndex: Int, speed: PlaybackSpeed, freeBytes: Long): Int? {
        var total = 0L
        var end: Int? = null
        for (index in startIndex until chapterMs1x.size) {
            total += chapterMs1x[index]
            if (neededNew(finalBytes(outputSeconds(total, speed))) > freeBytes) break
            end = index
        }
        return end
    }

    /** "a few minutes", "about 40 min", "about 2 h", "about 2 h 10 min" (file 10 §3.4). */
    fun formatMakeTime(seconds: Double): String {
        val minutes = seconds / SECONDS_PER_MINUTE
        return when {
            minutes < FEW_MINUTES -> "a few minutes"
            minutes < MINUTES_PER_HOUR -> "about ${roundTo(minutes, FIVE)} min"
            else -> {
                val total = roundTo(minutes, TEN)
                val hours = total / MINUTES_PER_HOUR
                val rest = total % MINUTES_PER_HOUR
                if (rest == 0) "about $hours h" else "about $hours h $rest min"
            }
        }
    }

    private fun roundTo(value: Double, step: Int): Int = (Math.round(value / step) * step).toInt().coerceAtLeast(step)

    private const val SECONDS_PER_MINUTE = 60.0
    private const val MINUTES_PER_HOUR = 60
    private const val FEW_MINUTES = 5
    private const val FIVE = 5
    private const val TEN = 10
}
