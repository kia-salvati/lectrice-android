package app.lectrice.core.work

import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.PauseReason
import app.lectrice.core.model.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineRebuildTest {
    private fun export(id: String, status: ExportStatus, pausedBy: PauseReason? = null) = AudioExport(
        id = id, documentId = "doc", voiceId = "af_heart", speed = PlaybackSpeed.X1, engineId = "engine", range = null, rangeLabel = null,
        title = "The Kettle Ledger", status = status, pausedBy = pausedBy, assembling = false, error = null, sentencesDone = 10,
        sentencesTotal = 100, partsDone = 0, partsTotal = 2, secondsLeft = null, fileBytes = null, filePath = null, sharedUri = null,
        waitingForEngine = false,
    )

    @Test
    fun anExportThatRanAndWasPausedByTheSystemWithoutACauseContinues() {
        val exports = listOf(export("a", ExportStatus.Paused, PauseReason.System))

        assertEquals(listOf("a"), pausedByEngineRebuild(setOf("a"), exports, emptySet()))
    }

    @Test
    fun heatBatteryAndTimeLimitPausesStayPaused() {
        val exports = listOf(export("a", ExportStatus.Paused, PauseReason.System))

        assertEquals(emptyList<String>(), pausedByEngineRebuild(setOf("a"), exports, setOf("a")))
    }

    @Test
    fun userPausesAndExportsThatWereNotRunningStayAsTheyAre() {
        val exports = listOf(
            export("user", ExportStatus.Paused, PauseReason.User),
            export("old", ExportStatus.Paused, PauseReason.System),
            export("running", ExportStatus.Running),
        )

        assertEquals(emptyList<String>(), pausedByEngineRebuild(setOf("user", "running"), exports, emptySet()))
    }
}
