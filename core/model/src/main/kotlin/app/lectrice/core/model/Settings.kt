package app.lectrice.core.model

import kotlinx.coroutines.flow.StateFlow

enum class ThemeSetting { System, Light, Dark, Black }

/** Reader text sizes (file 04 §3.8): paragraph sp; line height × 1.68; headings scale by the same factor. */
enum class TextSize(val paragraphSp: Int) {
    S(15), M(17), L(19), XL(22), XXL(26);

    /** Relative to the default L, for headings and other reader text. */
    val scale: Float get() = paragraphSp / L.paragraphSp.toFloat()

    companion object {
        val DEFAULT = L
    }
}

/** Read-ahead choices in listening-minutes (file 08). */
enum class ReadAheadMinutes(val minutes: Int) {
    Five(5), Ten(10), Twenty(20);

    companion object {
        val DEFAULT = Ten

        fun fromMinutes(minutes: Int): ReadAheadMinutes = entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

data class AppSettings(
    val theme: ThemeSetting = ThemeSetting.System,
    val textSize: TextSize = TextSize.DEFAULT,
    val defaultVoiceId: String = Voices.DEFAULT_VOICE_ID,
    val defaultSpeed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    val readSkipped: Boolean = false,
    val readAhead: ReadAheadMinutes = ReadAheadMinutes.DEFAULT,
    val sentenceOnLockScreen: Boolean = true,
    val listenOverLockScreen: Boolean = false,
    /** The model the user listens with (file 01); null before the first run picked one. */
    val activeModelId: String? = null,
    /** Speech threads; null = the phone-class default. */
    val threadsOverride: Int? = null,
    val firstRunDone: Boolean = false,
    /** For media resumption after process death (file 09 §3.9). */
    val lastPlayedDocumentId: String? = null,
    val pillHintSeen: Boolean = false,
    val listenHintSeen: Boolean = false,
    /** The notification permission was asked once (file 17 §3.2); never again. */
    val notificationAsked: Boolean = false,
)

/** Per-book listening choices (file 16). */
data class BookListening(val voiceId: String, val speed: PlaybackSpeed)

interface SettingsStore {
    val settings: StateFlow<AppSettings>

    fun update(change: (AppSettings) -> AppSettings)

    /** The book's own voice and speed, else the defaults. */
    fun bookListening(documentId: String): BookListening

    fun setBookListening(documentId: String, listening: BookListening)

    /** Where the book's audio comes from (file 23); [AudioSource.Generated] until the user picks the audiobook. */
    fun audioSource(documentId: String): AudioSource

    fun setAudioSource(documentId: String, source: AudioSource)

    fun isSlowTipShown(documentId: String): Boolean

    fun setSlowTipShown(documentId: String)

    /** Forgets everything stored for a removed book. */
    fun forgetBook(documentId: String)
}
