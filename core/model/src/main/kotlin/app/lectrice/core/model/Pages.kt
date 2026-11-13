package app.lectrice.core.model

/** Port of `frontend/src/utils/pages.ts`. */
object Pages {
    fun labelLookup(pages: List<PageLabel>): Map<Int, String> = pages.associate { it.index to it.label }

    /** The printed label of a PDF page, else its PDF number. */
    fun printedLabel(labels: Map<Int, String>, pageIndex: Int): String = labels[pageIndex] ?: pageIndex.toString()

    /**
     * Where page chips go: code and table blocks are one box, so their chip goes before the block; elsewhere before the
     * sentence where the page changes. The first content always gets one.
     */
    data class MarkerPlan(val beforeSentence: Set<Int>, val beforeBlock: Set<Int>)

    fun planPageMarkers(blocks: List<Block>): MarkerPlan {
        val beforeSentence = mutableSetOf<Int>()
        val beforeBlock = mutableSetOf<Int>()
        var previousPage: Int? = null
        for (block in blocks) {
            if (block.kind == BlockKind.Code || block.kind == BlockKind.Table) {
                if (block.pageIndex != previousPage) beforeBlock += block.id
                previousPage = block.pageIndex
                continue
            }
            for (sentence in block.sentences) {
                if (sentence.pageIndex != previousPage) beforeSentence += sentence.id
                previousPage = sentence.pageIndex
            }
        }
        return MarkerPlan(beforeSentence, beforeBlock)
    }
}
