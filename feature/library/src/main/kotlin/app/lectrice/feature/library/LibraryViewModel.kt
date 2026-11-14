package app.lectrice.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.AudiobookImport
import app.lectrice.core.model.AudiobookRepository
import app.lectrice.core.model.Covers
import app.lectrice.core.model.DocumentSummary
import app.lectrice.core.model.ImportOutcome
import app.lectrice.core.model.ImportQueue
import app.lectrice.core.model.IncomingFiles
import app.lectrice.core.model.IncomingKind
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.Pages
import app.lectrice.core.model.PdfSource
import app.lectrice.core.model.PlaceLabels
import app.lectrice.core.model.PlaybackController
import app.lectrice.core.model.PlaybackOrder
import app.lectrice.core.model.Progress
import app.lectrice.core.model.RemovalController
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.VoiceAvailability
import app.lectrice.core.model.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Library sends the user; the app's navigator implements it. */
interface LibraryNavigation {
    fun openReader(documentId: String, play: Boolean)
    fun openSettings()
    fun openModelSetup()
    fun openDownload()
}

/** File 02 (library), 03 (import), 21 (remove). */
class LibraryViewModel(
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val imports: ImportQueue,
    private val removal: RemovalController,
    private val settings: SettingsStore,
    private val voice: VoiceAvailability,
    private val navigation: LibraryNavigation,
    private val audiobooks: AudiobookRepository,
    private val incoming: IncomingFiles,
    private val now: () -> Long = { System.currentTimeMillis() / MS_PER_SECOND },
) : ViewModel(), LibraryActions {
    private val state = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = state.asStateFlow()

    private val summaries = mutableListOf<DocumentSummary>()
    private var total = 0
    private var cardSummary: DocumentSummary? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { imports.jobs.collect { jobs -> state.update { it.copy(imports = jobs.map { job -> ImportRow(job.key, job.displayName, job.running) }) } } }
        viewModelScope.launch { imports.results.collect(::onImportOutcome) }
        viewModelScope.launch {
            incoming.waiting.collect { waiting ->
                state.update { it.copy(incomingConfirm = waiting.firstOrNull()?.let { file -> IncomingConfirm(file.key, file.displayName, file.kind == IncomingKind.Audiobook) }) }
            }
        }
        viewModelScope.launch { voice.state.collect { voiceState -> state.update { it.copy(banner = banner(voiceState)) } } }
        viewModelScope.launch {
            playback.state.collect { playing ->
                state.update { current -> current.copy(continueCard = current.continueCard?.let { it.copy(playing = playing.documentId == it.documentId && playing.isPlaying) }) }
            }
        }
        reload()
    }

    /** Page 1 again (on start and when coming back from the reader: positions and order changed). */
    fun reload() {
        loadJob?.cancel()
        summaries.clear()
        loadJob = viewModelScope.launch { loadPage(offset = 0) }
    }

    override fun loadMore() {
        if (loadJob?.isActive == true || summaries.size >= total) return
        loadJob = viewModelScope.launch { loadPage(offset = summaries.size) }
    }

    override fun retryList() = reload()

    private suspend fun loadPage(offset: Int) {
        try {
            val page = library.listRecent(PAGE_SIZE, offset)
            if (offset == 0) summaries.clear()
            summaries += page.items
            total = page.total
            publishList()
        } catch (error: LectriceError) {
            state.update { it.copy(loading = false, listFailed = true) }
        }
    }

    private fun publishList() {
        val card = summaries.firstOrNull { it.lastSentenceId != null }
        val playingNow = playback.state.value
        val previousCard = state.value.continueCard
        state.update { current ->
            current.copy(
                loading = false,
                listFailed = false,
                continueCard = card?.let { summary ->
                    val percent = Progress.percentRead(summary.lastSentenceId, summary.sentenceCount) ?: 0
                    val keep = previousCard?.takeIf { it.documentId == summary.id }
                    ContinueCard(
                        documentId = summary.id,
                        title = summary.title,
                        percent = percent,
                        location = keep?.location,
                        timeLeft = keep?.timeLeft,
                        finished = keep?.finished ?: false,
                        playing = playingNow.documentId == summary.id && playingNow.isPlaying,
                        playLabel = keep?.playLabel ?: "Play ${summary.title}",
                    )
                },
                recent = summaries.filter { it.id != card?.id }.map(::row),
            )
        }
        if (card != null && card.id != cardSummary?.id) fillCard(card)
        cardSummary = card
    }

    /** Loads the card's document only, for its location and time left (file 02 §3.2). */
    private fun fillCard(summary: DocumentSummary) {
        viewModelScope.launch {
            val document = runCatching { library.getDocument(summary.id, markOpened = false) }.getOrNull() ?: return@launch
            val listening = settings.bookListening(summary.id)
            val order = PlaybackOrder(library.playbackOrder(summary.id, settings.settings.value.readSkipped), document)
            val sentence = order.startingSentenceId(summary.lastSentenceId) ?: return@launch
            val labels = PlaceLabels.of(document, order, sentence, listening.speed.rate, Pages.labelLookup(document.pages))
            val finished = sentence == order.lastSentenceId && summary.lastSentenceId == order.lastSentenceId
            val chapterNumber = labels.chapter?.number
            val playLabel = if (chapterNumber != null) "Play ${summary.title} from chapter $chapterNumber" else "Play ${summary.title} from page ${labels.pageLabel}"
            state.update { current ->
                current.copy(continueCard = current.continueCard?.takeIf { it.documentId == summary.id }?.copy(
                    location = labels.cardLocation(),
                    timeLeft = Progress.formatTimeLeft(order.secondsLeft(sentence, listening.speed.rate)),
                    finished = finished,
                    playLabel = playLabel,
                ))
            }
        }
    }

    private fun row(summary: DocumentSummary) = RecentRow(
        documentId = summary.id,
        title = summary.title,
        initials = Covers.initials(summary.title),
        hue = Covers.hue(summary.title),
        meta = "${summary.pageCount} pages · opened ${Progress.formatRelativeTime(summary.lastOpenedAtEpochS, now())}",
        percent = Progress.percentRead(summary.lastSentenceId, summary.sentenceCount),
    )

    override fun openBook(documentId: String, play: Boolean) = navigation.openReader(documentId, play)

    override fun togglePlayOnCard() {
        val card = state.value.continueCard ?: return
        val playing = playback.state.value
        if (playing.documentId == card.documentId) playback.togglePlayPause() else navigation.openReader(card.documentId, play = true)
    }

    override fun pickedPdf(uri: String) {
        if (!imports.enqueue(PdfSource(uri))) state.update { it.copy(snackbar = LibrarySnackbar.ImportsFull) }
    }

    override fun pickedAudiobook(uri: String) = importAudiobook(uri, fromAnotherApp = false)

    override fun acceptIncoming(key: Long) {
        val file = incoming.take(key) ?: return
        when (file.kind) {
            IncomingKind.Pdf -> pickedPdf(file.uri)
            IncomingKind.Audiobook -> importAudiobook(file.uri, fromAnotherApp = true)
        }
    }

    override fun declineIncoming(key: Long) {
        incoming.take(key)
    }

    private fun importAudiobook(uri: String, fromAnotherApp: Boolean) {
        viewModelScope.launch {
            try {
                when (val outcome = audiobooks.import(uri, fromAnotherApp)) {
                    is AudiobookImport.Linked -> state.update { it.copy(snackbar = LibrarySnackbar.AudiobookLinked(outcome.link.documentId, outcome.title)) }
                    AudiobookImport.BookMissing -> state.update { it.copy(audiobookBookMissing = AudiobookBookMissing) }
                    AudiobookImport.Foreign -> state.update { it.copy(snackbar = LibrarySnackbar.AudiobookForeign) }
                }
            } catch (error: LectriceError) {
                state.update { it.copy(snackbar = LibrarySnackbar.Error(error.name)) }
            }
        }
    }

    override fun listenToLinked(documentId: String) {
        if (playback.state.value.documentId == documentId) {
            // Already the player's book: switch in place (keeps play/pause), so a second load can't race the switch.
            playback.setAudioSource(AudioSource.Audiobook)
            navigation.openReader(documentId, play = false)
        } else {
            settings.setAudioSource(documentId, AudioSource.Audiobook)
            navigation.openReader(documentId, play = true)
        }
    }

    override fun dismissBookMissing() = state.update { it.copy(audiobookBookMissing = null) }

    private fun onImportOutcome(outcome: ImportOutcome) {
        when (outcome) {
            is ImportOutcome.Imported -> {
                removal.restoredByImport(outcome.result.summary.id)
                if (outcome.result.alreadyInLibrary) state.update { it.copy(snackbar = LibrarySnackbar.AlreadyInLibrary) }
                navigation.openReader(outcome.result.summary.id, play = false)
            }
            is ImportOutcome.Failed -> state.update { it.copy(importError = ImportError(outcome.error.name, outcome.displayName, outcome.error.bytes)) }
        }
    }

    override fun dismissImportError() = state.update { it.copy(importError = null) }

    override fun openBookSheet(documentId: String) {
        val summary = summaries.firstOrNull { it.id == documentId } ?: return
        state.update { it.copy(bookSheet = BookSheet(documentId, summary.title, Covers.initials(summary.title), Covers.hue(summary.title), null, null)) }
        viewModelScope.launch {
            val usage = runCatching { library.storageUsage() }.getOrNull()?.documents?.firstOrNull { it.documentId == documentId }
            state.update { current -> current.copy(bookSheet = current.bookSheet?.takeIf { it.documentId == documentId }?.copy(libraryBytes = usage?.libraryBytes ?: 0, audioBytes = usage?.cacheBytes ?: 0)) }
        }
    }

    override fun closeBookSheet() = state.update { it.copy(bookSheet = null) }

    override fun clearBookAudio(documentId: String) {
        viewModelScope.launch {
            try {
                library.clearDocumentAudio(documentId)
                state.update { current -> current.copy(bookSheet = current.bookSheet?.copy(audioBytes = 0)) }
            } catch (error: LectriceError) {
                state.update { it.copy(snackbar = LibrarySnackbar.Error(error.name)) }
            }
        }
    }

    override fun askRemove(documentId: String) {
        val sheet = state.value.bookSheet
        val summary = summaries.firstOrNull { it.id == documentId } ?: return
        val playing = playback.state.value
        state.update {
            it.copy(
                bookSheet = null,
                removeConfirm = RemoveConfirm(
                    documentId = documentId,
                    title = summary.title,
                    freesBytes = sheet?.let { s -> (s.libraryBytes ?: 0) + (s.audioBytes ?: 0) },
                    isPlaying = playing.documentId == documentId && playing.isPlaying,
                    hasRunningExport = false,
                    exportCount = 0,
                    exportBytes = 0,
                ),
            )
        }
    }

    override fun cancelRemove() = state.update { it.copy(removeConfirm = null) }

    override fun confirmRemove(deleteExports: Boolean) {
        val confirm = state.value.removeConfirm ?: return
        state.update { it.copy(removeConfirm = null) }
        viewModelScope.launch {
            try {
                removal.remove(confirm.documentId, confirm.title, deleteExports)
                summaries.removeAll { it.id == confirm.documentId }
                total = (total - 1).coerceAtLeast(0)
                publishList()
                state.update { it.copy(snackbar = LibrarySnackbar.Removed(confirm.title)) }
            } catch (error: LectriceError) {
                state.update { it.copy(snackbar = LibrarySnackbar.Error(error.name)) }
            }
        }
    }

    override fun undoRemove() {
        state.update { it.copy(snackbar = null) }
        viewModelScope.launch {
            try {
                removal.undo()
            } catch (error: LectriceError) {
                state.update { it.copy(snackbar = LibrarySnackbar.Error(error.name)) }
            }
            reload()
        }
    }

    override fun snackbarDismissed() {
        val removed = state.value.snackbar is LibrarySnackbar.Removed
        state.update { it.copy(snackbar = null) }
        if (removed) removal.purgeSoon()
    }

    override fun bannerAction() {
        when (state.value.banner) {
            LibraryBanner.VoiceNotInstalled -> navigation.openModelSetup()
            is LibraryBanner.Downloading -> navigation.openDownload()
            LibraryBanner.EngineFailed -> voice.retry()
            null -> Unit
        }
    }

    override fun openSettings() = navigation.openSettings()

    private fun banner(voiceState: VoiceState): LibraryBanner? = when (voiceState) {
        VoiceState.NotInstalled -> LibraryBanner.VoiceNotInstalled
        is VoiceState.Downloading -> LibraryBanner.Downloading(voiceState.percent)
        VoiceState.Failed -> LibraryBanner.EngineFailed
        VoiceState.Installed -> null
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MS_PER_SECOND = 1000L
    }
}
