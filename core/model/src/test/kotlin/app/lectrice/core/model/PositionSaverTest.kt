package app.lectrice.core.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PositionSaverTest {
    private class RecordingLibrary : LibraryRepository by NotUsed {
        val saves = mutableListOf<Pair<String, Int>>()

        override suspend fun savePosition(documentId: String, sentenceId: Int) {
            saves += documentId to sentenceId
        }
    }

    @Test
    fun playingSavesTheLastIdOncePerWindow() = runTest {
        val library = RecordingLibrary()
        val saver = PositionSaver(library, backgroundScope)
        saver.playingAt("d", 1)
        saver.playingAt("d", 2)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(emptyList<Pair<String, Int>>(), library.saves)
        saver.playingAt("d", 3)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("d" to 3), library.saves)
    }

    @Test
    fun saveNowFlushesAtOnceAndSkipsRepeats() = runTest {
        val library = RecordingLibrary()
        val saver = PositionSaver(library, backgroundScope)
        saver.playingAt("d", 4)
        saver.saveNow("d", 5)
        runCurrent()
        saver.saveNow("d", 5)
        runCurrent()
        assertEquals(listOf("d" to 5), library.saves)
    }
}

/** A repository whose calls fail: tests override only what they use. */
object NotUsed : LibraryRepository {
    override suspend fun listRecent(limit: Int, offset: Int) = error("unused")
    override suspend fun describe(source: PdfSource) = error("unused")
    override suspend fun importPdf(source: PdfSource, info: PdfInfo) = error("unused")
    override suspend fun getDocument(documentId: String) = error("unused")
    override suspend fun summary(documentId: String) = error("unused")
    override suspend fun playbackOrder(documentId: String, readSkipped: Boolean) = error("unused")
    override suspend fun savePosition(documentId: String, sentenceId: Int) = error("unused")
    override suspend fun currentCleanerVersion() = error("unused")
    override suspend fun removeDocument(documentId: String) = error("unused")
    override suspend fun restoreDocument(documentId: String) = error("unused")
    override suspend fun purgeRemoved(deleteExportsFor: List<String>) = error("unused")
    override suspend fun clearDocumentAudio(documentId: String) = error("unused")
    override suspend fun storageUsage() = error("unused")
    override suspend fun clearAudioCache() = error("unused")
}
