package app.lectrice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The screens we control (spec §4.5: one Activity, our own back stack). Sheets are screen state, not screens. */
sealed interface Screen {
    data object Library : Screen

    data class Reader(val documentId: String, val play: Boolean) : Screen

    data object Settings : Screen

    /** S1 → S2 → S3/S4 (file 01). */
    data object Welcome : Screen

    data object ChooseModel : Screen

    data object Download : Screen

    /** T2 */
    data object ModelPage : Screen

    /** T3 (file 20); [requestedBytes]: opened from an export that didn't fit ("Free up about …"). */
    data class Storage(val requestedBytes: Long? = null) : Screen

    data object Licenses : Screen
}

/** A tiny back stack. [push] and [replaceTop] animate forward; [pop] animates back. */
class Navigator(start: List<Screen>) {
    var stack by mutableStateOf(start)
        private set

    /** True while the last change went back (for the transition direction). */
    var lastWasBack by mutableStateOf(false)
        private set

    val top: Screen get() = stack.last()

    fun push(screen: Screen) {
        lastWasBack = false
        stack = stack.filterNot { it == screen } + screen
    }

    fun replaceTop(screen: Screen) {
        lastWasBack = false
        stack = stack.dropLast(1) + screen
    }

    /** Back to [screen] if it's in the stack, else to a stack of only it. */
    fun resetTo(screen: Screen) {
        lastWasBack = true
        val index = stack.indexOfLast { it::class == screen::class }
        stack = if (index >= 0) stack.take(index) + screen else listOf(screen)
    }

    /** False when there's nothing to go back to (the system then leaves the app). */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        lastWasBack = true
        stack = stack.dropLast(1)
        return true
    }
}
