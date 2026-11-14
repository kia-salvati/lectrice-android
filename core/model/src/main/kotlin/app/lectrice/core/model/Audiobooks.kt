package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * Where a book's audio comes from (file 23 §3.4; file 24 §3.12 adds producers inside [Generated], not a new source).
 * [Generated]: sentence clips made on the phone (cache → engine). [Audiobook]: slices of the book's linked M4B.
 */
enum class AudioSource { Generated, Audiobook }

/** How closely an audiobook file follows the text (file 23 §3.3). */
enum class AudiobookMode { Sentences, Chapters, Unaligned }

enum class AudiobookKind { OwnExport, Imported }

/** A book's linked audiobook (file 23 §3.6). [uri] is a `content://` URI or an absolute path in app storage. */
data class AudiobookLink(
    val documentId: String,
    val uri: String,
    val kind: AudiobookKind,
    val mode: AudiobookMode,
    val remapped: Boolean,
    val matchedPercent: Int,
    val fileSpeedPercent: Int,
    val durationMs: Long,
    val voiceId: String?,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val linkedAt: Long,
)

/** A sentence's slice of the file, on the file's timeline. */
data class TimedSentence(val sentenceId: Int, val startMs: Long, val endMs: Long)

data class AudiobookTimeline(val mode: AudiobookMode, val fileSpeedPercent: Int, val durationMs: Long, val sentences: List<TimedSentence>)

/** A file before it is tied to a book (the import flow). */
data class AudiobookFileInfo(val mapDocumentId: String?, val durationMs: Long, val chapterCount: Int, val voiceId: String?, val speedPercent: Int?)

/** What importing an M4B led to (file 23 §2 B/C). */
sealed interface AudiobookImport {
    data class Linked(val link: AudiobookLink, val title: String) : AudiobookImport

    /** Its map names a book that isn't in the library. */
    data object BookMissing : AudiobookImport

    /** No Lectrice map: other apps' audiobooks are not playable in this version (file 23 §9, built later). */
    data object Foreign : AudiobookImport
}

interface AudiobookRepository {
    /** Links by document id. */
    val links: StateFlow<Map<String, AudiobookLink>>

    /** Reads the file (throws [LectriceError]: `AudiobookUnreadable`, `FileUnreadable`). */
    suspend fun inspect(uri: String): AudiobookFileInfo

    /** Ties the file to the book and caches its timeline. `AudiobookOtherBook` when its map names another book. */
    suspend fun link(documentId: String, uri: String, kind: AudiobookKind): AudiobookLink

    /**
     * An M4B from outside: linked to its book when that book is in the library. [fromAnotherApp]: it came by "Open with" /
     * "Share" and not from the app's own picker, so the app keeps its own copy and never reads the sender's again
     * (`UploadTooLarge` / `StorageFull` when the copy can't be made).
     */
    suspend fun import(uri: String, fromAnotherApp: Boolean): AudiobookImport

    suspend fun unlink(documentId: String)

    /** The book's timeline in sentence mode, or null (no link, another mode, or the file is gone: see [fileMissing]). */
    suspend fun timeline(documentId: String): AudiobookTimeline?

    /** The linked file can't be read any more (moved, deleted, permission lost). */
    fun fileMissing(documentId: String): Boolean
}
