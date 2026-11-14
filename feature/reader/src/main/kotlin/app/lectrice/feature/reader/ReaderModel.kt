package app.lectrice.feature.reader

import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.Document
import app.lectrice.core.model.Pages
import app.lectrice.core.model.Toc

/** Document → what the reader draws (file 04 §3.2–3.3). Pure; runs once per open, off the main thread. */
internal object ReaderModel {
    private const val MAX_HEADING_LEVEL = 3

    fun blocks(document: Document, pageLabels: Map<Int, String>): List<ReaderBlock> {
        val plan = Pages.planPageMarkers(document.blocks)
        fun chip(pageIndex: Int) = PageChip(Pages.printedLabel(pageLabels, pageIndex), pageIndex)
        return document.blocks.mapIndexed { index, block ->
            val isBox = block.kind == BlockKind.Code || block.kind == BlockKind.Table
            ReaderBlock(
                index = index,
                id = block.id,
                kind = block.kind,
                level = (block.level ?: 1).coerceIn(1, MAX_HEADING_LEVEL),
                sentences = block.sentences.map { ReaderSentence(it.id, it.text) },
                boxText = if (isBox) block.text ?: block.sentences.joinToString(" ") { it.text } else null,
                cue = block.cue?.let { ReaderSentence(it.id, it.text) },
                chipBeforeBlock = if (block.id in plan.beforeBlock) chip(block.pageIndex) else null,
                chipsBeforeSentence = if (isBox) emptyMap() else block.sentences.filter { it.id in plan.beforeSentence }.associate { it.id to chip(it.pageIndex) },
            )
        }
    }

    fun outline(document: Document, pageLabels: Map<Int, String>, currentSentenceId: Int?): List<OutlineRow> {
        val path = Toc.location(document.toc, currentSentenceId)?.path.orEmpty().toSet()
        return document.toc.mapIndexed { index, entry ->
            OutlineRow(index, entry.title, entry.level, Pages.printedLabel(pageLabels, entry.pageIndex), entry.sentenceId, index in path)
        }
    }
}
