package app.lectrice.core.playback

/**
 * A service started with `startForegroundService` must show a foreground notification within seconds, or Android
 * kills the whole app. Media3 does that only once something plays, so a start that leads to no playback (a media key
 * with nothing to resume, an intent Media3 doesn't know) has to be answered here.
 */
internal object ForegroundPromise {
    enum class Answer {
        /** Already in the foreground: the promise is kept. */
        Nothing,

        /** A media key: Media3 may be about to play. Give it [MEDIA_KEY_GRACE_MS], then keep the promise if it didn't. */
        KeepIfStillIdle,

        /** Nothing Media3 acts on: keep the promise now and end this start. */
        KeepAndStop,
    }

    /** Well under the shortest limit Android gives a foreground start (5 s on old versions, 10 s on Android 12). */
    const val MEDIA_KEY_GRACE_MS = 2_000L

    fun answer(inForeground: Boolean, mediaKey: Boolean): Answer = when {
        inForeground -> Answer.Nothing
        mediaKey -> Answer.KeepIfStillIdle
        else -> Answer.KeepAndStop
    }
}
