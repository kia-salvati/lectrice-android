package app.lectrice.feature.reader

import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.PauseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val document = inventedDocument()
    private val platform = FakeExportPlatform()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(exports: FakeExports): ExportViewModel {
        val model = ExportViewModel(document, exports, FakeModels(), FakeVoices(), FakePlayback(), FakeSettings(), platform, FakeAudiobooks())
        advanceUntilIdle()
        return model
    }

    @Test
    fun `start runs the export in the work service and the page follows its progress`() = runTest(dispatcher) {
        val exports = FakeExports()
        val model = viewModel(exports)
        assertTrue(model.uiState.value.page is ExportPage.Choose)
        assertTrue(model.uiState.value.footer!!.fits)

        model.start()
        advanceUntilIdle()

        assertEquals(1, exports.started.size)
        assertEquals(1, platform.workStarts)
        assertEquals(0, (model.uiState.value.page as ExportPage.Progress).export.percent)

        exports.list.value = listOf(export(document.id, ExportStatus.Running, done = 40))
        advanceUntilIdle()
        assertEquals(40, (model.uiState.value.page as ExportPage.Progress).export.percent)

        exports.list.value = listOf(export(document.id, ExportStatus.Done, done = 100))
        advanceUntilIdle()
        assertTrue(model.uiState.value.page is ExportPage.Done)
    }

    @Test
    fun `an export that doesn't fit is not started`() = runTest(dispatcher) {
        val exports = FakeExports(free = 1_000_000)
        val model = viewModel(exports)
        assertFalse(model.uiState.value.footer!!.fits)

        model.start()
        advanceUntilIdle()

        assertTrue(exports.started.isEmpty())
        assertEquals(0, platform.workStarts)
    }

    @Test
    fun `resuming a paused export runs the work service again`() = runTest(dispatcher) {
        val exports = FakeExports()
        exports.list.value = listOf(export(document.id, ExportStatus.Paused, done = 30).copy(pausedBy = PauseReason.User))
        val model = viewModel(exports)
        assertEquals(ProgressKind.PausedByUser, (model.uiState.value.page as ExportPage.Progress).kind)

        model.resume()
        advanceUntilIdle()

        assertEquals(listOf("e1"), exports.resumed)
        assertEquals(1, platform.workStarts)
    }
}
