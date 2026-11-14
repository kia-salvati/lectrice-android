package app.lectrice.core.playback

import app.lectrice.core.model.ErrorName
import app.lectrice.core.playback.PlaybackErrorPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorPolicyTest {
    private fun decide(name: ErrorName, fromFile: Boolean = false, retried: Boolean = false, failures: Int = 0) =
        PlaybackErrorPolicy.decide(name, fromFile, retried, failures)

    @Test
    fun `each error gets its own answer`() {
        assertEquals(Action.AudiobookMissing, decide(ErrorName.FileUnreadable, fromFile = true))
        assertEquals(Action.AudiobookMissing, decide(ErrorName.AudiobookUnreadable, fromFile = true))
        assertEquals(Action.Retry, decide(ErrorName.FileUnreadable, fromFile = false))
        assertEquals(Action.VoiceMissing, decide(ErrorName.ModelMissing))
        assertEquals(Action.VoiceFailed, decide(ErrorName.SpeechEngineUnavailable))
        assertEquals(Action.ReloadOrder, decide(ErrorName.SentenceNotFound))
        assertEquals(Action.DefaultVoice, decide(ErrorName.VoiceUnknown))
    }

    @Test
    fun `a sentence is retried once, then skipped, and the third skip in a row stops`() {
        assertEquals(Action.Retry, decide(ErrorName.SpeechSynthesisFailed))
        assertEquals(Action.Skip, decide(ErrorName.SpeechSynthesisFailed, retried = true, failures = 0))
        assertEquals(Action.Skip, decide(ErrorName.SpeechSynthesisFailed, retried = true, failures = 1))
        assertEquals(Action.VoiceFailed, decide(ErrorName.SpeechSynthesisFailed, retried = true, failures = 2))
    }
}
