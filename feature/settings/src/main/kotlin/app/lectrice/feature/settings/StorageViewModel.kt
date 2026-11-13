package app.lectrice.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.Covers
import app.lectrice.core.model.ExportRepository
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.RemovalController
import app.lectrice.core.model.Sizes
import app.lectrice.core.model.VoiceModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The platform numbers the Storage screen needs (file 20 §3.5). */
interface StoragePlatform {
    /** Android's "Total" for our package (`StorageStats.appBytes + dataBytes`). */
    suspend fun totalBytes(): Long

    suspend fun freeBytes(): Long

    /** Bytes of everything else counted under the app: the APK, native libraries, settings. */
    suspend fun appBytes(): Long

    fun openPhoneStorage(requestedBytes: Long?)

    fun share(export: AudioExport)

    fun open(export: AudioExport)

    /** "Delete all Lectrice data": the app is left as freshly installed (the process ends). */
    fun deleteAllData()
}

data class StorageSegment(val label: String, val bytes: Long, val hue: Int)

data class StorageModelRow(val id: String, val name: String, val meta: String)

data class StorageBookRow(val documentId: String, val title: String, val initials: String, val hue: Int, val libraryBytes: Long, val audioBytes: Long, val meta: String)

data class StorageExportRow(val id: String, val title: String, val meta: String, val inAudiobooks: Boolean, val unfinished: Boolean, val export: AudioExport)

data class StorageUiState(
    val loading: Boolean = true,
    /** "1.2 GB used by Lectrice" */
    val totalLine: String = "",
    /** "23.4 GB free on this phone" */
    val freeLine: String = "",
    val segments: List<StorageSegment> = emptyList(),
    val models: List<StorageModelRow> = emptyList(),
    /** "3 books · 48 MB, plus 212 MB of audio" */
    val booksHeader: String = "",
    val books: List<StorageBookRow> = emptyList(),
    /** "312 MB of 1 GB" + fraction for the bar. */
    val cacheLine: String = "",
    val cacheFraction: Float = 0f,
    val exports: List<StorageExportRow> = emptyList(),
    /** "Free up about 120 MB to export this book." (opened from an export that didn't fit). */
    val requestedBytes: Long? = null,
    /** "Cleared 312 MB." */
    val message: String? = null,
    val confirmDeleteAll: Boolean = false,
    val confirmDeleteExport: StorageExportRow? = null,
)

interface StorageActions {
    fun clearAudioCache()
    fun openModel(modelId: String)
    fun removeBook(documentId: String)
    fun clearBookAudio(documentId: String)
    fun openExport(row: StorageExportRow)
    fun shareExport(row: StorageExportRow)
    fun saveExport(row: StorageExportRow)
    fun askDeleteExport(row: StorageExportRow)
    fun confirmDeleteExport()
    fun openPhoneStorage()
    fun askDeleteAll()
    fun confirmDeleteAll()
    fun dismiss()
    fun messageShown()
}

interface StorageNavigation {
    fun openModelPage()
    /** The book sheet's remove flow (file 21) runs in the Library's components; here it confirms and removes directly. */
    fun close()
}

/** T3 (file 20 §3.5). */
class StorageViewModel(
    private val library: LibraryRepository,
    private val exports: ExportRepository,
    private val models: VoiceModelRepository,
    private val removal: RemovalController,
    private val platform: StoragePlatform,
    private val navigation: StorageNavigation,
    requestedBytes: Long?,
) : ViewModel(), StorageActions {
    private val state = MutableStateFlow(StorageUiState(requestedBytes = requestedBytes))
    val uiState: StateFlow<StorageUiState> = state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val usage = runCatching { library.storageUsage() }.getOrNull()
            runCatching { exports.refresh() }
            val titles = runCatching { library.listRecent(MAX_BOOKS, 0).items.associate { it.id to it.title } }.getOrDefault(emptyMap())
            val modelRows = models.statuses.value.filter { it.state == ModelInstallState.Installed }.map {
                StorageModelRow(it.model.id, it.model.name, Sizes.format(it.model.installedBytes) + if (it.inUse) " · in use" else "")
            }
            val modelBytes = models.statuses.value.filter { it.state == ModelInstallState.Installed }.sumOf { it.model.installedBytes }
            val books = usage?.documents.orEmpty().mapNotNull { book ->
                val title = titles[book.documentId] ?: return@mapNotNull null
                StorageBookRow(book.documentId, title, Covers.initials(title), Covers.hue(title), book.libraryBytes, book.cacheBytes, "PDF and text ${Sizes.format(book.libraryBytes)} · audio ${Sizes.format(book.cacheBytes)}")
            }
            val exportRows = exports.exports.value.filter { it.status == ExportStatus.Done || it.isUnfinished }.map(::exportRow)
            val total = runCatching { platform.totalBytes() }.getOrDefault(0)
            val free = runCatching { platform.freeBytes() }.getOrDefault(0)
            val libraryBytes = usage?.libraryBytes ?: 0
            val cacheBytes = usage?.cacheBytes ?: 0
            val exportBytes = (usage?.exportBytes ?: 0) + (usage?.exportPartsBytes ?: 0)
            val other = (total - modelBytes - libraryBytes - cacheBytes - exportBytes).coerceAtLeast(0)
            state.update {
                it.copy(
                    loading = false,
                    totalLine = "${Sizes.format(total)} used by Lectrice",
                    freeLine = "${Sizes.format(free)} free on this phone",
                    segments = listOf(
                        StorageSegment("Voice models", modelBytes, 0),
                        StorageSegment("Books", libraryBytes, 1),
                        StorageSegment("Audio cache", cacheBytes, 2),
                        StorageSegment("Exports", exportBytes, 3),
                        StorageSegment("App & other", other, 4),
                    ),
                    models = modelRows,
                    booksHeader = "${books.size} ${if (books.size == 1) "book" else "books"} · ${Sizes.format(libraryBytes)}, plus ${Sizes.format(cacheBytes)} of audio",
                    books = books.sortedByDescending { book -> book.libraryBytes + book.audioBytes },
                    cacheLine = "${Sizes.format(cacheBytes)} of ${Sizes.format(usage?.cacheCapBytes ?: 0)}",
                    cacheFraction = if ((usage?.cacheCapBytes ?: 0) > 0) cacheBytes.toFloat() / usage!!.cacheCapBytes else 0f,
                    exports = exportRows,
                )
            }
        }
    }

    private fun exportRow(export: AudioExport): StorageExportRow {
        val voice = export.voiceId.substringAfter('_').replaceFirstChar(Char::uppercase)
        val place = if (export.sharedUri != null) "In Audiobooks" else "In Lectrice"
        val meta = if (export.isUnfinished) {
            "Exporting · ${export.percent} % · ${Sizes.format(exports.partsBytes(export.id))} so far"
        } else {
            "$voice · ${export.speed.label} · ${Sizes.format(export.fileBytes ?: 0)} · $place"
        }
        return StorageExportRow(export.id, export.title, meta, export.sharedUri != null, export.isUnfinished, export)
    }

    override fun clearAudioCache() {
        viewModelScope.launch {
            runCatching { library.clearAudioCache() }.onSuccess { cleared -> state.update { it.copy(message = "Cleared ${Sizes.format(cleared)}.") } }
            refresh()
        }
    }

    override fun openModel(modelId: String) = navigation.openModelPage()

    override fun removeBook(documentId: String) {
        val book = state.value.books.firstOrNull { it.documentId == documentId } ?: return
        viewModelScope.launch {
            try {
                removal.remove(documentId, book.title, deleteExports = false)
                removal.purgeNow()
            } catch (error: LectriceError) {
                state.update { it.copy(message = "Something went wrong.") }
            }
            refresh()
        }
    }

    override fun clearBookAudio(documentId: String) {
        viewModelScope.launch {
            runCatching { library.clearDocumentAudio(documentId) }
            refresh()
        }
    }

    override fun openExport(row: StorageExportRow) = platform.open(row.export)

    override fun shareExport(row: StorageExportRow) = platform.share(row.export)

    override fun saveExport(row: StorageExportRow) {
        viewModelScope.launch {
            try {
                exports.saveToAudiobooks(row.id)
                state.update { it.copy(message = "Saved to Audiobooks/Lectrice.") }
            } catch (error: LectriceError) {
                state.update { it.copy(message = "Your phone is out of space.") }
            }
            refresh()
        }
    }

    override fun askDeleteExport(row: StorageExportRow) = state.update { it.copy(confirmDeleteExport = row) }

    override fun confirmDeleteExport() {
        val row = state.value.confirmDeleteExport ?: return
        state.update { it.copy(confirmDeleteExport = null) }
        viewModelScope.launch {
            runCatching { if (row.unfinished) exports.cancel(row.id) else exports.delete(row.id) }
            refresh()
        }
    }

    override fun openPhoneStorage() = platform.openPhoneStorage(state.value.requestedBytes)

    override fun askDeleteAll() = state.update { it.copy(confirmDeleteAll = true) }

    override fun confirmDeleteAll() = platform.deleteAllData()

    override fun dismiss() = state.update { it.copy(confirmDeleteAll = false, confirmDeleteExport = null) }

    override fun messageShown() = state.update { it.copy(message = null) }

    private companion object {
        const val MAX_BOOKS = 100
    }
}
