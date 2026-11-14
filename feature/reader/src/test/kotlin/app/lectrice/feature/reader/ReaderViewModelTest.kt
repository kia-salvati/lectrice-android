package app.lectrice.feature.reader

import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.ComputerHelperRepository
import app.lectrice.core.model.Document
import app.lectrice.core.model.DocumentSummary
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.FoundComputer
import app.lectrice.core.model.HelperNotice
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.ImportResult
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.LibraryRepository
import app.lectrice.core.model.PairResult
import app.lectrice.core.model.PdfInfo
import app.lectrice.core.model.PdfSource
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.PrepareController
import app.lectrice.core.model.PrepareRequest
import app.lectrice.core.model.PrepareScope
import app.lectrice.core.model.PrepareState
import app.lectrice.core.model.StorageUsage
import app.lectrice.core.model.SummaryPage
import app.lectrice.core.model.TocEntry
import app.lectrice.core.model.VoiceAvailability
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.core.model.VoiceSampleState
import app.lectrice.core.model.VoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** Invented: chapter 1 holds sentences 0–3, chapter 2 sentences 4–5. */
    private val document = inventedDocument().copy(toc = listOf(TocEntry(0, "1. First", 1, 0, 1), TocEntry(1, "2. Second", 1, 4, 3)))
    private val playback = OpeningPlayback()
    private val navigation = RecordingNavigation()
    private val prepares = FakePrepares()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(library: LibraryRepository = FakeLibrary(document), slowPhone: Boolean = true): ReaderViewModel {
        val helper = FakeHelper()
        val model = ReaderViewModel(
            document.id, false, library, playback, FakeSettings(), FakeVoices(), NoSamples, ReadyVoice, navigation,
            PreparePresenter(document.id, prepares, FakeModels(slowPhone), helper) { 0L },
            AudiobookSourcePresenter(document.id, FakeAudiobooks(), playback),
            helper,
            clockMs = { 0L },
            computation = dispatcher,
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `opening shows the book and hands it to the player at its start`() = runTest(dispatcher) {
        val model = viewModel()

        assertTrue(model.uiState.value.content is ReaderContent.Ready)
        assertEquals(document.id to 0, playback.opened)
    }

    @Test
    fun `a book that is gone sends the reader back to the library with the reason`() = runTest(dispatcher) {
        viewModel(library = FakeLibrary(document, missing = true))

        assertEquals(listOf<ReaderMessage?>(ReaderMessage.BookGone), navigation.backs)
    }

    @Test
    fun `this chapter is prepared up to the next chapter on a slow phone`() = runTest(dispatcher) {
        val model = viewModel()
        assertEquals(PrepareSummary.Off, model.uiState.value.prepare?.summary)

        model.prepare(PrepareScope.ThisChapter)
        advanceUntilIdle()

        val request = prepares.requests.single()
        assertEquals(0 to 3, request.fromSentence to request.toSentence)
        assertEquals("Ch. 1", request.upToLabel)
        assertEquals(ReaderMessage.PrepareQueued, model.uiState.value.message)
    }

    @Test
    fun `a fast phone without a computer has no Prepare row`() = runTest(dispatcher) {
        assertNull(viewModel(slowPhone = false).uiState.value.prepare)
    }

    @Test
    fun `listening from a file that isn't linked sentence by sentence is refused with a message`() = runTest(dispatcher) {
        val model = viewModel()

        model.setAudioSource(AudioSource.Audiobook)

        assertEquals(ReaderMessage.AudiobookFailed(ErrorName.AudiobookNotLectrice), model.uiState.value.message)
        assertNull(playback.source)
    }
}

/** Records [open]; the state follows it like the real player (paused at the start sentence). */
private class OpeningPlayback : PlaybackControllerFake() {
    var opened: Pair<String, Int?>? = null
    var source: AudioSource? = null

    override fun open(document: Document, sentenceId: Int?, play: Boolean) {
        opened = document.id to sentenceId
        state.value = PlaybackState(documentId = document.id, currentSentenceId = sentenceId)
    }

    override fun setAudioSource(source: AudioSource) {
        this.source = source
    }
}

private open class PlaybackControllerFake : app.lectrice.core.model.PlaybackController by FakePlayback() {
    override val state = MutableStateFlow(PlaybackState())
}

private class RecordingNavigation : ReaderNavigation {
    val backs = mutableListOf<ReaderMessage?>()
    override fun backToLibrary(message: ReaderMessage?) {
        backs += message
    }
    override fun openModelSetup() = Unit
    override fun openComputerHelper() = Unit
}

private class FakeLibrary(private val document: Document, private val missing: Boolean = false) : LibraryRepository {
    private val summary = DocumentSummary(document.id, document.title, "invented.pdf", document.pageCount, 6, document.cleanerVersion, 0, 0, null)
    override suspend fun listRecent(limit: Int, offset: Int) = SummaryPage(listOf(summary), 1, limit, offset)
    override suspend fun describe(source: PdfSource) = PdfInfo("invented.pdf", null)
    override suspend fun importPdf(source: PdfSource, info: PdfInfo) = ImportResult(summary, alreadyInLibrary = false)
    override suspend fun getDocument(documentId: String, markOpened: Boolean): Document = if (missing) throw LectriceError(ErrorName.DocumentNotFound) else document
    override suspend fun summary(documentId: String) = if (missing) throw LectriceError(ErrorName.DocumentNotFound) else summary
    override suspend fun playbackOrder(documentId: String, readSkipped: Boolean) = (0 until 6).toList()
    override suspend fun savePosition(documentId: String, sentenceId: Int) = Unit
    override suspend fun currentCleanerVersion() = document.cleanerVersion
    override suspend fun removeDocument(documentId: String) = Unit
    override suspend fun restoreDocument(documentId: String) = summary
    override suspend fun purgeRemoved(deleteExportsFor: List<String>) = Unit
    override suspend fun clearDocumentAudio(documentId: String) = 0L
    override suspend fun storageUsage() = StorageUsage(1, 0, 0, 0, 0, 0, 0, emptyList())
    override suspend fun clearAudioCache() = 0L
}

private class FakePrepares : PrepareController {
    val requests = mutableListOf<PrepareRequest>()
    override val states = MutableStateFlow<Map<String, PrepareState>>(emptyMap())
    override fun prepare(request: PrepareRequest) {
        requests += request
    }
    override fun cancel(documentId: String) = Unit
}

private class FakeHelper : ComputerHelperRepository {
    override val state = MutableStateFlow(HelperState())
    override val notices = MutableSharedFlow<HelperNotice>()
    override val found = MutableStateFlow<List<FoundComputer>>(emptyList())
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override suspend fun pair(address: String, code: String) = PairResult.Unreachable
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun forget(peerId: String) = Unit
    override suspend fun rename(peerId: String, label: String?) = Unit
    override fun retry() = Unit
    override fun permissionChanged() = Unit
}

private object ReadyVoice : VoiceAvailability {
    override val state = MutableStateFlow(VoiceState.Installed)
    override fun retry() = Unit
}

private object NoSamples : VoiceSamplePlayer {
    override val state = MutableStateFlow(VoiceSampleState())
    override fun play(voiceId: String) = Unit
}
