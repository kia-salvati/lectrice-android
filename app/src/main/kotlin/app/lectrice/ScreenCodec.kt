package app.lectrice

/**
 * The back stack as strings for the Activity's saved state, so a font-size change or process death reopens the same
 * screens. A book comes back paused; entries that no longer parse are dropped.
 */
object ScreenCodec {
    private const val SEPARATOR = ':'
    private const val READER = "Reader"
    private const val STORAGE = "Storage"

    fun encode(stack: List<Screen>): ArrayList<String> = stack.mapTo(ArrayList()) { screen ->
        when (screen) {
            is Screen.Reader -> "$READER$SEPARATOR${screen.documentId}"
            is Screen.Storage -> "$STORAGE$SEPARATOR${screen.requestedBytes ?: ""}"
            else -> simple.entries.first { it.value == screen }.key
        }
    }

    fun decode(saved: List<String>): List<Screen> = saved.mapNotNull { entry ->
        val name = entry.substringBefore(SEPARATOR)
        val argument = entry.substringAfter(SEPARATOR, "")
        when (name) {
            READER -> argument.takeIf { it.isNotEmpty() }?.let { Screen.Reader(it, play = false) }
            STORAGE -> Screen.Storage(argument.toLongOrNull())
            else -> simple[name]
        }
    }

    private val simple: Map<String, Screen> = listOf(
        Screen.Library, Screen.Settings, Screen.Welcome, Screen.ChooseModel, Screen.Download, Screen.ModelPage, Screen.Licenses, Screen.ComputerHelper,
    ).associateBy { it.toString() }
}
