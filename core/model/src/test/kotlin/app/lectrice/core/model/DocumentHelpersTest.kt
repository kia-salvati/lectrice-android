package app.lectrice.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentHelpersTest {
    private fun s(id: Int, text: String, page: Int) = Sentence(id, text, page)

    /** Invented: a heading, a paragraph crossing pages 1→2, a code block with a cue on page 2, a paragraph on page 3. */
    private val document = Document(
        id = "d",
        title = "The Long Book",
        pageCount = 3,
        cleanerVersion = 3,
        pages = listOf(PageLabel(1, "i"), PageLabel(2, "ii")),
        toc = listOf(TocEntry(0, "1. Opening", 1, 0, 1)),
        blocks = listOf(
            Block(0, BlockKind.Heading, 1, 1, true, null, listOf(s(0, "1. Opening", 1)), null),
            Block(1, BlockKind.Paragraph, null, 1, true, null, listOf(s(1, "One two three.", 1), s(2, "Four five.", 2)), null),
            Block(2, BlockKind.Code, null, 2, false, "let x = 1", listOf(s(3, "let x = 1", 2)), s(4, "Code example.", 2)),
            Block(3, BlockKind.Paragraph, null, 3, true, null, listOf(s(5, "Six seven eight.", 3)), null),
        ),
        lastSentenceId = 2,
    )

    @Test
    fun pageMarkers() {
        val plan = Pages.planPageMarkers(document.blocks)
        assertEquals(setOf(0, 2, 5), plan.beforeSentence)
        assertEquals(emptySet<Int>(), plan.beforeBlock)
        assertEquals("ii", Pages.printedLabel(Pages.labelLookup(document.pages), 2))
        assertEquals("3", Pages.printedLabel(Pages.labelLookup(document.pages), 3))
    }

    @Test
    fun playbackOrderLookups() {
        val order = PlaybackOrder(listOf(0, 1, 2, 4, 5), document)
        assertEquals(4, order.resolvePlayable(3))
        assertEquals(2, order.startingSentenceId(2))
        assertEquals(0, order.startingSentenceId(null))
        assertEquals(4, order.stepFrom(2, 1))
        assertEquals(2, order.blockIndexOf(4))
        val seconds = order.secondsLeft(2, 1f)
        assertEquals(("Code example.".length + "Six seven eight.".length) / Progress.CHARS_PER_SECOND, seconds, 1e-9)
        assertEquals(6, document.sentenceIdEnd)
    }

    @Test
    fun finishedAtTheLastSentenceReadOrPastIt() {
        val order = PlaybackOrder(listOf(0, 1, 2, 4), document)
        assertEquals(listOf(false, true, true), listOf(2, 4, 5).map(order::isFinishedAt))
        assertEquals(false, order.isFinishedAt(null))
        assertEquals(listOf(false, true, false), listOf(99, 100, null).map(Progress::isFinished))
    }

    @Test
    fun placeLabels() {
        val order = PlaybackOrder(listOf(0, 1, 2, 4, 5), document)
        val labels = PlaceLabels.of(document, order, 2, 1f, Pages.labelLookup(document.pages))
        assertEquals(listOf(StatusPart("Ch. 1"), StatusPart("p. ii")), labels.pillLocation(hasToc = true))
        assertEquals("Ch. 1 · The Long Book", labels.mediaArtist("The Long Book"))
        assertEquals("33 % of chapter 1", labels.listenProgress(50))
    }
}
