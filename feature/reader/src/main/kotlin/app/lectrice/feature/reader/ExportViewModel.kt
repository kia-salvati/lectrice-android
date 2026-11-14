package app.lectrice.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookKind
import app.lectrice.core.model.AudiobookMode
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.Document
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.ExportEstimate
import app.lectrice.core.model.ExportRepository
import app.lectrice.core.model.ExportRequest
import app.lectrice.core.model.ExportSpace
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.PauseReason
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.Progress
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.Sizes
import app.lectrice.core.model.SystemPauseCause
import app.lectrice.core.model.Toc
import app.lectrice.core.model.Voice
import app.lectrice.core.model.VoiceModelRepository
import app.lectrice.core.model.VoiceRepository
import app.lectrice.core.model.VoiceSampleState
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A quality row on E1 (file 10 §3.2). */
data class QualityRow(
    val modelId: String,
    val name: String,
    /** "In use · 169 MB", "Installed · 384 MB", "Not downloaded" */
    val subtitle: String,
    val installed: Boolean,
    /** "Download · 140 MB" while not installed; the download's percent while it runs. */
    val downloadLabel: String?,
    val downloadPercent: Int?,
)

/** One chapter choice of E7 (file 10 §3.11). */
data class RangeChoice(val index: Int, val title: String, val firstSentence: Int, val lastSentence: Int, val meta: String)

/** The footer of E1/E7 (file 10 §3.4, §3.11). */
data class ExportFooter(
    /** "about 8 h 10 min · about 231 MB" (E7: "Chapters 7–12 · …") */
    val summary: String,
    /** "Takes about 2 h on this phone" */
    val makeTime: String,
    /** "Start export (about 2 h)" */
    val startLabel: String,
    val fits: Boolean,
    /** E6: "It needs about {needed} free. The phone has {free}." */
    val neededBytes: Long,
    val freeBytes: Long,
    /** E6: a chapter range fits; offers "Export part of the book". */
    val canExportPart: Boolean,
)

sealed interface ExportPage {
    data object Loading : ExportPage

    /** E1 (and E6 through `footer.fits == false`). */
    data object Choose : ExportPage

    /** E7 */
    data object Part : ExportPage

    /** E2 running / waiting / paused by the user, E3 paused by the system, E5/E5b out of space, failed. */
    data class Progress(val export: AudioExport, val kind: ProgressKind, val summary: String, val detail: String, val neededBytes: Long?, val freeBytes: Long?) : ExportPage

    /** E4 */
    data class Done(val export: AudioExport, val summary: String, val saved: Boolean) : ExportPage
}

enum class ProgressKind { Running, WaitingOtherExport, WaitingEngine, PausedByUser, PausedTimeLimit, PausedHeat, PausedBattery, OutOfSpaceParts, OutOfSpaceAssemble, Failed }

data class ExportUiState(
    val page: ExportPage = ExportPage.Loading,
    val voices: List<Voice> = emptyList(),
    val voiceId: String = "",
    val quality: List<QualityRow> = emptyList(),
    val modelId: String = "",
    /** "This export uses the full-quality model. While you listen, it waits and continues when you stop." */
    val qualityNote: String? = null,
    val speed: PlaybackSpeed = PlaybackSpeed.X1,
    val readSkipped: Boolean = false,
    val footer: ExportFooter? = null,
    val rangeChoices: List<RangeChoice> = emptyList(),
    val rangeFrom: Int = 0,
    val rangeTo: Int = 0,
    val confirmCancel: Boolean = false,
    val message: ExportMessage? = null,
    val voiceSample: VoiceSampleState = VoiceSampleState(),
    /** File 23 S1: "Listen from this file" worked; the page closes back to the reader. */
    val close: Boolean = false,
)

/** The export page's snackbar. */
sealed interface ExportMessage {
    /** "Saved to Audiobooks/Lectrice." */
    data object SavedToAudiobooks : ExportMessage

    /** "This audiobook can't follow the text." */
    data object CantFollowText : ExportMessage

    /** `StorageFull` "Your phone is out of space.", `VoiceUnknown` "That voice isn't available.", else "Something went wrong." */
    data class Failed(val name: ErrorName) : ExportMessage
}

interface ExportActions {
    fun setVoice(voiceId: String)
    fun playSample(voiceId: String)
    fun setModel(modelId: String)
    fun downloadModel(modelId: String)
    fun setSpeed(speed: PlaybackSpeed)
    fun setReadSkipped(readSkipped: Boolean)
    fun start()
    fun openPart()
    fun backToChoose()
    fun setRange(from: Int, to: Int)
    fun pickWhatFits()
    fun pause()
    fun resume()
    fun askCancel()
    fun confirmCancel()
    fun dismissCancel()
    fun saveToAudiobooks()
    fun share()
    fun saveAs(uri: String)
    fun makeAnother()
    fun freeUpSpace()
    fun messageShown()
    /** File 23 S1 (E4 done): link this file to the book and listen from it. */
    fun listenFromFile()
}

/** What the export page needs from the platform. */
interface ExportPlatform {
    fun share(export: AudioExport)
    suspend fun copyTo(export: AudioExport, uri: String)
    fun openStorage(requestedBytes: Long?)
    fun playSample(voiceId: String)
    val sampleState: StateFlow<VoiceSampleState>

    /** A started or resumed export runs in the foreground work service, which also keeps [ExportRepository.exports] fresh. */
    fun runExports()
}

/** The export page inside the player sheet (file 10). */
class ExportViewModel(
    private val document: Document,
    private val exports: ExportRepository,
    private val models: VoiceModelRepository,
    private val voices: VoiceRepository,
    private val playback: PlaybackController,
    private val settings: SettingsStore,
    private val platform: ExportPlatform,
    private val audiobooks: AudiobookRepository,
) : ViewModel(), ExportActions {
    private val state = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = state.asStateFlow()

    private var estimate: ExportEstimate? = null
    private var estimateJob: Job? = null
    private var choosing = true

    init {
        val listening = settings.bookListening(document.id)
        val active = models.statuses.value.firstOrNull { it.inUse }?.model?.id ?: models.statuses.value.firstOrNull()?.model?.id.orEmpty()
        state.update { it.copy(voiceId = listening.voiceId, modelId = active, readSkipped = settings.settings.value.readSkipped) }
        viewModelScope.launch { state.update { it.copy(voices = suspendRunCatching { voices.listVoices() }.getOrDefault(emptyList())) } }
        viewModelScope.launch { platform.sampleState.collect { sample -> state.update { it.copy(voiceSample = sample) } } }
        viewModelScope.launch {
            combine(exports.exports, exports.systemPauseCause, models.statuses, models.download) { list, causes, _, _ -> list to causes }.collect { (list, causes) ->
                state.update { it.copy(quality = qualityRows(), qualityNote = qualityNote(it.modelId)) }
                val latest = list.firstOrNull { it.documentId == document.id && it.status != ExportStatus.Cancelled }
                if (latest == null || (latest.status == ExportStatus.Done && choosing && state.value.page is ExportPage.Choose)) {
                    if (state.value.page is ExportPage.Loading || state.value.page is ExportPage.Progress) showChoose()
                } else {
                    showRecord(latest, causes[latest.id])
                }
            }
        }
        viewModelScope.launch { suspendRunCatching { exports.refresh() } }
    }

    private fun showChoose() {
        choosing = true
        state.update { it.copy(page = ExportPage.Choose) }
        recompute()
    }

    private suspend fun showRecord(export: AudioExport, cause: SystemPauseCause?) {
        if (export.status == ExportStatus.Done) {
            choosing = false
            val length = Progress.formatDuration(((export.fileBytes ?: 0) / ExportSpace.BYTES_PER_SECOND).toDouble())
            val summary = listOfNotNull(export.rangeLabel, length, Sizes.format(export.fileBytes ?: 0), "M4B").joinToString(" · ")
            state.update { it.copy(page = ExportPage.Done(export, summary, saved = export.sharedUri != null)) }
            return
        }
        val kind = kind(export, cause)
        val voiceName = state.value.voices.firstOrNull { it.id == export.voiceId }?.name ?: export.voiceId
        val modelName = models.statuses.value.firstOrNull { it.model.engineId == export.engineId }?.model?.name.orEmpty()
        val summary = listOfNotNull(voiceName, modelName.ifEmpty { null }, export.speed.label, export.rangeLabel).joinToString(" · ")
        val detail = when (kind) {
            ProgressKind.Running -> "Part ${export.partsDone + 1} of ${export.partsTotal}" + (export.secondsLeft?.let { " · about ${Progress.formatDuration(it.toDouble())} left" } ?: "")
            else -> "${export.percent} %"
        }
        val (needed, free) = if (kind == ProgressKind.OutOfSpaceParts || kind == ProgressKind.OutOfSpaceAssemble) neededForResume(export, kind) else null to null
        state.update { it.copy(page = ExportPage.Progress(export, kind, summary, detail, needed, free)) }
    }

    private fun kind(export: AudioExport, cause: SystemPauseCause?): ProgressKind = when {
        export.status == ExportStatus.Failed -> ProgressKind.Failed
        export.waitingForEngine || export.pausedBy == PauseReason.EngineSwitch -> ProgressKind.WaitingEngine
        export.status == ExportStatus.Queued && exports.exports.value.any { it.status == ExportStatus.Running && it.id != export.id } -> ProgressKind.WaitingOtherExport
        export.status == ExportStatus.Paused && export.pausedBy == PauseReason.User -> ProgressKind.PausedByUser
        export.status == ExportStatus.Paused && export.pausedBy == PauseReason.StorageFull -> if (export.assembling) ProgressKind.OutOfSpaceAssemble else ProgressKind.OutOfSpaceParts
        export.status == ExportStatus.Paused && cause == SystemPauseCause.Heat -> ProgressKind.PausedHeat
        export.status == ExportStatus.Paused && cause == SystemPauseCause.Battery -> ProgressKind.PausedBattery
        export.status == ExportStatus.Paused -> ProgressKind.PausedTimeLimit
        else -> ProgressKind.Running
    }

    private suspend fun neededForResume(export: AudioExport, kind: ProgressKind): Pair<Long, Long> {
        val final = ExportSpace.finalBytes(export.partsTotal * PART_SECONDS / export.speed.rate.toDouble())
        val needed = if (kind == ProgressKind.OutOfSpaceAssemble) ExportSpace.neededResumeAssemble(final) else ExportSpace.neededResumeParts(final, exports.partsBytes(export.id))
        return needed to exports.freeBytes()
    }

    private fun qualityRows(): List<QualityRow> = models.statuses.value.map { status ->
        val download = models.download.value?.takeIf { it.modelId == status.model.id && it.state is ModelInstallState.Downloading }
        val installed = status.state == ModelInstallState.Installed
        QualityRow(
            modelId = status.model.id,
            name = status.model.name,
            subtitle = when {
                status.inUse -> "In use · ${Sizes.format(status.model.installedBytes)}"
                installed -> "Installed · ${Sizes.format(status.model.installedBytes)}"
                download != null -> "Downloading · ${(download.doneBytes * PERCENT / download.totalBytes.coerceAtLeast(1)).toInt()} %"
                else -> "Not downloaded"
            },
            installed = installed,
            downloadLabel = if (!installed && download == null) "Download · ${Sizes.format(status.model.downloadBytes)}" else null,
            downloadPercent = download?.let { (it.doneBytes * PERCENT / it.totalBytes.coerceAtLeast(1)).toInt() },
        )
    }

    private fun qualityNote(modelId: String): String? {
        val inUse = models.statuses.value.firstOrNull { it.inUse }?.model?.id
        if (modelId == inUse) return null
        val name = models.statuses.value.firstOrNull { it.model.id == modelId }?.model?.name?.lowercase() ?: return null
        return "This export uses the $name model. While you listen, it waits and continues when you stop."
    }

    /** File 10 §3.4: recomputed on every change, debounced 150 ms. */
    private fun recompute() {
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val current = state.value
            val whole = suspendRunCatching { exports.estimate(request(current, range = null)) }.getOrNull() ?: return@launch
            estimate = whole
            val choices = rangeChoices(whole, current.speed)
            val free = exports.freeBytes()
            val wholeFooter = footer(whole.audioMs1x, whole.cachedMs1x, current, free, label = null)
            val partFits = choices.isNotEmpty() && suggestion(choices, whole, current.speed, free) != null
            state.update { it.copy(footer = wholeFooter.copy(canExportPart = partFits), rangeChoices = choices) }
            if (current.page is ExportPage.Part) updatePartFooter()
        }
    }

    private fun footer(audioMs1x: Long, cachedMs1x: Long, current: ExportUiState, free: Long, label: String?): ExportFooter {
        val seconds = ExportSpace.outputSeconds(audioMs1x, current.speed)
        val final = ExportSpace.finalBytes(seconds)
        val needed = ExportSpace.neededNew(final)
        val make = ExportSpace.formatMakeTime((audioMs1x - cachedMs1x).coerceAtLeast(0) / MS_PER_SECOND * models.rtf(current.modelId))
        val summary = listOfNotNull(label, "about ${Progress.formatDuration(seconds)}", "about ${Sizes.format(ExportSpace.shownBytes(seconds))}").joinToString(" · ")
        return ExportFooter(summary, "Takes $make on this phone", "Start export ($make)", needed <= free, needed, free, canExportPart = false)
    }

    /** Chapter-level entries (file 07 `isChapterNode`), with "Beginning" for front matter; page steps without a TOC. */
    private fun rangeChoices(whole: ExportEstimate, speed: PlaybackSpeed): List<RangeChoice> {
        val toc = document.toc
        val starts: List<Pair<String, Int>> = if (toc.isEmpty()) {
            whole.partOffsets.map { "p. ${it.pageLabel}" to it.firstSentence }
        } else {
            val chapters = Toc.chapterNodeIndexes(toc).map { toc[it].title to toc[it].sentenceId }
            val first = chapters.firstOrNull()?.second ?: 0
            (if (first > 0) listOf("Beginning" to 0) else emptyList()) + chapters
        }
        val msBefore: (Int) -> Long = { sentence ->
            if (toc.isEmpty()) whole.partOffsets.firstOrNull { it.firstSentence == sentence }?.audioMs1xBefore ?: 0
            else toc.indexOfFirst { it.sentenceId == sentence }.takeIf { it >= 0 }?.let { whole.tocOffsetsMs[it] } ?: 0
        }
        return starts.mapIndexed { index, (title, first) ->
            val next = starts.getOrNull(index + 1)
            val ms = (next?.let { msBefore(it.second) } ?: whole.totalAudioMs1x) - msBefore(first)
            val seconds = ExportSpace.outputSeconds(ms, speed)
            RangeChoice(index, title, first, (next?.second ?: document.sentenceIdEnd) - 1, "about ${Progress.formatDuration(seconds)} · about ${Sizes.format(ExportSpace.shownBytes(seconds))}")
        }
    }

    /** From the saved place's chapter (or the first when not started / finished), as far as fits. */
    private fun suggestion(choices: List<RangeChoice>, whole: ExportEstimate, speed: PlaybackSpeed, free: Long): Pair<Int, Int>? {
        val place = playback.state.value.currentSentenceId ?: document.lastSentenceId ?: 0
        val start = choices.indexOfLast { it.firstSentence <= place }.coerceAtLeast(0)
        val lengths = choices.map { choice -> rangeMs(whole, choice.index, choice.index, choices) }
        val end = ExportSpace.suggestEnd(lengths, start, speed, free) ?: return null
        return start to end
    }

    private fun rangeMs(whole: ExportEstimate, from: Int, to: Int, choices: List<RangeChoice>): Long {
        val total = whole.totalAudioMs1x
        val sentences = (document.sentenceIdEnd).coerceAtLeast(1)
        val share = (choices[to].lastSentence + 1 - choices[from].firstSentence).toDouble() / sentences
        return (total * share).toLong()
    }

    private fun updatePartFooter() {
        val current = state.value
        val whole = estimate ?: return
        val choices = current.rangeChoices
        if (choices.isEmpty()) return
        viewModelScope.launch {
            val range = choices[current.rangeFrom].firstSentence..choices[current.rangeTo].lastSentence
            val label = rangeLabel(current.rangeFrom, current.rangeTo)
            val partEstimate = suspendRunCatching { exports.estimate(request(current, range)) }.getOrNull()
            val audio = partEstimate?.audioMs1x ?: rangeMs(whole, current.rangeFrom, current.rangeTo, choices)
            val footer = footer(audio, partEstimate?.cachedMs1x ?: 0, current, exports.freeBytes(), label)
            state.update { it.copy(footer = footer) }
        }
    }

    private fun rangeLabel(from: Int, to: Int): String {
        val choices = state.value.rangeChoices
        val numbers = listOf(from, to).map { index -> Toc.chapterLabel(choices[index].title).number ?: choices[index].title }
        return if (from == to) "Ch. ${numbers[0]}" else "Ch. ${numbers[0]}–${numbers[1]}"
    }

    private fun request(current: ExportUiState, range: IntRange?) = ExportRequest(
        documentId = document.id,
        voiceId = current.voiceId,
        modelId = current.modelId,
        readSkipped = current.readSkipped,
        speed = current.speed,
        range = range,
        rangeLabel = range?.let { rangeLabel(current.rangeFrom, current.rangeTo) },
    )

    override fun setVoice(voiceId: String) {
        state.update { it.copy(voiceId = voiceId) }
        recompute()
    }

    override fun playSample(voiceId: String) = platform.playSample(voiceId)

    override fun setModel(modelId: String) {
        if (state.value.quality.firstOrNull { it.modelId == modelId }?.installed != true) return
        state.update { it.copy(modelId = modelId, qualityNote = qualityNote(modelId)) }
        recompute()
    }

    override fun downloadModel(modelId: String) = models.startDownload(modelId, wifiOnly = false)

    override fun setSpeed(speed: PlaybackSpeed) {
        state.update { it.copy(speed = speed) }
        recompute()
    }

    override fun setReadSkipped(readSkipped: Boolean) {
        state.update { it.copy(readSkipped = readSkipped) }
        recompute()
    }

    override fun start() {
        val current = state.value
        val partRange = if (current.page is ExportPage.Part && current.rangeChoices.isNotEmpty()) {
            current.rangeChoices[current.rangeFrom].firstSentence..current.rangeChoices[current.rangeTo].lastSentence
        } else {
            null
        }
        viewModelScope.launch {
            val fits = state.value.footer?.fits ?: false
            if (!fits) return@launch
            try {
                choosing = false
                exports.start(request(current, partRange))
                platform.runExports()
            } catch (error: LectriceError) {
                choosing = true
                state.update { it.copy(message = ExportMessage.Failed(error.name)) }
            }
        }
    }

    override fun openPart() {
        val current = state.value
        val whole = estimate ?: return
        val (from, to) = suggestion(current.rangeChoices, whole, current.speed, current.footer?.freeBytes ?: 0) ?: (0 to 0)
        state.update { it.copy(page = ExportPage.Part, rangeFrom = from, rangeTo = to) }
        updatePartFooter()
    }

    override fun backToChoose() = showChoose()

    override fun setRange(from: Int, to: Int) {
        state.update { it.copy(rangeFrom = minOf(from, to), rangeTo = maxOf(from, to)) }
        updatePartFooter()
    }

    override fun pickWhatFits() = openPart()

    override fun pause() = launchExport { exports.pause(it.id) }

    override fun resume() = launchExport {
        exports.resume(it.id)
        platform.runExports()
    }

    override fun askCancel() = state.update { it.copy(confirmCancel = true) }

    override fun confirmCancel() {
        state.update { it.copy(confirmCancel = false) }
        launchExport { exports.cancel(it.id) }
    }

    override fun dismissCancel() = state.update { it.copy(confirmCancel = false) }

    override fun saveToAudiobooks() {
        val done = state.value.page as? ExportPage.Done ?: return
        viewModelScope.launch {
            try {
                val wasLinked = done.export.filePath != null && audiobooks.links.value[document.id]?.uri == done.export.filePath
                exports.saveToAudiobooks(done.export.id)
                // The file moved: the link follows it to its MediaStore URI (file 23 §2 A).
                val moved = exports.exports.value.firstOrNull { it.id == done.export.id }?.sharedUri
                if (wasLinked && moved != null) suspendRunCatching { audiobooks.link(document.id, moved, AudiobookKind.OwnExport) }
                state.update { it.copy(message = ExportMessage.SavedToAudiobooks) }
            } catch (error: LectriceError) {
                state.update { it.copy(message = ExportMessage.Failed(error.name)) }
            }
        }
    }

    override fun share() {
        val done = state.value.page as? ExportPage.Done ?: return
        platform.share(done.export)
    }

    override fun saveAs(uri: String) {
        val done = state.value.page as? ExportPage.Done ?: return
        viewModelScope.launch { suspendRunCatching { platform.copyTo(done.export, uri) }.onFailure { state.update { it.copy(message = ExportMessage.Failed(ErrorName.Internal)) } } }
    }

    override fun makeAnother() = showChoose()

    override fun freeUpSpace() {
        val footer = state.value.footer
        val progress = state.value.page as? ExportPage.Progress
        val requested = progress?.let { (it.neededBytes ?: 0) - (it.freeBytes ?: 0) } ?: footer?.let { it.neededBytes - it.freeBytes }
        platform.openStorage(requested?.coerceAtLeast(0))
    }

    override fun messageShown() = state.update { it.copy(message = null) }

    override fun listenFromFile() {
        val done = state.value.page as? ExportPage.Done ?: return
        val uri = done.export.filePath ?: done.export.sharedUri ?: return
        viewModelScope.launch {
            try {
                val link = audiobooks.link(document.id, uri, AudiobookKind.OwnExport)
                if (link.mode != AudiobookMode.Sentences) {
                    state.update { it.copy(message = ExportMessage.CantFollowText) }
                    return@launch
                }
                playback.setAudioSource(AudioSource.Audiobook)
                state.update { it.copy(close = true) }
            } catch (error: LectriceError) {
                state.update { it.copy(message = ExportMessage.Failed(error.name)) }
            }
        }
    }

    private fun launchExport(block: suspend (AudioExport) -> Unit) {
        val export = (state.value.page as? ExportPage.Progress)?.export ?: return
        viewModelScope.launch { suspendRunCatching { block(export) }.onFailure { error -> state.update { it.copy(message = ExportMessage.Failed((error as? LectriceError)?.name ?: ErrorName.Internal)) } } }
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
        const val PERCENT = 100
        const val MS_PER_SECOND = 1000.0
        /** Parts hold about 10 listening-minutes at 1× (core ExportSettings). */
        const val PART_SECONDS = 600.0
    }
}
