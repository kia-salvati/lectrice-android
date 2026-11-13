package app.lectrice.core.playback

/**
 * Who may use the media session. The service has to be exported (the system UI, Bluetooth and cars bind to it), so any
 * installed app can ask for a connection; the items carry the sentence being read, the book's title and its id.
 */
internal object ControllerPolicy {
    enum class Access {
        /** The app itself (its own controller and Media3's notification controller): everything. */
        Full,

        /** The system and what the user trusts with media: control and the current item, never the playlist. */
        Limited,

        Refused,
    }

    /**
     * What Media3 knows about a controller that asks to connect.
     * [trusted]: holds `MEDIA_CONTENT_CONTROL` (system UI, Bluetooth) or is a notification listener the user allowed
     * (Wear, Android Auto). [car]: Android Auto or Automotive by package name, which only counts when the system
     * [packageVerified] that the caller owns that name.
     */
    data class Caller(val sameApp: Boolean, val trusted: Boolean, val car: Boolean, val packageVerified: Boolean)

    fun access(caller: Caller): Access = when {
        caller.sameApp -> Access.Full
        caller.trusted || (caller.car && caller.packageVerified) -> Access.Limited
        else -> Access.Refused
    }
}
