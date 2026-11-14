package app.lectrice.core.data

import android.util.Log as AndroidLog

/**
 * The one logging wrapper (README §4.6): ids, counts, sizes, durations and error names only, never book content
 * (titles, file names, sentence text). Release builds keep `info` and above.
 */
object Log {
    private const val TAG = "Lectrice"

    fun i(scope: String, message: String) {
        AndroidLog.i(TAG, "$scope: $message")
    }

    /** Only the error's class and registry name are logged: messages may carry file names. */
    fun w(scope: String, message: String, error: Throwable? = null) {
        val cause = error?.let { " (${it.javaClass.simpleName}${(it as? app.lectrice.core.model.LectriceError)?.let { e -> ": ${e.name}" } ?: ""})" }.orEmpty()
        AndroidLog.w(TAG, "$scope: $message$cause")
    }

    fun d(scope: String, message: String) {
        if (AndroidLog.isLoggable(TAG, AndroidLog.DEBUG)) AndroidLog.d(TAG, "$scope: $message")
    }
}
