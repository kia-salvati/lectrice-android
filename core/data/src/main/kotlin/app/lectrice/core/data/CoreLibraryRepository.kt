package app.lectrice.core.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import app.lectrice.core.model.Document
import app.lectrice.core.model.DocumentSummary
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.ImportResult
import app.lectrice.core.model.IncomingRules
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.PdfInfo
import app.lectrice.core.model.PdfSource
import app.lectrice.core.model.StorageUsage
import app.lectrice.core.model.SummaryPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** The library through the core, plus the SAF copy of a picked file (file 03 §3.1). */
class CoreLibraryRepository(private val context: Context, private val core: RustCore) : LibraryRepository {
    override suspend fun listRecent(limit: Int, offset: Int): SummaryPage =
        core.call("list_recent") { Mappers.page(it.listRecent(limit.toUInt(), offset.toUInt())) }

    override suspend fun describe(source: PdfSource): PdfInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source.uri)
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return@withContext PdfInfo(IncomingRules.displayName(cursor.string(OpenableColumns.DISPLAY_NAME), DEFAULT_NAME), cursor.long(OpenableColumns.SIZE))
                }
            }
        } catch (error: RuntimeException) {
            // Another app's provider can answer with any exception or a cursor of another shape, not only SecurityException.
            throw LectriceError(ErrorName.FileUnreadable, error)
        }
        PdfInfo(IncomingRules.displayName(uri.lastPathSegment?.substringAfterLast('/'), DEFAULT_NAME), null)
    }

    override suspend fun importPdf(source: PdfSource, info: PdfInfo): ImportResult = withContext(Dispatchers.IO) {
        val size = info.sizeBytes
        if (size != null && size > MAX_BYTES) throw LectriceError(ErrorName.UploadTooLarge, bytes = size)
        if (size != null) {
            val needed = 2 * size + SPACE_MARGIN_BYTES
            if (allocatableBytes() < needed) throw LectriceError(ErrorName.StorageFull, bytes = needed)
        }
        val temp = File(context.cacheDir, "import/${UUID.randomUUID()}.pdf")
        try {
            copy(Uri.parse(source.uri), temp)
            val started = System.nanoTime()
            val result = core.blocking("import_pdf") { it.importPdf(temp.path, info.displayName) }
            Log.i(SCOPE, "imported doc=${result.summary.id.take(ID_LOG_CHARS)} pages=${result.summary.pageCount} sentences=${result.summary.sentenceCount} existing=${result.alreadyInLibrary} ms=${(System.nanoTime() - started) / NANOS_PER_MS}")
            ImportResult(Mappers.summary(result.summary), result.alreadyInLibrary)
        } finally {
            temp.delete()
        }
    }

    private fun copy(uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            throw LectriceError(ErrorName.FileUnreadable, error)
        } ?: throw LectriceError(ErrorName.FileUnreadable)
        input.use { stream ->
            try {
                target.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = try {
                            stream.read(buffer)
                        } catch (error: IOException) {
                            throw LectriceError(ErrorName.FileUnreadable, error)
                        }
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw LectriceError(ErrorName.UploadTooLarge)
                        output.write(buffer, 0, read)
                    }
                }
            } catch (error: IOException) {
                throw LectriceError(if (isOutOfSpace(error)) ErrorName.StorageFull else ErrorName.StorageFailed, error)
            }
        }
    }

    override suspend fun getDocument(documentId: String, markOpened: Boolean): Document =
        core.call("get_document") { Mappers.document(it.getDocument(documentId, markOpened)) }

    override suspend fun summary(documentId: String): DocumentSummary = core.call("document_summary") { Mappers.summary(it.documentSummary(documentId)) }

    override suspend fun playbackOrder(documentId: String, readSkipped: Boolean): List<Int> =
        core.call("playback_order") { core -> core.playbackOrder(documentId, readSkipped).map { it.toInt() } }

    override suspend fun savePosition(documentId: String, sentenceId: Int) {
        core.call("save_position") { it.savePosition(documentId, sentenceId.toUInt()) }
        Log.d(SCOPE, "position saved doc=${documentId.take(ID_LOG_CHARS)} sentence=$sentenceId")
    }

    override suspend fun currentCleanerVersion(): Int = core.call("current_cleaner_version") { it.currentCleanerVersion().toInt() }

    override suspend fun removeDocument(documentId: String) = core.call("remove_document") { it.removeDocument(documentId) }

    override suspend fun restoreDocument(documentId: String): DocumentSummary = core.call("restore_document") { Mappers.summary(it.restoreDocument(documentId)) }

    override suspend fun purgeRemoved(deleteExportsFor: List<String>) {
        val report = core.call("purge_removed") { it.purgeRemoved(deleteExportsFor) }
        Log.i(SCOPE, "purged documents=${report.documents} failed=${report.failed} clips=${report.clips}")
    }

    override suspend fun clearDocumentAudio(documentId: String): Long = core.call("clear_document_audio") { it.clearDocumentAudio(documentId).freedBytes.toLong() }

    override suspend fun storageUsage(): StorageUsage = core.call("storage_usage") { Mappers.storage(it.storageUsage()) }

    override suspend fun clearAudioCache(): Long = core.call("clear_audio_cache") { it.clearAudioCache().freedBytes.toLong() }

    private fun allocatableBytes(): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }

    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    companion object {
        private const val SCOPE = "Library"
        private const val DEFAULT_NAME = "document.pdf"
        private val MAX_BYTES = RustCore.IMPORT_MAX_MB.toLong() * 1024 * 1024
        /** The temp copy + the library's copy + its JSON (file 03 §3.1). */
        private const val SPACE_MARGIN_BYTES = 50L * 1_000_000
        private const val COPY_BUFFER_BYTES = 1 shl 16
        private const val ID_LOG_CHARS = 12
        private const val NANOS_PER_MS = 1_000_000

        fun isOutOfSpace(error: IOException): Boolean = error.message?.contains("ENOSPC") == true || error.message?.contains("No space left") == true
    }
}
