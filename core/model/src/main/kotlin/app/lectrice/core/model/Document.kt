package app.lectrice.core.model

enum class BlockKind { Heading, Paragraph, ListItem, Note, Code, Table, Caption }

/** A sentence as shown (its spoken text stays in the core). Ids are global and sequential from 0, cues included. */
data class Sentence(val id: Int, val text: String, val pageIndex: Int)

data class Block(
    val id: Int,
    val kind: BlockKind,
    val level: Int?,
    val pageIndex: Int,
    /** False for code, tables and captions: shown, but only read when "Read code & tables aloud" is on. */
    val spoken: Boolean,
    /** The block's text as drawn, for code and tables (keeps line breaks); null for sentence blocks. */
    val text: String?,
    val sentences: List<Sentence>,
    /** The spoken stand-in ("Code example.", "Table.") when the block isn't read. */
    val cue: Sentence?,
)

data class TocEntry(val id: Int, val title: String, val level: Int, val sentenceId: Int, val pageIndex: Int)

data class PageLabel(val index: Int, val label: String)

data class Document(
    val id: String,
    val title: String,
    val pageCount: Int,
    val cleanerVersion: Int,
    val pages: List<PageLabel>,
    val toc: List<TocEntry>,
    val blocks: List<Block>,
    val lastSentenceId: Int?,
) {
    /** One past the highest sentence id, cues included: where the book's last part ends. */
    val sentenceIdEnd: Int by lazy {
        blocks.maxOfOrNull { block -> maxOf(block.cue?.id?.plus(1) ?: 0, block.sentences.maxOfOrNull { it.id + 1 } ?: 0) } ?: 0
    }
}

data class DocumentSummary(
    val id: String,
    val title: String,
    val sourceFileName: String,
    val pageCount: Int,
    val sentenceCount: Int,
    val cleanerVersion: Int,
    val addedAtEpochS: Long,
    val lastOpenedAtEpochS: Long,
    val lastSentenceId: Int?,
)

data class SummaryPage(val items: List<DocumentSummary>, val total: Int, val limit: Int, val offset: Int)

data class ImportResult(val summary: DocumentSummary, val alreadyInLibrary: Boolean)
