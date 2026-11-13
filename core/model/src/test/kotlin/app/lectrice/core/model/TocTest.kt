package app.lectrice.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ports of the web's toc tests (frontend/tests/utils.test.ts) with invented titles. */
class TocTest {
    private fun entry(id: Int, title: String, level: Int, sentenceId: Int) = TocEntry(id, title, level, sentenceId, sentenceId + 1)

    /** Parts › chapters › sections over sentences 0–99. */
    private val partsToc = listOf(
        entry(0, "Preface", 1, 2),
        entry(1, "PART I Getting Started", 1, 10),
        entry(2, "Chapter 1 Why Gardens Grow", 2, 12),
        entry(3, "The Soil?", 3, 15),
        entry(4, "Conclusion", 3, 20),
        entry(5, "Chapter 2 A Tale of Two Seeds", 2, 30),
        entry(6, "PART II Tools of the Trade", 1, 50),
        entry(7, "Chapter 4 Careful Pruning", 2, 52),
        entry(8, "Proof", 3, 60),
    )
    private val sentences = 100

    @Test
    fun currentEntryPathAndEnd() {
        assertNull(Toc.currentEntryIndex(partsToc, null))
        assertNull(Toc.currentEntryIndex(partsToc, 1))
        assertEquals(0, Toc.currentEntryIndex(partsToc, 2))
        assertEquals(3, Toc.currentEntryIndex(partsToc, 17))
        assertEquals(8, Toc.currentEntryIndex(partsToc, 99))
        assertEquals(listOf(6, 7, 8), Toc.path(partsToc, 8))
        assertEquals(listOf(1, 5), Toc.path(partsToc, 5))
        assertEquals(listOf(0), Toc.path(partsToc, 0))
        assertEquals(10, Toc.entryEndSentenceId(partsToc, 0))
        assertEquals(30, Toc.entryEndSentenceId(partsToc, 2))
        assertEquals(50, Toc.entryEndSentenceId(partsToc, 5))
        assertNull(Toc.entryEndSentenceId(partsToc, 8))
    }

    @Test
    fun locationPicksTheChapterLikeTitle() {
        fun titles(toc: List<TocEntry>, sentenceId: Int) = Toc.location(toc, sentenceId)?.let { toc[it.chapterIndex].title to it.sectionIndex?.let { s -> toc[s].title } }
        assertNull(titles(partsToc, 0))
        assertEquals(TocLocation(listOf(6, 7, 8), 7, 8), Toc.location(partsToc, 65))
        assertEquals("Chapter 4 Careful Pruning" to "Proof", titles(partsToc, 65))
        assertEquals("PART I Getting Started" to null, titles(partsToc, 11))
        assertEquals("Preface" to null, titles(partsToc, 5))
        val numbered = listOf(entry(0, "2: How Rivers Bend", 1, 0), entry(1, "Banks", 2, 8), entry(2, "3: Deltas", 1, 20))
        assertEquals("2: How Rivers Bend" to "Banks", titles(numbered, 9))
        val parts = listOf(entry(0, "Chapter 1. Opening", 1, 0), entry(1, "Part I. Small Things", 1, 20), entry(2, "Motives", 2, 22), entry(3, "Chapter 2. The Helper", 2, 30))
        assertEquals("Part I. Small Things" to "Motives", titles(parts, 25))
        assertEquals("Chapter 2. The Helper" to null, titles(parts, 31))
        val longBook = listOf(entry(0, "7. Long Documents", 1, 0), entry(1, "7.3 Tables of contents that tell the truth", 2, 10), entry(2, "Why it matters", 3, 12))
        assertEquals("7. Long Documents" to "7.3 Tables of contents that tell the truth", titles(longBook, 13))
    }

    @Test
    fun chapterLabels() {
        assertEquals(ChapterLabel("4", "Careful Pruning"), Toc.chapterLabel("Chapter 4 Careful Pruning"))
        assertEquals(ChapterLabel("2", "The Helper"), Toc.chapterLabel("Chapter 2. The Helper"))
        assertEquals(ChapterLabel("12", "Harvest"), Toc.chapterLabel("CHAPTER 12: Harvest"))
        assertEquals(ChapterLabel("IV", "Proof"), Toc.chapterLabel("Ch. IV Proof"))
        assertEquals(ChapterLabel("4", "Working with Clay"), Toc.chapterLabel("4: Working with Clay"))
        assertEquals(ChapterLabel("7", "Long Documents"), Toc.chapterLabel("7. Long Documents"))
        listOf("Preface", "PART II Tools of the Trade", "Appendix A Old Maps", "7.3 Tables of contents", "20 Famous Bridges", "10 years ago, a river moved").forEach {
            assertEquals(it, ChapterLabel(null, it), Toc.chapterLabel(it))
        }
    }

    @Test
    fun percentThroughAnEntry() {
        assertEquals(0, Toc.percentThrough(partsToc, 2, 12, sentences))
        assertEquals(50, Toc.percentThrough(partsToc, 2, 21, sentences))
        assertEquals(50, Toc.percentThrough(partsToc, 8, 80, sentences))
        assertEquals(100, Toc.percentThrough(partsToc, 2, 40, sentences))
    }

    @Test
    fun sectionJumps() {
        val identity: (Int) -> Int? = { it }
        assertEquals(15, Toc.nextSectionStart(partsToc, 12))
        assertNull(Toc.nextSectionStart(partsToc, 60))
        assertNull(Toc.previousSectionStart(partsToc, 1, identity))
        assertEquals(15, Toc.previousSectionStart(partsToc, 17, identity))
        assertEquals(12, Toc.previousSectionStart(partsToc, 15, identity))
        assertNull(Toc.previousSectionStart(partsToc, 2, identity))
    }

    @Test
    fun outlineSearchKeepsAncestorsAndMarksMatches() {
        val result = Toc.searchOutline(partsToc, "PROOF")
        assertEquals(listOf(6, 7, 8), result.map { it.index })
        assertEquals(Toc.OutlineMatch(8, 0, 5), result.last())
        assertNull(result.first().matchStart)
        assertEquals(partsToc.size, Toc.searchOutline(partsToc, "  ").size)
        val accents = listOf(entry(0, "Café Notes", 1, 0))
        assertEquals(listOf(Toc.OutlineMatch(0, 0, 4)), Toc.searchOutline(accents, "cafe"))
    }

    @Test
    fun chapterNodesSkipParts() {
        assertEquals(listOf(0, 2, 5, 7), Toc.chapterNodeIndexes(partsToc))
    }
}
