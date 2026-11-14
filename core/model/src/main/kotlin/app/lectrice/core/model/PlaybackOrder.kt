package app.lectrice.core.model

/**
 * What playback reads, in order, for one setting of "Read code & tables aloud". The ids come from the core's
 * `playback_order` (the one source for the player, read-ahead and export); this class adds the lookups the UI needs.
 * Lengths use the shown text (README §4.7: Kotlin estimates from the sentence text).
 */
class PlaybackOrder(val sentenceIds: List<Int>, document: Document) {
    private val positionOf: Map<Int, Int> = sentenceIds.withIndex().associate { (position, id) -> id to position }

    /** Block index of every sentence in the document, cues included. */
    private val blockIndexOfAny: Map<Int, Int>
    private val sentenceById: Map<Int, Sentence>

    /** charsFrom[i] = characters from sentenceIds[i] to the end, with one trailing 0. */
    private val charsFrom: IntArray

    init {
        val blockIndex = HashMap<Int, Int>()
        val sentences = HashMap<Int, Sentence>()
        document.blocks.forEachIndexed { index, block ->
            block.sentences.forEach { blockIndex[it.id] = index; sentences[it.id] = it }
            block.cue?.let { blockIndex[it.id] = index; sentences[it.id] = it }
        }
        blockIndexOfAny = blockIndex
        sentenceById = sentences
        charsFrom = IntArray(sentenceIds.size + 1)
        for (i in sentenceIds.indices.reversed()) charsFrom[i] = charsFrom[i + 1] + (sentences[sentenceIds[i]]?.text?.length ?: 0)
    }

    val isEmpty: Boolean get() = sentenceIds.isEmpty()

    fun contains(sentenceId: Int): Boolean = sentenceId in positionOf

    fun positionOf(sentenceId: Int): Int? = positionOf[sentenceId]

    fun blockIndexOf(sentenceId: Int): Int? = blockIndexOfAny[sentenceId]

    fun sentence(sentenceId: Int): Sentence? = sentenceById[sentenceId]

    /** First playable sentence in this block or a later one. */
    fun firstPlayableFromBlock(blockIndex: Int): Int? = sentenceIds.firstOrNull { (blockIndexOfAny[it] ?: -1) >= blockIndex }

    /** Itself if playable, else the first playable one at or after it (by id, then by block). */
    fun resolvePlayable(sentenceId: Int): Int? {
        if (sentenceId in positionOf) return sentenceId
        val nextById = sentenceIds.firstOrNull { it > sentenceId }
        val blockIndex = blockIndexOfAny[sentenceId] ?: return nextById
        val fromBlock = firstPlayableFromBlock(blockIndex)
        return listOfNotNull(nextById, fromBlock).minOrNull()
    }

    /** The saved sentence (resolved), else the first playable one; null if nothing is playable. */
    fun startingSentenceId(lastSentenceId: Int?): Int? = lastSentenceId?.let(::resolvePlayable) ?: sentenceIds.firstOrNull()

    /** The playable sentence [offset] steps away, or null past either end. */
    fun stepFrom(sentenceId: Int, offset: Int): Int? {
        val position = positionOf[sentenceId] ?: return null
        return sentenceIds.getOrNull(position + offset)
    }

    val lastSentenceId: Int? get() = sentenceIds.lastOrNull()

    /** The saved place is the last sentence read aloud, or past it (a caption or code block that isn't read). */
    fun isFinishedAt(savedSentenceId: Int?): Boolean {
        val last = lastSentenceId ?: return false
        return savedSentenceId != null && savedSentenceId >= last
    }

    /** Listening seconds from (after) [fromSentenceId] to [toSentenceId] (null = the end of the book). */
    fun secondsBetween(fromSentenceId: Int, toSentenceId: Int?, rate: Float): Double {
        val from = positionOfAny(fromSentenceId)?.plus(1) ?: sentenceIds.size
        val to = toSentenceId?.let(::positionOfAny) ?: sentenceIds.size
        val chars = (charsFrom[from.coerceAtMost(sentenceIds.size)] - charsFrom[to.coerceAtMost(sentenceIds.size)]).coerceAtLeast(0)
        return chars / Progress.CHARS_PER_SECOND / rate
    }

    /** Listening seconds after the current sentence to the end of the book. */
    fun secondsLeft(sentenceId: Int, rate: Float): Double = secondsBetween(sentenceId, null, rate)

    private fun positionOfAny(sentenceId: Int): Int? = resolvePlayable(sentenceId)?.let(positionOf::get)
}
