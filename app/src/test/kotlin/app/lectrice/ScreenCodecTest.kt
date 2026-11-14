package app.lectrice

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCodecTest {
    private val documentId = "ab".repeat(32)

    @Test
    fun `every screen survives a round trip and a book comes back paused`() {
        val stack = listOf(
            Screen.Library, Screen.Reader(documentId, play = true), Screen.Settings, Screen.Welcome, Screen.ChooseModel, Screen.Download,
            Screen.ModelPage, Screen.Storage(), Screen.Storage(1_234), Screen.Licenses, Screen.ComputerHelper,
        )

        val restored = ScreenCodec.decode(ScreenCodec.encode(stack))

        assertEquals(stack.map { if (it is Screen.Reader) it.copy(play = false) else it }, restored)
    }

    @Test
    fun `entries that don't parse are dropped`() {
        assertEquals(listOf(Screen.Library), ScreenCodec.decode(listOf("Library", "Reader:", "Gone", "")))
    }
}
