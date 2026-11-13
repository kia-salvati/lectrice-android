package app.lectrice.core.playback

/**
 * Bounds for one sentence cut out of an audiobook file. The times come from a map inside the file and the audio format
 * from the file's headers; an imported file can claim anything.
 */
internal object SliceLimits {
    /** No sentence is longer (a very long one at 0.75× is under two minutes); more would be memory, not speech. */
    const val MAX_SLICE_MS = 10 * 60_000L
    const val MAX_DECODE_NANOS = 20_000_000_000L
    private val SAMPLE_RATES = 8_000..192_000
    private val CHANNELS = 1..8

    /** [startMs]..[endMs] made safe: never negative, never backwards, at most [MAX_SLICE_MS] long. */
    fun range(startMs: Long, endMs: Long): LongRange {
        val start = startMs.coerceAtLeast(0)
        val end = endMs.coerceIn(start, start + MAX_SLICE_MS)
        return start..end
    }

    fun formatOk(sampleRate: Int, channels: Int): Boolean = sampleRate in SAMPLE_RATES && channels in CHANNELS
}
