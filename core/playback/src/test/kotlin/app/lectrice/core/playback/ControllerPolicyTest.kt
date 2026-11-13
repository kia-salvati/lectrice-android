package app.lectrice.core.playback

import app.lectrice.core.playback.ControllerPolicy.Access
import app.lectrice.core.playback.ControllerPolicy.Caller
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerPolicyTest {
    private val stranger = Caller(sameApp = false, trusted = false, car = false, packageVerified = true)

    @Test
    fun `another app without the user's trust is refused`() {
        assertEquals(Access.Refused, ControllerPolicy.access(stranger))
    }

    @Test
    fun `the app itself gets everything`() {
        assertEquals(Access.Full, ControllerPolicy.access(stranger.copy(sameApp = true)))
    }

    @Test
    fun `the system UI, Bluetooth and notification listeners control but don't see the playlist`() {
        assertEquals(Access.Limited, ControllerPolicy.access(stranger.copy(trusted = true)))
    }

    @Test
    fun `a car counts only when the system verified its package name`() {
        assertEquals(Access.Limited, ControllerPolicy.access(stranger.copy(car = true)))
        assertEquals(Access.Refused, ControllerPolicy.access(stranger.copy(car = true, packageVerified = false)))
    }
}
