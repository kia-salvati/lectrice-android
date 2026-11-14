package app.lectrice.core.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import app.lectrice.core.model.AudiobookFileInfo
import app.lectrice.core.model.AudiobookImport
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookLink
import app.lectrice.core.model.AudiobookMode
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.AudiobookTimeline
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.IncomingRules
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.TimedSentence
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.lectrice_ffi.AudiobookModeRecord
import uniffi.lectrice_ffi.AudiobookTimelineRecord
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * File 23 §3.6: links in `filesDir/lectrice/audiobooks.json`, timelines cached in `audiobook-maps/<doc>.json` (ids and
 * times only). The core reads the file through `/proc/self/fd/N`, so a content URI needs no copy. A file picked in the
 * app keeps a persisted read permission; a file another app sent is copied into app storage (its sender is never read
 * again); the app's own exports are read by path.
 */
class AndroidAudiobookRepository(private val context: Context, private val core: RustCore) : AudiobookRepository {
    private val directory = File(context.filesDir, "lectrice")
    private val linksFile = File(directory, LINKS_FILE)
    private val mapsDir = File(directory, MAPS_DIR)
    private val copiesDir = File(directory, COPIES_DIR)
    private val mutex = Mutex()
    private val state = MutableStateFlow(readLinks())
    private val timelines = ConcurrentHashMap<String, AudiobookTimeline>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override val links: StateFlow<Map<String, AudiobookLink>> = state.asStateFlow()

    override suspend fun inspect(uri: String): AudiobookFileInfo = withFile(uri) { path ->
        val file = core.blocking("inspect_audiobook") { it.inspectAudiobook(path) }
        AudiobookFileInfo(file.mapDocumentId, file.durationMs.toLong(), file.chapterCount.toInt(), file.voiceId, file.speedPercent?.toInt())
    }

    override suspend fun link(documentId: String, uri: String, kind: AudiobookKind): AudiobookLink = link(documentId, uri, kind, ownCopy = false)

    /**
     * [ownCopy]: the file came from another app, so it is copied and the sender's URI is never read again. A picked file
     * is copied too when its read permission can't be kept (the link would break at the next start). A copy is matched
     * after it is made: what was checked is what gets played.
     */
    private suspend fun link(documentId: String, uri: String, kind: AudiobookKind, ownCopy: Boolean): AudiobookLink = mutex.withLock {
        val (rawName, _, _) = withContext(Dispatchers.IO) { describe(uri) }
        val name = IncomingRules.displayName(rawName, DEFAULT_NAME)
        val staged = if (kind == AudiobookKind.Imported && (ownCopy || !keepPermission(uri))) withContext(Dispatchers.IO) { copyIn(documentId, uri) } else null
        val record = try {
            withFile(staged?.path ?: uri) { path -> core.blocking("match_audiobook") { it.matchAudiobook(path, documentId) } }
        } catch (error: LectriceError) {
            staged?.delete()
            throw error
        }
        val kept = if (staged != null) withContext(Dispatchers.IO) { keepCopy(documentId, staged) } else uri
        val (_, size, modified) = withContext(Dispatchers.IO) { describe(kept) }
        val link = AudiobookLink(
            documentId = documentId,
            uri = kept,
            kind = kind,
            mode = mode(record.mode),
            remapped = record.remapped,
            matchedPercent = record.matchedPercent.toInt(),
            fileSpeedPercent = record.fileSpeedPercent.toInt(),
            durationMs = record.durationMs.toLong(),
            voiceId = record.voiceId,
            displayName = name,
            sizeBytes = size,
            lastModified = modified,
            linkedAt = System.currentTimeMillis(),
        )
        val timeline = timeline(record)
        withContext(Dispatchers.IO) {
            writeTimeline(documentId, timeline)
            writeLinks(state.value + (documentId to link))
        }
        timelines[documentId] = timeline
        missing -= documentId
        state.value = state.value + (documentId to link)
        Log.i(SCOPE, "linked doc=$documentId kind=$kind mode=${link.mode} matched=${link.matchedPercent} sentences=${timeline.sentences.size}")
        link
    }

    override suspend fun import(uri: String, fromAnotherApp: Boolean): AudiobookImport {
        val info = inspect(uri)
        val documentId = info.mapDocumentId ?: return AudiobookImport.Foreign.also { Log.i(SCOPE, "import: no Lectrice map") }
        val summary = suspendRunCatching { core.call("document_summary") { it.documentSummary(documentId) } }.getOrNull()
            ?: return AudiobookImport.BookMissing.also { Log.i(SCOPE, "import: book not in the library") }
        return AudiobookImport.Linked(link(documentId, uri, AudiobookKind.Imported, ownCopy = fromAnotherApp), summary.title)
    }

    override suspend fun unlink(documentId: String) = mutex.withLock {
        val link = state.value[documentId] ?: return@withLock
        withContext(Dispatchers.IO) {
            File(mapsDir, "$documentId.json").delete()
            writeLinks(state.value - documentId)
        }
        if (link.kind == AudiobookKind.Imported) releasePermission(link.uri)
        withContext(Dispatchers.IO) { File(copiesDir, "$documentId.m4b").delete() }
        timelines -= documentId
        state.value = state.value - documentId
        Log.i(SCOPE, "unlinked doc=$documentId")
    }

    override suspend fun timeline(documentId: String): AudiobookTimeline? {
        val link = state.value[documentId] ?: return null
        val described = withContext(Dispatchers.IO) { suspendRunCatching { describe(link.uri) }.getOrNull() }
        if (described == null) {
            missing += documentId
            Log.i(SCOPE, "audiobook file missing doc=$documentId")
            return null
        }
        missing -= documentId
        val (_, size, modified) = described
        if (size != link.sizeBytes || modified != link.lastModified) {
            Log.i(SCOPE, "audiobook changed, matching again doc=$documentId")
            timelines -= documentId
            return suspendRunCatching { link(documentId, link.uri, link.kind) }.getOrNull()?.let { timelines[documentId] }
        }
        return timelines[documentId] ?: withContext(Dispatchers.IO) { readTimeline(documentId) }?.also { timelines[documentId] = it }
    }

    override fun fileMissing(documentId: String): Boolean = documentId in missing

    // ---- Files ------------------------------------------------------------------------------------------------------

    /** Opens [uri] (a content URI or a path) and hands the core a path it can read. */
    private suspend fun <T> withFile(uri: String, block: (String) -> T): T = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == null || parsed.scheme == ContentResolver.SCHEME_FILE) {
            val path = parsed.path ?: uri
            if (!File(path).isFile) throw LectriceError(ErrorName.FileUnreadable)
            return@withContext block(path)
        }
        val descriptor = try {
            context.contentResolver.openFileDescriptor(parsed, "r")
        } catch (error: FileNotFoundException) {
            throw LectriceError(ErrorName.FileUnreadable, error)
        } catch (error: RuntimeException) {
            // Another app's provider can answer with any exception, not only SecurityException.
            throw LectriceError(ErrorName.FileUnreadable, error)
        } ?: throw LectriceError(ErrorName.FileUnreadable)
        descriptor.use { pfd: ParcelFileDescriptor -> block("/proc/self/fd/${pfd.fd}") }
    }

    /** (display name, size, last modified) of a content URI or a path; throws when it's gone. */
    private fun describe(uri: String): Triple<String, Long, Long> {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == null || parsed.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(parsed.path ?: uri)
            if (!file.isFile) throw LectriceError(ErrorName.FileUnreadable)
            return Triple(file.name, file.length(), file.lastModified())
        }
        var name = parsed.lastPathSegment.orEmpty()
        var size = -1L
        try {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            } ?: throw LectriceError(ErrorName.FileUnreadable)
            // Proves the file is still readable (a revoked permission or a deleted file throws here).
            context.contentResolver.openFileDescriptor(parsed, "r")?.use { pfd -> if (size < 0) size = pfd.statSize } ?: throw LectriceError(ErrorName.FileUnreadable)
        } catch (error: FileNotFoundException) {
            throw LectriceError(ErrorName.FileUnreadable, error)
        } catch (error: RuntimeException) {
            // Another app's provider can answer with any exception or a cursor of another shape.
            throw LectriceError(ErrorName.FileUnreadable, error)
        }
        return Triple(name, size, 0L)
    }

    /** True when [uri] stays readable after a restart: a path, or a content URI whose read permission could be kept. */
    private fun keepPermission(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != ContentResolver.SCHEME_CONTENT) return true
        return suspendRunCatching { context.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.i(SCOPE, "no persistable permission, copying the file into app storage") }
            .isSuccess
    }

    /**
     * Copies the file to `filesDir/lectrice/audiobooks/<doc>.m4b.partial`. The sender's size is not trusted: the copy
     * stops at [MAX_COPY_BYTES] and leaves [SPACE_MARGIN_BYTES] free.
     */
    private fun copyIn(documentId: String, uri: String): File {
        val partial = File(copiesDir, "$documentId.m4b.partial")
        copiesDir.mkdirs()
        val storage = context.getSystemService(StorageManager::class.java)
        val room = storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir)) - SPACE_MARGIN_BYTES
        try {
            val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw LectriceError(ErrorName.FileUnreadable)
            val bytes = input.use { source -> partial.outputStream().use { CappedCopy.copy(source, it, minOf(MAX_COPY_BYTES, room)) } }
            Log.i(SCOPE, "copied doc=$documentId bytes=$bytes")
            return partial
        } catch (error: Exception) {
            partial.delete()
            throw when (error) {
                is LectriceError -> error
                is TooLargeException -> LectriceError(if (error.maxBytes < MAX_COPY_BYTES) ErrorName.StorageFull else ErrorName.UploadTooLarge, error)
                is IOException -> LectriceError(if (CoreLibraryRepository.isOutOfSpace(error)) ErrorName.StorageFull else ErrorName.FileUnreadable, error)
                // A provider can throw anything (SecurityException, IllegalStateException…).
                else -> LectriceError(ErrorName.FileUnreadable, error)
            }
        }
    }

    /** The checked copy becomes the book's file, replacing an earlier copy. */
    private fun keepCopy(documentId: String, staged: File): String {
        val target = File(copiesDir, "$documentId.m4b")
        if (!staged.renameTo(target)) {
            staged.delete()
            throw LectriceError(ErrorName.StorageFailed)
        }
        return target.path
    }

    private fun releasePermission(uri: String) {
        suspendRunCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun readLinks(): Map<String, AudiobookLink> = suspendRunCatching {
        if (!linksFile.isFile) return emptyMap()
        val array = JSONArray(linksFile.readText())
        (0 until array.length()).map { linkFrom(array.getJSONObject(it)) }.associateBy { it.documentId }
    }.getOrElse { error ->
        Log.w(SCOPE, "audiobook links unreadable, starting empty", error)
        emptyMap()
    }

    private fun writeLinks(links: Map<String, AudiobookLink>) {
        directory.mkdirs()
        val array = JSONArray()
        links.values.forEach { array.put(json(it)) }
        val partial = File(directory, "$LINKS_FILE.partial")
        partial.writeText(array.toString())
        if (!partial.renameTo(linksFile)) throw LectriceError(ErrorName.StorageFailed)
    }

    private fun writeTimeline(documentId: String, timeline: AudiobookTimeline) {
        mapsDir.mkdirs()
        val sentences = JSONArray()
        timeline.sentences.forEach { sentences.put(JSONArray().put(it.sentenceId).put(it.startMs).put(it.endMs)) }
        val body = JSONObject().put("mode", timeline.mode.name).put("fileSpeedPercent", timeline.fileSpeedPercent).put("durationMs", timeline.durationMs).put("sentences", sentences)
        File(mapsDir, "$documentId.json").writeText(body.toString())
    }

    private fun readTimeline(documentId: String): AudiobookTimeline? = suspendRunCatching {
        val body = JSONObject(File(mapsDir, "$documentId.json").readText())
        val array = body.getJSONArray("sentences")
        val sentences = (0 until array.length()).map { index ->
            val entry = array.getJSONArray(index)
            TimedSentence(entry.getInt(0), entry.getLong(1), entry.getLong(2))
        }
        AudiobookTimeline(AudiobookMode.valueOf(body.getString("mode")), body.getInt("fileSpeedPercent"), body.getLong("durationMs"), sentences)
    }.getOrNull()

    private fun json(link: AudiobookLink): JSONObject = JSONObject()
        .put("documentId", link.documentId).put("uri", link.uri).put("kind", link.kind.name).put("mode", link.mode.name)
        .put("remapped", link.remapped).put("matchedPercent", link.matchedPercent).put("fileSpeedPercent", link.fileSpeedPercent)
        .put("durationMs", link.durationMs).put("voiceId", link.voiceId ?: JSONObject.NULL).put("displayName", link.displayName)
        .put("sizeBytes", link.sizeBytes).put("lastModified", link.lastModified).put("linkedAt", link.linkedAt)

    private fun linkFrom(json: JSONObject) = AudiobookLink(
        documentId = json.getString("documentId"),
        uri = json.getString("uri"),
        kind = AudiobookKind.valueOf(json.getString("kind")),
        mode = AudiobookMode.valueOf(json.getString("mode")),
        remapped = json.optBoolean("remapped"),
        matchedPercent = json.optInt("matchedPercent"),
        fileSpeedPercent = json.optInt("fileSpeedPercent", PERCENT),
        durationMs = json.optLong("durationMs"),
        voiceId = json.optString("voiceId").takeIf { !json.isNull("voiceId") && it.isNotEmpty() },
        displayName = json.optString("displayName"),
        sizeBytes = json.optLong("sizeBytes"),
        lastModified = json.optLong("lastModified"),
        linkedAt = json.optLong("linkedAt"),
    )

    private fun timeline(record: AudiobookTimelineRecord) = AudiobookTimeline(
        mode = mode(record.mode),
        fileSpeedPercent = record.fileSpeedPercent.toInt(),
        durationMs = record.durationMs.toLong(),
        sentences = record.sentences.map { TimedSentence(it.sentenceId.toInt(), it.startMs.toLong(), it.endMs.toLong()) },
    )

    private fun mode(record: AudiobookModeRecord) = when (record) {
        AudiobookModeRecord.SENTENCES -> AudiobookMode.Sentences
        AudiobookModeRecord.CHAPTERS -> AudiobookMode.Chapters
        AudiobookModeRecord.UNALIGNED -> AudiobookMode.Unaligned
    }

    private companion object {
        const val SCOPE = "Audiobooks"
        const val LINKS_FILE = "audiobooks.json"
        const val MAPS_DIR = "audiobook-maps"
        const val COPIES_DIR = "audiobooks"
        const val DEFAULT_NAME = "audiobook.m4b"
        /** A 40-hour book made by Lectrice is about 1.2 GB. */
        const val MAX_COPY_BYTES = 2_000_000_000L
        const val SPACE_MARGIN_BYTES = 200L * 1_000_000
        const val PERCENT = 100
    }
}
