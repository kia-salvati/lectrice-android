package app.lectrice.core.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.ExportEstimate
import app.lectrice.core.model.ExportRepository
import app.lectrice.core.model.ExportRequest
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.PartOffset
import app.lectrice.core.model.PauseReason
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.SystemPauseCause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.lectrice_ffi.AudioExportRecord
import uniffi.lectrice_ffi.ExportStatusRecord
import uniffi.lectrice_ffi.PauseReasonRecord
import java.io.File
import java.io.IOException
import uniffi.lectrice_ffi.ExportRequest as RecordRequest

/** Exports through the core (file 10), plus the Android-only move to `Audiobooks/Lectrice/` (file 20 §3.4). */
class CoreExportRepository(private val context: Context, private val core: RustCore, private val dataDir: File, private val engine: EngineHost) : ExportRepository {
    private val state = MutableStateFlow<List<AudioExport>>(emptyList())
    override val exports: StateFlow<List<AudioExport>> = state.asStateFlow()

    private val causes = MutableStateFlow<Map<String, SystemPauseCause>>(emptyMap())
    override val systemPauseCause: StateFlow<Map<String, SystemPauseCause>> = causes.asStateFlow()

    private fun setSystemPauseCause(exportId: String, cause: SystemPauseCause?) {
        causes.value = if (cause == null) causes.value - exportId else causes.value + (exportId to cause)
    }

    override suspend fun refresh() {
        state.value = core.call("list_all_exports") { it.listAllExports() }.map(::export)
    }

    /** The core's export services exist only while an engine is loaded (the model in use; the work service loads another export's model when nobody listens). */
    private suspend fun withEngine() {
        if (engine.loadedModel() == null) engine.ensureLoadedForExport() ?: throw LectriceError(ErrorName.SpeechEngineUnavailable)
    }

    override suspend fun estimate(request: ExportRequest): ExportEstimate {
        withEngine()
        val record = core.call("estimate_export") { it.estimateExport(record(request)) }
        return ExportEstimate(
            audioMs1x = record.audioMs1x.toLong(),
            outputMs = record.outputMs.toLong(),
            cachedMs1x = record.cachedMs1x.toLong(),
            totalAudioMs1x = record.totalAudioMs1x.toLong(),
            tocOffsetsMs = record.tocOffsets.associate { it.tocIndex.toInt() to it.audioMs1xBefore.toLong() },
            partOffsets = record.partOffsets.map { PartOffset(it.firstSentence.toInt(), it.pageLabel, it.audioMs1xBefore.toLong()) },
        )
    }

    override suspend fun start(request: ExportRequest): AudioExport {
        withEngine()
        val started = export(core.call("start_export") { it.startExport(record(request)) })
        Log.i(SCOPE, "export started id=${started.id} speed=${started.speed.percent} parts=${started.partsTotal}")
        refresh()
        return started
    }

    override suspend fun pause(exportId: String) {
        withEngine()
        core.call("pause_export") { it.pauseExport(exportId, PauseReasonRecord.USER) }
        refresh()
    }

    override suspend fun resume(exportId: String) {
        setSystemPauseCause(exportId, null)
        withEngine()
        core.call("resume_export") { it.resumeExport(exportId) }
        refresh()
    }

    /** Pauses every running export for the system (time limit, heat, battery). */
    suspend fun pauseAllForSystem(cause: SystemPauseCause) {
        val paused = runCatching { core.call("pause_all_exports") { it.pauseAllExports(PauseReasonRecord.SYSTEM) } }.getOrDefault(emptyList())
        paused.forEach { setSystemPauseCause(it.id, cause) }
        refresh()
    }

    /** Continues what the system or an engine switch paused. */
    suspend fun resumeSystemPaused() {
        runCatching { withEngine() }
        runCatching { core.call("resume_exports") { it.resumeExports() } }
        causes.value = emptyMap()
        refresh()
    }

    override suspend fun cancel(exportId: String) {
        withEngine()
        core.call("cancel_export") { it.cancelExport(exportId) }
        refresh()
    }

    override suspend fun delete(exportId: String) {
        val export = state.value.firstOrNull { it.id == exportId }
        export?.sharedUri?.let { uri -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) } } }
        core.call("delete_export") { it.deleteExport(exportId) }
        refresh()
    }

    override suspend fun saveToAudiobooks(exportId: String) = withContext(Dispatchers.IO) {
        val export = state.value.firstOrNull { it.id == exportId } ?: throw LectriceError(ErrorName.ExportNotFound)
        val file = File(export.filePath ?: throw LectriceError(ErrorName.ExportNotReady))
        val bytes = file.length()
        if (freeBytes() < bytes + SAVE_MARGIN_BYTES) throw LectriceError(ErrorName.StorageFull, bytes = bytes + SAVE_MARGIN_BYTES)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName(export))
            put(MediaStore.Audio.Media.MIME_TYPE, MIME)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_AUDIOBOOKS}/Lectrice")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw LectriceError(ErrorName.StorageFailed)
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output, COPY_BUFFER) } } ?: throw IOException("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw LectriceError(if (CoreLibraryRepository.isOutOfSpace(error)) ErrorName.StorageFull else ErrorName.StorageFailed, error)
        }
        core.blocking("mark_export_saved") { it.markExportSaved(exportId, uri.toString()) }
        file.delete()
        Log.i(SCOPE, "export moved to Audiobooks id=$exportId bytes=$bytes")
        state.value = core.blocking("list_all_exports") { it.listAllExports() }.map(::export)
    }

    override suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    }

    override fun partsBytes(exportId: String): Long =
        File(dataDir, "exports/parts/$exportId").listFiles().orEmpty().filter { it.isFile }.sumOf(File::length)

    /** "{Title} - {Voice}.m4b", " - 1.5x" when sped up, " - Ch. 7–12" for a range; no path characters; ≤ 120 chars. */
    private fun displayName(export: AudioExport): String {
        val voice = export.voiceId.substringAfter('_').replaceFirstChar(Char::uppercase)
        val parts = listOfNotNull(export.title, voice, export.speed.takeIf { it != PlaybackSpeed.X1 }?.label?.replace("×", "x"), export.rangeLabel)
        val name = parts.joinToString(" - ").replace(UNSAFE, "").take(NAME_MAX).trim()
        return "$name.m4b"
    }

    private fun record(request: ExportRequest) = RecordRequest(
        documentId = request.documentId,
        voiceId = request.voiceId,
        readSkipped = request.readSkipped,
        speedPercent = request.speed.percent.toUShort(),
        engineId = ModelSpec.byId(request.modelId)?.info?.engineId,
        rangeFrom = request.range?.first?.toUInt(),
        rangeTo = request.range?.last?.toUInt(),
        rangeLabel = request.rangeLabel,
    )

    private fun export(record: AudioExportRecord): AudioExport {
        val from = record.rangeFrom
        val to = record.rangeTo
        return AudioExport(
            id = record.id,
            documentId = record.documentId,
            voiceId = record.voiceId,
            speed = PlaybackSpeed.fromPercent(record.speedPercent.toInt()),
            engineId = record.engineId,
            range = if (from != null && to != null) from.toInt()..to.toInt() else null,
            rangeLabel = record.rangeLabel,
            title = record.title,
            status = when (record.status) {
                ExportStatusRecord.QUEUED -> ExportStatus.Queued
                ExportStatusRecord.RUNNING -> ExportStatus.Running
                ExportStatusRecord.PAUSED -> ExportStatus.Paused
                ExportStatusRecord.DONE -> ExportStatus.Done
                ExportStatusRecord.FAILED -> ExportStatus.Failed
                ExportStatusRecord.CANCELLED -> ExportStatus.Cancelled
            },
            pausedBy = when (record.pausedBy) {
                PauseReasonRecord.USER -> PauseReason.User
                PauseReasonRecord.SYSTEM -> PauseReason.System
                PauseReasonRecord.STORAGE_FULL -> PauseReason.StorageFull
                PauseReasonRecord.ENGINE_SWITCH -> PauseReason.EngineSwitch
                null -> null
            },
            assembling = record.assembling,
            error = record.errorName?.let(ErrorName::fromName),
            sentencesDone = record.sentencesDone.toInt(),
            sentencesTotal = record.sentencesTotal.toInt(),
            partsDone = record.partsDone.toInt(),
            partsTotal = record.partsTotal.toInt(),
            secondsLeft = record.estimatedSecondsLeft?.toInt(),
            fileBytes = record.fileBytes?.toLong(),
            filePath = record.filePath,
            sharedUri = record.sharedUri,
            waitingForEngine = record.waitingForEngine,
        )
    }

    private companion object {
        const val SCOPE = "Exports"
        const val MIME = "audio/mp4"
        const val SAVE_MARGIN_BYTES = 50_000_000L
        const val COPY_BUFFER = 1 shl 16
        const val NAME_MAX = 120
        val UNSAFE = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
    }
}
