package app.lectrice.core.link

import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.HelperAttention
import app.lectrice.core.model.HelperLink

/** The core's link state as the screens show it (file 24 §4). Pure, so the JVM tests can check every case. */
internal object HelperLinkState {
    sealed interface Core {
        data object Off : Core
        data object Connecting : Core
        data object Connected : Core
        data object Busy : Core
        data object Lost : Core
        data class NeedsAttention(val errorName: String) : Core
    }

    /**
     * [remembered]: what the computer last said needs the person (it forgot this phone, lacks the model, doesn't
     * match). It is still shown while the link isn't running, so the helper page can say "Pair again" when nobody listens.
     */
    fun of(core: Core, running: Boolean, permissionDenied: Boolean, remembered: HelperAttention? = null): HelperLink = when {
        permissionDenied -> HelperLink.NeedsAttention(HelperAttention.PermissionDenied)
        !running -> remembered?.let(HelperLink::NeedsAttention) ?: HelperLink.Off
        else -> when (core) {
            Core.Off, Core.Connecting -> HelperLink.Searching
            Core.Connected -> HelperLink.Connected
            Core.Busy -> HelperLink.Busy
            Core.Lost -> HelperLink.Lost
            is Core.NeedsAttention -> when (ErrorName.fromName(core.errorName)) {
                ErrorName.HelperNotPaired -> HelperLink.NeedsAttention(HelperAttention.NotPaired)
                ErrorName.HelperModelMissing -> HelperLink.NeedsAttention(HelperAttention.ModelMissing)
                ErrorName.HelperIncompatible -> HelperLink.NeedsAttention(HelperAttention.Incompatible)
                else -> HelperLink.Lost
            }
        }
    }

    /** What to remember from a running link: set by the computer's answer, cleared once it makes audio again. */
    fun remembered(previous: HelperAttention?, link: HelperLink): HelperAttention? = when {
        link is HelperLink.NeedsAttention && link.reason != HelperAttention.PermissionDenied -> link.reason
        link.makesAudio -> null
        else -> previous
    }

    /** Went from making audio to not reachable: the phone must get ready to take over (file 24 §3.6). */
    fun lostNow(before: HelperLink, after: HelperLink): Boolean = before.makesAudio && !after.makesAudio && after != HelperLink.Off
}
