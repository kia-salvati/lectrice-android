package app.lectrice.core.data

import app.lectrice.core.model.Block
import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.Document
import app.lectrice.core.model.DocumentSummary
import app.lectrice.core.model.DocumentUsage
import app.lectrice.core.model.PageLabel
import app.lectrice.core.model.Sentence
import app.lectrice.core.model.StorageUsage
import app.lectrice.core.model.SummaryPage
import app.lectrice.core.model.TocEntry
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceGender
import app.lectrice.core.model.VoiceLanguage
import uniffi.lectrice_ffi.BlockRecord
import uniffi.lectrice_ffi.DocumentRecord
import uniffi.lectrice_ffi.DocumentSummaryRecord
import uniffi.lectrice_ffi.SentenceRecord
import uniffi.lectrice_ffi.StorageUsageRecord
import uniffi.lectrice_ffi.VoiceRecord
import uniffi.lectrice_ffi.BlockKind as RecordKind
import uniffi.lectrice_ffi.SummaryPage as RecordPage
import uniffi.lectrice_ffi.VoiceGender as RecordGender
import uniffi.lectrice_ffi.VoiceLanguage as RecordLanguage

/** uniffi records → core:model types. The only place that knows both. */
internal object Mappers {
    fun document(record: DocumentRecord) = Document(
        id = record.id,
        title = record.title,
        pageCount = record.pageCount.toInt(),
        cleanerVersion = record.cleanerVersion.toInt(),
        pages = record.pages.map { PageLabel(it.index.toInt(), it.label) },
        toc = record.toc.map { TocEntry(it.id.toInt(), it.title, it.level.toInt(), it.sentenceId.toInt(), it.pageIndex.toInt()) },
        blocks = record.blocks.map(::block),
        lastSentenceId = record.lastSentenceId?.toInt(),
    )

    private fun block(record: BlockRecord) = Block(
        id = record.id.toInt(),
        kind = kind(record.kind),
        level = record.level?.toInt(),
        pageIndex = record.pageIndex.toInt(),
        spoken = record.spoken,
        text = record.text,
        sentences = record.sentences.map(::sentence),
        cue = record.cue?.let(::sentence),
    )

    private fun sentence(record: SentenceRecord) = Sentence(record.id.toInt(), record.text, record.pageIndex.toInt())

    private fun kind(kind: RecordKind) = when (kind) {
        RecordKind.HEADING -> BlockKind.Heading
        RecordKind.PARAGRAPH -> BlockKind.Paragraph
        RecordKind.LIST_ITEM -> BlockKind.ListItem
        RecordKind.NOTE -> BlockKind.Note
        RecordKind.CODE -> BlockKind.Code
        RecordKind.TABLE -> BlockKind.Table
        RecordKind.CAPTION -> BlockKind.Caption
    }

    fun summary(record: DocumentSummaryRecord) = DocumentSummary(
        id = record.id,
        title = record.title,
        sourceFileName = record.sourceFileName,
        pageCount = record.pageCount.toInt(),
        sentenceCount = record.sentenceCount.toInt(),
        cleanerVersion = record.cleanerVersion.toInt(),
        addedAtEpochS = record.addedAtEpochS,
        lastOpenedAtEpochS = record.lastOpenedAtEpochS,
        lastSentenceId = record.lastSentenceId?.toInt(),
    )

    fun page(record: RecordPage) = SummaryPage(record.items.map(::summary), record.total.toInt(), record.limit.toInt(), record.offset.toInt())

    fun storage(record: StorageUsageRecord) = StorageUsage(
        libraryDocuments = record.libraryDocuments.toInt(),
        libraryBytes = record.libraryBytes.toLong(),
        cacheBytes = record.cacheBytes.toLong(),
        cacheCapBytes = record.cacheCapBytes.toLong(),
        exportFiles = record.exportFiles.toInt(),
        exportBytes = record.exportBytes.toLong(),
        exportPartsBytes = record.exportPartsBytes.toLong(),
        documents = record.documents.map { DocumentUsage(it.documentId, it.libraryBytes.toLong(), it.cacheBytes.toLong()) },
    )

    fun voice(record: VoiceRecord) = Voice(
        id = record.id,
        name = record.name,
        language = if (record.language == RecordLanguage.EN_US) VoiceLanguage.EnUs else VoiceLanguage.EnGb,
        gender = if (record.gender == RecordGender.FEMALE) VoiceGender.Female else VoiceGender.Male,
    )
}
