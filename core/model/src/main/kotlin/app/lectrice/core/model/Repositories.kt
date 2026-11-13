package app.lectrice.core.model

/** Every call may throw [LectriceError]; none runs on the main thread (the implementations switch to IO). */
interface LibraryRepository {
    suspend fun listRecent(limit: Int, offset: Int): SummaryPage

    /** The picked file's display name and size (for the import row and its errors); never logged. */
    suspend fun describe(source: PdfSource): PdfInfo

    /** Copies the picked file to a temp file and imports it (file 03). */
    suspend fun importPdf(source: PdfSource, info: PdfInfo): ImportResult

    suspend fun getDocument(documentId: String): Document

    /** The book's summary without opening it (to know whether opening re-cleans it). */
    suspend fun summary(documentId: String): DocumentSummary

    /** The ids the player reads, in order (core `playback_order`). */
    suspend fun playbackOrder(documentId: String, readSkipped: Boolean): List<Int>

    suspend fun savePosition(documentId: String, sentenceId: Int)

    suspend fun currentCleanerVersion(): Int

    suspend fun removeDocument(documentId: String)

    suspend fun restoreDocument(documentId: String): DocumentSummary

    suspend fun purgeRemoved(deleteExportsFor: List<String>)

    suspend fun clearDocumentAudio(documentId: String): Long

    suspend fun storageUsage(): StorageUsage

    /** Deletes every clip not in use; returns the bytes freed. */
    suspend fun clearAudioCache(): Long
}

/** A file the user picked or shared, as the platform hands it over (a content URI string). */
data class PdfSource(val uri: String)

data class PdfInfo(val displayName: String, val sizeBytes: Long?)

data class DocumentUsage(val documentId: String, val libraryBytes: Long, val cacheBytes: Long)

data class StorageUsage(
    val libraryDocuments: Int,
    val libraryBytes: Long,
    val cacheBytes: Long,
    val cacheCapBytes: Long,
    val exportFiles: Int,
    val exportBytes: Long,
    val exportPartsBytes: Long,
    val documents: List<DocumentUsage>,
)

interface VoiceRepository {
    suspend fun listVoices(): List<Voice>
}
