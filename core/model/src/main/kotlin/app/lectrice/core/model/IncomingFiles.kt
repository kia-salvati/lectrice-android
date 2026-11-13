package app.lectrice.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class IncomingKind { Pdf, Audiobook }

/** A file another app handed over ("Open with" / "Share"), waiting for the user's yes. */
data class IncomingFile(val key: Long, val uri: String, val kind: IncomingKind, val displayName: String)

/**
 * What the app accepts from another app. The activity is exported, and an intent filter is not a check: any app can
 * send any action, URI and type straight to it.
 */
object IncomingRules {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    private const val SCHEME_CONTENT = "content"
    private const val PDF_TYPE = "application/pdf"
    private val AUDIOBOOK_TYPES = setOf("audio/mp4", "audio/x-m4b", "audio/m4b")
    private const val MAX_NAME_CHARS = 80
    private const val ELLIPSIS = "…"

    /**
     * The kind of file, or null when it is dropped: only `content:` URIs (not `file:` or `android.resource:`, which
     * could name the app's own private files), never one of the app's own providers, and only the types the app opens.
     */
    fun kind(action: String?, scheme: String?, authority: String?, mimeType: String?, ownPackage: String): IncomingKind? {
        if (action != ACTION_VIEW && action != ACTION_SEND) return null
        if (!scheme.equals(SCHEME_CONTENT, ignoreCase = true)) return null
        // "user@authority" names the same provider for another user profile.
        val provider = authority?.substringAfterLast('@')?.lowercase().orEmpty()
        if (provider.isEmpty() || provider == ownPackage || provider.startsWith("$ownPackage.")) return null
        return when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            PDF_TYPE -> IncomingKind.Pdf
            in AUDIOBOOK_TYPES -> IncomingKind.Audiobook
            else -> null
        }
    }

    /** A file name from another app, safe to show: one line, no control or direction-override characters, cut short. */
    fun displayName(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty().filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }.trim()
        return when {
            cleaned.isEmpty() -> fallback
            cleaned.length > MAX_NAME_CHARS -> cleaned.take(MAX_NAME_CHARS - 1) + ELLIPSIS
            else -> cleaned
        }
    }
}

/** The files waiting for a yes, oldest first. Nothing is copied or read until [take] hands one to an import. */
class IncomingFiles(private val maxWaiting: Int = MAX_WAITING) {
    private val state = MutableStateFlow<List<IncomingFile>>(emptyList())
    private var nextKey = 0L

    val waiting: StateFlow<List<IncomingFile>> = state.asStateFlow()

    /** False when too many are waiting already, or this URI is (an app sending files in a loop gets nowhere). */
    @Synchronized
    fun offer(uri: String, kind: IncomingKind, displayName: String): Boolean {
        val current = state.value
        if (current.size >= maxWaiting || current.any { it.uri == uri }) return false
        state.value = current + IncomingFile(nextKey++, uri, kind, displayName)
        return true
    }

    /** Removes the file (the user answered); null when it is gone already. */
    @Synchronized
    fun take(key: Long): IncomingFile? {
        val file = state.value.firstOrNull { it.key == key } ?: return null
        state.value = state.value - file
        return file
    }

    private companion object {
        const val MAX_WAITING = 5
    }
}
