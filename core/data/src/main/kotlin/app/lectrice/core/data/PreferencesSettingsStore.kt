package app.lectrice.core.data

import android.content.Context
import android.content.SharedPreferences
import app.lectrice.core.model.AppSettings
import app.lectrice.core.model.AudioSource
import app.lectrice.core.model.BookListening
import app.lectrice.core.model.PlaybackSpeed
import app.lectrice.core.model.ReadAheadMinutes
import app.lectrice.core.model.SettingsStore
import app.lectrice.core.model.TextSize
import app.lectrice.core.model.ThemeSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which preference keys carry a book's id (the SHA-256 of the user's PDF) and so stay out of backups. */
internal object BookKeys {
    const val LAST_PLAYED = "lastPlayedDocumentId"
    const val VOICE_PREFIX = "voice."
    const val SPEED_PREFIX = "speed."
    const val SLOW_TIP_PREFIX = "slowTip."
    const val SOURCE_PREFIX = "source."
    private val PREFIXES = listOf(VOICE_PREFIX, SPEED_PREFIX, SLOW_TIP_PREFIX, SOURCE_PREFIX)

    fun isPerBook(key: String): Boolean = key == LAST_PLAYED || PREFIXES.any(key::startsWith)
}

/**
 * Settings in two small `SharedPreferences` files (a few keys; DataStore isn't worth a dependency here): `settings`
 * (app-wide, the only file in the backup rules) and `books` (everything keyed by a book's id: a backup must not say
 * which PDFs the user has, and after a restore those books aren't on the phone anyway). Reads once at start; every
 * update writes asynchronously (`apply`) and publishes the new value at once.
 */
class PreferencesSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val books: SharedPreferences = context.getSharedPreferences(BOOKS_FILE, Context.MODE_PRIVATE)

    init {
        moveBookKeys()
    }

    private val state = MutableStateFlow(read())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    @Synchronized
    override fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        if (next == state.value) return
        write(next)
        state.value = next
    }

    override fun bookListening(documentId: String): BookListening {
        val defaults = state.value
        return BookListening(
            voiceId = books.getString(VOICE_PREFIX + documentId, null) ?: defaults.defaultVoiceId,
            speed = if (books.contains(SPEED_PREFIX + documentId)) PlaybackSpeed.fromPercent(books.getInt(SPEED_PREFIX + documentId, 0)) else defaults.defaultSpeed,
        )
    }

    override fun setBookListening(documentId: String, listening: BookListening) {
        books.edit().putString(VOICE_PREFIX + documentId, listening.voiceId).putInt(SPEED_PREFIX + documentId, listening.speed.percent).apply()
    }

    override fun audioSource(documentId: String): AudioSource =
        AudioSource.entries.firstOrNull { it.name == books.getString(SOURCE_PREFIX + documentId, null) } ?: AudioSource.Generated

    override fun setAudioSource(documentId: String, source: AudioSource) = books.edit().putString(SOURCE_PREFIX + documentId, source.name).apply()

    override fun isSlowTipShown(documentId: String): Boolean = books.getBoolean(SLOW_TIP_PREFIX + documentId, false)

    override fun setSlowTipShown(documentId: String) = books.edit().putBoolean(SLOW_TIP_PREFIX + documentId, true).apply()

    override fun forgetBook(documentId: String) {
        books.edit().remove(VOICE_PREFIX + documentId).remove(SPEED_PREFIX + documentId).remove(SLOW_TIP_PREFIX + documentId).remove(SOURCE_PREFIX + documentId).apply()
    }

    /** Versions before the split kept the per-book keys in `settings` (which is backed up): move them once. */
    private fun moveBookKeys() {
        val old = prefs.all.filterKeys(BookKeys::isPerBook)
        if (old.isEmpty()) return
        val target = books.edit()
        old.forEach { (key, value) ->
            when (value) {
                is String -> target.putString(key, value)
                is Int -> target.putInt(key, value)
                is Boolean -> target.putBoolean(key, value)
            }
        }
        // The copy is on disk before the originals go.
        if (!target.commit()) return
        val source = prefs.edit()
        old.keys.forEach(source::remove)
        source.apply()
        Log.i(SCOPE, "moved per-book settings out of the backed-up file keys=${old.size}")
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = enumOr(prefs.getString(THEME, null), defaults.theme),
            textSize = enumOr(prefs.getString(TEXT_SIZE, null), defaults.textSize),
            defaultVoiceId = prefs.getString(DEFAULT_VOICE, null) ?: defaults.defaultVoiceId,
            defaultSpeed = PlaybackSpeed.fromPercent(prefs.getInt(DEFAULT_SPEED, defaults.defaultSpeed.percent)),
            readSkipped = prefs.getBoolean(READ_SKIPPED, defaults.readSkipped),
            readAhead = ReadAheadMinutes.fromMinutes(prefs.getInt(READ_AHEAD, defaults.readAhead.minutes)),
            sentenceOnLockScreen = prefs.getBoolean(LOCK_SENTENCE, defaults.sentenceOnLockScreen),
            listenOverLockScreen = prefs.getBoolean(LISTEN_OVER_LOCK, defaults.listenOverLockScreen),
            activeModelId = prefs.getString(ACTIVE_MODEL, null),
            threadsOverride = prefs.getInt(THREADS, 0).takeIf { it > 0 },
            firstRunDone = prefs.getBoolean(FIRST_RUN_DONE, false),
            lastPlayedDocumentId = books.getString(LAST_PLAYED, null),
            pillHintSeen = prefs.getBoolean(PILL_HINT, false),
            listenHintSeen = prefs.getBoolean(LISTEN_HINT, false),
            notificationAsked = prefs.getBoolean(NOTIFICATION_ASKED, false),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putString(THEME, settings.theme.name)
            .putString(TEXT_SIZE, settings.textSize.name)
            .putString(DEFAULT_VOICE, settings.defaultVoiceId)
            .putInt(DEFAULT_SPEED, settings.defaultSpeed.percent)
            .putBoolean(READ_SKIPPED, settings.readSkipped)
            .putInt(READ_AHEAD, settings.readAhead.minutes)
            .putBoolean(LOCK_SENTENCE, settings.sentenceOnLockScreen)
            .putBoolean(LISTEN_OVER_LOCK, settings.listenOverLockScreen)
            .putString(ACTIVE_MODEL, settings.activeModelId)
            .putInt(THREADS, settings.threadsOverride ?: 0)
            .putBoolean(FIRST_RUN_DONE, settings.firstRunDone)
            .putBoolean(PILL_HINT, settings.pillHintSeen)
            .putBoolean(LISTEN_HINT, settings.listenHintSeen)
            .putBoolean(NOTIFICATION_ASKED, settings.notificationAsked)
            .apply()
        books.edit().putString(LAST_PLAYED, settings.lastPlayedDocumentId).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val SCOPE = "Settings"
        const val FILE = "settings"
        /** Not in `backup_rules.xml` / `data_extraction_rules.xml`, on purpose. */
        const val BOOKS_FILE = "books"
        const val THEME = "theme"
        const val TEXT_SIZE = "textSize"
        const val DEFAULT_VOICE = "defaultVoice"
        const val DEFAULT_SPEED = "defaultSpeed"
        const val READ_SKIPPED = "readSkipped"
        const val READ_AHEAD = "readAhead"
        const val LOCK_SENTENCE = "lockScreenSentence"
        const val LISTEN_OVER_LOCK = "listenOverLock"
        const val ACTIVE_MODEL = "activeModel"
        const val THREADS = "threads"
        const val FIRST_RUN_DONE = "firstRunDone"
        const val LAST_PLAYED = BookKeys.LAST_PLAYED
        const val PILL_HINT = "hint.pill"
        const val LISTEN_HINT = "hint.listen"
        const val NOTIFICATION_ASKED = "notificationAsked"
        const val VOICE_PREFIX = BookKeys.VOICE_PREFIX
        const val SPEED_PREFIX = BookKeys.SPEED_PREFIX
        const val SLOW_TIP_PREFIX = BookKeys.SLOW_TIP_PREFIX
        const val SOURCE_PREFIX = BookKeys.SOURCE_PREFIX
    }
}
