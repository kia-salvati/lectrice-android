package app.lectrice.feature.reader

import app.lectrice.core.model.HelperAttention
import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.PlaybackState
import app.lectrice.core.model.Sizes

/** The player sheet's computer-helper line (file 24 §4, §11.2). Pure. */
internal object HelperLines {
    private const val MS_PER_MINUTE = 60_000

    fun of(helper: HelperState, playback: PlaybackState): HelperLineUi? {
        val name = helper.active?.shownName ?: return null
        if (!helper.enabled) return null
        val ready = minutesReady(playback.readyAheadMs, playback.speed.rate)
        val text = when (val link = helper.link) {
            HelperLink.Off -> return null
            HelperLink.Searching -> "Looking for $name…"
            HelperLink.Connected -> "Making audio on $name · $ready"
            HelperLink.Busy -> "$name is busy · $ready"
            HelperLink.Lost -> "Computer not reachable · $ready"
            is HelperLink.NeedsAttention -> when (link.reason) {
                HelperAttention.ModelMissing -> {
                    val model = helper.neededModel?.let { "the ${it.name} voice (${Sizes.format(it.downloadBytes)})" } ?: "this phone's voice model"
                    "$name needs $model to help. Open Lectrice on the computer to download it."
                }
                HelperAttention.Incompatible -> "Lectrice on $name and on this phone don't match. Update the older one."
                HelperAttention.NotPaired -> "$name forgot this phone. Pair again."
                HelperAttention.PermissionDenied -> "Allow Nearby devices to use your computer."
            }
        }
        val attention = helper.link is HelperLink.NeedsAttention
        val mark = helper.link.makesAudio
        return HelperLineUi(text, needsAttention = attention, pillMark = mark, pillSpoken = if (mark) ", audio from $name" else null)
    }

    /** Listening minutes ready ahead at this speed, rounded down (file 24 §11.2). */
    fun minutesReady(readyAheadMs: Long, rate: Float): String {
        val minutes = (readyAheadMs / rate.coerceAtLeast(MIN_RATE) / MS_PER_MINUTE).toInt()
        return if (minutes < 1) "under a minute ready" else "$minutes min ready"
    }

    private const val MIN_RATE = 0.1f
}
