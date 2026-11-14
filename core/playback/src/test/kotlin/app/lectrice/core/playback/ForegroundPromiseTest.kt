package app.lectrice.core.playback

import app.lectrice.core.playback.ForegroundPromise.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPromiseTest {
    @Test
    fun `a start Media3 ignores is answered at once and ended`() {
        assertEquals(Answer.KeepAndStop, ForegroundPromise.answer(inForeground = false, mediaKey = false))
    }

    @Test
    fun `a media key gets time to start playback first`() {
        assertEquals(Answer.KeepIfStillIdle, ForegroundPromise.answer(inForeground = false, mediaKey = true))
    }

    @Test
    fun `a service already in the foreground is left alone`() {
        assertEquals(Answer.Nothing, ForegroundPromise.answer(inForeground = true, mediaKey = false))
        assertEquals(Answer.Nothing, ForegroundPromise.answer(inForeground = true, mediaKey = true))
    }

    @Test
    fun `the grace time ends before Android's shortest limit`() {
        assertTrue(ForegroundPromise.MEDIA_KEY_GRACE_MS < 5_000L)
    }
}
