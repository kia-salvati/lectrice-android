package app.lectrice.core.playback

/**
 * The player holds a window of the book, not the whole book (file 09 §3.2): 50 items behind the current one and 150
 * ahead. When fewer than 50 remain ahead, the next items are appended and the oldest beyond 50 behind dropped.
 * Pure arithmetic over positions in the playback order, so it is tested on the JVM.
 */
class PlaylistWindow(private val orderSize: Int) {
    /** Positions [start, end) of the playback order currently in the player. */
    var start = 0
        private set
    var end = 0
        private set

    /** A fresh window around [position] (a jump outside, a voice or read-code change). Returns the index of [position] in it. */
    fun rebuildAround(position: Int): Int {
        start = (position - BEHIND).coerceAtLeast(0)
        end = (position + AHEAD).coerceAtMost(orderSize)
        return position - start
    }

    fun contains(position: Int): Boolean = position in start until end

    /**
     * What to do after moving to [position] (inside the window). Only when fewer than 50 remain ahead: the next 200 are
     * appended and the items beyond 50 behind are dropped in the same change (the timeline changes rarely).
     */
    fun advance(position: Int): Change {
        if (end - position >= MIN_AHEAD || end >= orderSize) return Change(end, 0, 0)
        val newEnd = (end + STEP).coerceAtMost(orderSize)
        val append = newEnd - end
        end = newEnd
        val newStart = (position - BEHIND).coerceAtLeast(start)
        val drop = newStart - start
        start = newStart
        return Change(appendFrom = end - append, appendCount = append, dropFromFront = drop)
    }

    /** [appendFrom] is a position in the order; [dropFromFront] counts items to remove at the window's front. */
    data class Change(val appendFrom: Int, val appendCount: Int, val dropFromFront: Int) {
        val isEmpty: Boolean get() = appendCount == 0 && dropFromFront == 0
    }

    companion object {
        const val BEHIND = 50
        const val AHEAD = 150
        const val MIN_AHEAD = 50
        const val STEP = 200
    }
}
