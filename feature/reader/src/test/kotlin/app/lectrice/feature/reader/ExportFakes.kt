package app.lectrice.feature.reader

import app.lectrice.core.model.AppSettings
import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookFileInfo
import app.lectrice.core.model.AudiobookImport
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookLink
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.AudiobookTimeline
import app.lectrice.core.model.Block
import app.lectrice.core.model.BlockKind
import app.lectrice.core.model.BookListening
import app.lectrice.core.model.Document
import app.lectrice.core.model.DownloadInfo
import app.lectrice.core.model.ExportEstimate
import app.lectrice.core.model.ExportRepository
import app.lectrice.core.model.ExportRequest
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.ModelStatus
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.Sentence
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.SleepTimer
import app.lectrice.core.model.SystemPauseCause
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceGender
import app.lectrice.core.model.VoiceLanguage
import app.lectrice.core.model.VoiceModelInfo
import app.lectrice.core.model.VoiceModelRepository
import app.lectrice.core.model.VoiceRepository
import app.lectrice.core.model.VoiceSampleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An invented two-paragraph book. */
internal fun inventedDocument(id: String = "d".repeat(64)): Document {
    val sentences = (0 until SENTENCES).map { Sentence(it, "Invented sentence number $it.", pageIndex = 1 + it / 2) }
    val blocks = sentences.chunked(2).mapIndexed { index, chunk -> Block(index, BlockKind.Paragraph, null, chunk.first().pageIndex, true, null, chunk, null) }
    return Document(id, "The Invented Book", pageCount = 3, cleanerVersion = 2, pages = emptyList(), toc = emptyList(), blocks = blocks, lastSentenceId = null)
}

private const val SENTENCES = 6

internal fun export(documentId: String, status: ExportStatus, done: Int, total: Int = 100) = AudioExport(
    id = "e1", documentId = documentId, voiceId = "af_heart", speed = PlaybackSpeed.X1, engineId = "engine-a", range = null, rangeLabel = null,
    title = "The Invented Book", status = status, pausedBy = null, assembling = false, error = null, sentencesDone = done, sentencesTotal = total,
    partsDone = 0, partsTotal = 2, secondsLeft = null, fileBytes = if (status == ExportStatus.Done) 8_000_000 else null,
    filePath = if (status == ExportStatus.Done) "/exports/e1.m4b" else null, sharedUri = null, waitingForEngine = false,
)

internal class FakeExports(private val free: Long = 10_000_000_000) : ExportRepository {
    val list = MutableStateFlow<List<AudioExport>>(emptyList())
    override val exports: StateFlow<List<AudioExport>> = list
    override val systemPauseCause = MutableStateFlow<Map<String, SystemPauseCause>>(emptyMap())
    val started = mutableListOf<ExportRequest>()
    val resumed = mutableListOf<String>()

    override suspend fun refresh() = Unit
    override suspend fun estimate(request: ExportRequest) = ExportEstimate(3_600_000, 3_600_000, 0, 3_600_000, emptyMap(), emptyList())
    override suspend fun start(request: ExportRequest): AudioExport {
        started += request
        return export(request.documentId, ExportStatus.Running, done = 0).also { list.value = listOf(it) }
    }
    override suspend fun pause(exportId: String) = Unit
    override suspend fun resume(exportId: String) {
        resumed += exportId
    }
    override suspend fun cancel(exportId: String) = Unit
    override suspend fun delete(exportId: String) = Unit
    override suspend fun saveToAudiobooks(exportId: String) = Unit
    override suspend fun freeBytes() = free
    override fun partsBytes(exportId: String) = 0L
}

internal class FakeModels(private val slowPhone: Boolean = false) : VoiceModelRepository {
    private val model = VoiceModelInfo("fast", "engine-a", "Fast", "Invented model", 100_000_000, 110_000_000)
    override val statuses = MutableStateFlow(listOf(ModelStatus(model, ModelInstallState.Installed, inUse = true)))
    override val download = MutableStateFlow<DownloadInfo?>(null)
    override fun refresh() = Unit
    override fun startDownload(modelId: String, wifiOnly: Boolean) = Unit
    override fun pauseDownload() = Unit
    override fun cancelDownload() = Unit
    override suspend fun importArchive(uri: String) = model
    override fun use(modelId: String) = Unit
    override suspend fun delete(modelId: String) = Unit
    override fun isMetered() = false
    override suspend fun freeBytes() = 10_000_000_000
    override fun spaceNeeded(modelId: String) = 0L
    override fun rtf(modelId: String) = 0.5f
    override fun isSlowPhone() = slowPhone
}

internal class FakeVoices : VoiceRepository {
    override suspend fun listVoices() = listOf(Voice("af_heart", "Heart", VoiceLanguage.EnUs, VoiceGender.Female))
}

internal class FakePlayback : PlaybackController {
    override val state = MutableStateFlow(PlaybackState())
    override fun open(document: Document, sentenceId: Int?, play: Boolean) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun jumpTo(sentenceId: Int) = Unit
    override fun setSpeed(speed: PlaybackSpeed) = Unit
    override fun setVoice(voiceId: String) = Unit
    override fun setReadSkipped(readSkipped: Boolean) = Unit
    override fun setSleepTimer(timer: SleepTimer) = Unit
    override fun setAudioSource(source: AudioSource) = Unit
    override fun stop() = Unit
}

internal class FakeSettings : SettingsStore {
    override val settings = MutableStateFlow(AppSettings())
    override fun update(change: (AppSettings) -> AppSettings) {
        settings.value = change(settings.value)
    }
    override fun bookListening(documentId: String) = BookListening(settings.value.defaultVoiceId, settings.value.defaultSpeed)
    override fun setBookListening(documentId: String, listening: BookListening) = Unit
    override fun audioSource(documentId: String) = AudioSource.Generated
    override fun setAudioSource(documentId: String, source: AudioSource) = Unit
    override fun isSlowTipShown(documentId: String) = false
    override fun setSlowTipShown(documentId: String) = Unit
    override fun forgetBook(documentId: String) = Unit
}

internal class FakeExportPlatform : ExportPlatform {
    var workStarts = 0
    override fun share(export: AudioExport) = Unit
    override suspend fun copyTo(export: AudioExport, uri: String) = Unit
    override fun openStorage(requestedBytes: Long?) = Unit
    override fun playSample(voiceId: String) = Unit
    override val sampleState = MutableStateFlow(VoiceSampleState())
    override fun runExports() {
        workStarts++
    }
}

internal class FakeAudiobooks : AudiobookRepository {
    override val links = MutableStateFlow<Map<String, AudiobookLink>>(emptyMap())
    override suspend fun inspect(uri: String) = AudiobookFileInfo(null, 0, 0, null, null)
    override suspend fun link(documentId: String, uri: String, kind: AudiobookKind): AudiobookLink = error("not used")
    override suspend fun import(uri: String, fromAnotherApp: Boolean): AudiobookImport = AudiobookImport.Foreign
    override suspend fun unlink(documentId: String) = Unit
    override suspend fun timeline(documentId: String): AudiobookTimeline? = null
    override fun fileMissing(documentId: String) = false
}
