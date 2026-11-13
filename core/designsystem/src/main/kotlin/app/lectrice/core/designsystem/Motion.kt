package app.lectrice.core.designsystem

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** True when the system "Remove animations" is on: every transition is then instant (README §4.5). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

const val FADE_MS = 150
const val HIGHLIGHT_MS = 120

/** The default spring (README §4.1), or a snap under reduced motion. */
fun <T> motionSpring(reduced: Boolean): AnimationSpec<T> =
    if (reduced) snap() else spring(dampingRatio = SPRING_DAMPING, stiffness = Spring.StiffnessMediumLow)

fun <T> motionTween(reduced: Boolean, millis: Int = FADE_MS): AnimationSpec<T> = if (reduced) snap() else tween(millis)

private const val SPRING_DAMPING = 0.85f
