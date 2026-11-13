package app.lectrice.core.model

/** One span of a one-line status. Only a part that [shrinks] gets the ellipsis. */
data class StatusPart(val text: String, val shrinks: Boolean = false)

/** Where a sentence is, in words (file 07 §3.3). Built once per sentence change from the document. */
data class PlaceLabels(
    /** Printed label of the sentence's page ("265", "xiii"). */
    val pageLabel: String,
    val pdfPage: Int,
    val pageCount: Int,
    /** The chapter's own number and name; null without a TOC or before its first entry. */
    val chapter: ChapterLabel?,
    val chapterTitle: String?,
    val sectionTitle: String?,
    /** Percent of the current chapter, when there is one. */
    val chapterPercent: Int?,
    /** Listening seconds left in the current chapter. */
    val chapterSecondsLeft: Double?,
) {
    /** Pill: "Ch. 7 · p. 265", "Preface · p. xi", "p. 12 of 18", "p. iii" (before the first entry of a book with a TOC). */
    fun pillLocation(hasToc: Boolean): List<StatusPart> = when {
        chapter?.number != null -> listOf(StatusPart("Ch. ${chapter.number}"), StatusPart("p. $pageLabel"))
        chapter != null -> listOf(StatusPart(chapter.name, shrinks = true), StatusPart("p. $pageLabel"))
        hasToc -> listOf(StatusPart("p. $pageLabel"))
        else -> listOf(StatusPart("p. $pageLabel of ${lastPageLabelOrCount()}"))
    }

    /** Continue card: "Ch. 7 · Long Documents · p. 265", "Preface · p. xi", "p. 265". */
    fun cardLocation(): List<StatusPart> = when {
        chapter?.number != null -> listOf(StatusPart("Ch. ${chapter.number}"), StatusPart(chapter.name, shrinks = true), StatusPart("p. $pageLabel"))
        chapter != null -> listOf(StatusPart(chapter.name, shrinks = true), StatusPart("p. $pageLabel"))
        else -> listOf(StatusPart("p. $pageLabel"))
    }

    /** Media artist (file 07 §3.3): "Ch. 7 · The Long Book", "Preface · …", "p. 12 · …". */
    fun mediaArtist(bookTitle: String): String = when {
        chapter?.number != null -> "Ch. ${chapter.number} · $bookTitle"
        chapter != null -> "${chapter.name} · $bookTitle"
        else -> "p. $pageLabel · $bookTitle"
    }

    /** Listen view progress: "38 % of chapter 7", "38 % of Preface"; null chapter → book percent given by the caller. */
    fun listenProgress(bookPercent: Int): String = when {
        chapter?.number != null && chapterPercent != null -> "$chapterPercent % of chapter ${chapter.number}"
        chapter != null && chapterPercent != null -> "$chapterPercent % of ${chapter.name}"
        else -> "$bookPercent % of the book"
    }

    /** Listen view top line 2: the section title, else the chapter title, else "p. 12". */
    fun listenTitle(): String = sectionTitle ?: chapterTitle ?: "p. $pageLabel"

    /** Player sheet chapter line 1: "7. Long Documents › 7.3 Tables …", no TOC: "Page 12 of 18 · PDF page 12". */
    fun chapterLineTitle(): String = when {
        chapterTitle != null && sectionTitle != null -> "$chapterTitle › $sectionTitle"
        chapterTitle != null -> chapterTitle
        else -> "Page $pageLabel of ${lastPageLabelOrCount()} · PDF page $pdfPage"
    }

    /** Player sheet chapter line 2: "38 % of this chapter · 9 min left in it · Page 265 · PDF page 279 of 589". */
    fun chapterLineDetail(): String {
        val page = "Page $pageLabel · PDF page $pdfPage of $pageCount"
        if (chapterPercent == null || chapterSecondsLeft == null) return page
        val left = Progress.formatTimeLeft(chapterSecondsLeft).removeSuffix(" left")
        return "$chapterPercent % of this chapter · $left left in it · $page"
    }

    private fun lastPageLabelOrCount(): String = pageCount.toString()

    companion object {
        fun of(document: Document, order: PlaybackOrder?, sentenceId: Int, rate: Float, pageLabels: Map<Int, String>): PlaceLabels {
            val sentence = order?.sentence(sentenceId) ?: document.blocks.firstNotNullOfOrNull { block ->
                block.sentences.firstOrNull { it.id == sentenceId } ?: block.cue?.takeIf { it.id == sentenceId }
            }
            val pdfPage = sentence?.pageIndex ?: 1
            val location = Toc.location(document.toc, sentenceId)
            val chapterEntry = location?.let { document.toc[it.chapterIndex] }
            val chapterEnd = location?.let { Toc.entryEndSentenceId(document.toc, it.chapterIndex) }
            return PlaceLabels(
                pageLabel = Pages.printedLabel(pageLabels, pdfPage),
                pdfPage = pdfPage,
                pageCount = document.pageCount,
                chapter = chapterEntry?.let { Toc.chapterLabel(it.title) },
                chapterTitle = chapterEntry?.title,
                sectionTitle = location?.sectionIndex?.let { document.toc[it].title },
                chapterPercent = location?.let { Toc.percentThrough(document.toc, it.chapterIndex, sentenceId, document.sentenceIdEnd) },
                chapterSecondsLeft = if (location != null && order != null) order.secondsBetween(sentenceId, chapterEnd, rate) else null,
            )
        }
    }
}
