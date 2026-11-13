package app.lectrice.core.model

import java.text.Normalizer

/** A title's own number and the rest: "Chapter 4 Structured Programming" → number "4", name "Structured Programming". */
data class ChapterLabel(val number: String?, val name: String)

/** Where a sentence is in the book, as indexes into the flat TOC. The last of [path] is the current entry. */
data class TocLocation(val path: List<Int>, val chapterIndex: Int, val sectionIndex: Int?)

/** Port of the web's `frontend/src/utils/toc.ts` (file 07 §3.2); the regexes are copied exactly. */
object Toc {
    /** "Chapter 4 …", "Chapter 2. …", "Ch. IV …": the word, a number (digits or roman), optional "." or ":". */
    private val CHAPTER_WORD_RE = Regex("""^(?:chapter|ch\.)\s+(\d+|[ivxlc]+)\b[.:]?\s*(.*)$""", RegexOption.IGNORE_CASE)

    /** "4: Working with Pods", "1. Introduction". "20 Popular …" (no mark) is not a chapter. */
    private val NUMBERED_TITLE_RE = Regex("""^(\d+)[.:]\s+(.*)$""")

    fun chapterLabel(title: String): ChapterLabel {
        val match = CHAPTER_WORD_RE.find(title) ?: NUMBERED_TITLE_RE.find(title)
        return if (match != null) ChapterLabel(match.groupValues[1], match.groupValues[2]) else ChapterLabel(null, title)
    }

    fun isChapterLike(title: String): Boolean = chapterLabel(title).number != null

    /** Index of the last entry starting at or before the sentence (any level); null before the first entry. */
    fun currentEntryIndex(toc: List<TocEntry>, sentenceId: Int?): Int? {
        if (sentenceId == null) return null
        var low = 0
        var high = toc.size - 1
        var found: Int? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (toc[mid].sentenceId <= sentenceId) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** The entry and its ancestors, ancestors first. */
    fun path(toc: List<TocEntry>, index: Int): List<Int> {
        val path = ArrayDeque<Int>()
        var level = Int.MAX_VALUE
        for (k in index downTo 0) {
            if (toc[k].level < level) {
                path.addFirst(k)
                level = toc[k].level
            }
        }
        return path.toList()
    }

    /** The first sentence of the next entry with the same or a smaller level; null at the end of the book. */
    fun entryEndSentenceId(toc: List<TocEntry>, index: Int): Int? {
        val level = toc[index].level
        for (k in index + 1 until toc.size) if (toc[k].level <= level) return toc[k].sentenceId
        return null
    }

    fun location(toc: List<TocEntry>, sentenceId: Int?): TocLocation? {
        val index = currentEntryIndex(toc, sentenceId) ?: return null
        val path = path(toc, index)
        val at = path.indexOfFirst { isChapterLike(toc[it].title) }.coerceAtLeast(0)
        return TocLocation(path, path[at], path.getOrNull(at + 1))
    }

    /** How far into the entry's part the sentence is, 0–100, by sentence ids. */
    fun percentThrough(toc: List<TocEntry>, index: Int, sentenceId: Int, sentenceIdEnd: Int): Int {
        val start = toc[index].sentenceId
        val end = entryEndSentenceId(toc, index) ?: sentenceIdEnd
        if (end <= start) return 100
        return Math.round((sentenceId - start).toDouble() / (end - start) * 100).toInt().coerceIn(0, 100)
    }

    /** Next section: the first entry starting after the current sentence (file 07 §3.5). */
    fun nextSectionStart(toc: List<TocEntry>, sentenceId: Int): Int? = toc.firstOrNull { it.sentenceId > sentenceId }?.sentenceId

    /**
     * Previous section: the start of the current entry when inside it, else the previous entry's start. [firstPlayable]
     * maps an entry's sentence id to the first playable sentence at or after it.
     */
    fun previousSectionStart(toc: List<TocEntry>, sentenceId: Int, firstPlayable: (Int) -> Int?): Int? {
        val current = currentEntryIndex(toc, sentenceId) ?: return null
        val startOfCurrent = firstPlayable(toc[current].sentenceId)
        if (startOfCurrent != null && sentenceId > startOfCurrent) return startOfCurrent
        return if (current > 0) firstPlayable(toc[current - 1].sentenceId) else null
    }

    /** Part node: has a chapter-like child. Chapter node: chapter-like, or top-level and not a part. Indexes into [toc]. */
    fun chapterNodeIndexes(toc: List<TocEntry>): List<Int> = toc.indices.filter { index ->
        val entry = toc[index]
        val children = childIndexes(toc, index)
        val isPart = children.any { isChapterLike(toc[it].title) }
        val isTopLevel = path(toc, index).size == 1
        !isPart && (isChapterLike(entry.title) || isTopLevel)
    }

    private fun childIndexes(toc: List<TocEntry>, index: Int): List<Int> {
        val level = toc[index].level
        val children = mutableListOf<Int>()
        var childLevel: Int? = null
        for (k in index + 1 until toc.size) {
            if (toc[k].level <= level) break
            if (childLevel == null) childLevel = toc[k].level
            if (toc[k].level <= childLevel) {
                childLevel = toc[k].level
                children += k
            }
        }
        return children
    }

    /** One row of the outline search result, with where the match is in its title. */
    data class OutlineMatch(val index: Int, val matchStart: Int?, val matchEnd: Int?)

    /**
     * File 07 §3.4 search: a level-1 entry shows when it or any descendant matches; under it, matching level-2 entries
     * and level-2 entries with a matching level-3 child, then those level-3 matches. Case- and accent-insensitive.
     */
    fun searchOutline(toc: List<TocEntry>, query: String): List<OutlineMatch> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return toc.indices.map { OutlineMatch(it, null, null) }
        val matchAt = toc.map { entry -> fold(entry.title).indexOf(needle).takeIf { it >= 0 } }
        val result = mutableListOf<OutlineMatch>()
        val path = mutableListOf<Int>()
        val keep = BooleanArray(toc.size)
        toc.indices.forEach { index ->
            while (path.isNotEmpty() && toc[path.last()].level >= toc[index].level) path.removeAt(path.lastIndex)
            if (matchAt[index] != null) {
                keep[index] = true
                path.forEach { keep[it] = true }
            }
            path += index
        }
        toc.indices.filter { keep[it] }.forEach { index ->
            val start = matchAt[index]
            result += OutlineMatch(index, start, start?.plus(needle.length))
        }
        return result
    }

    /** Lower-case without accents; one char in, one char out, so match offsets stay valid in the original title. */
    private fun fold(text: String): String = buildString(text.length) {
        text.forEach { char ->
            val base = Normalizer.normalize(char.toString(), Normalizer.Form.NFD).firstOrNull() ?: char
            append(base.lowercaseChar())
        }
    }
}
