package app.lectrice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * One [ViewModelStore] per screen on the back stack, so a screen's ViewModels end when it leaves the stack. Without it
 * they lived as long as the Activity: re-opening a book reused its old reader ViewModel, which never told the player
 * to switch to that book.
 *
 * A store is cleared only once its screen is off the stack **and** no longer composed (the outgoing screen of a
 * transition still draws, and must not create its ViewModels again in a cleared store).
 */
class ScreenStores {
    private val stores = mutableMapOf<String, ViewModelStore>()
    private val composed = mutableMapOf<String, Int>()
    private var stackKeys: Set<String> = emptySet()

    @Composable
    fun Scope(screen: Screen, stack: List<Screen>, content: @Composable () -> Unit) {
        val key = screen.storeKey
        val store = stores.getOrPut(key) { ViewModelStore() }
        LaunchedEffect(stack) { retainOnly(stack) }
        DisposableEffect(key) {
            composed[key] = (composed[key] ?: 0) + 1
            onDispose {
                composed[key] = (composed[key] ?: 1) - 1
                clearIfGone(key)
            }
        }
        val owner = remember(store) {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = store
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }

    private fun retainOnly(stack: List<Screen>) {
        stackKeys = stack.mapTo(mutableSetOf()) { it.storeKey }
        stores.keys.toList().forEach(::clearIfGone)
    }

    private fun clearIfGone(key: String) {
        if (key in stackKeys || (composed[key] ?: 0) > 0) return
        stores.remove(key)?.clear()
        composed.remove(key)
    }
}

/** Screens that share one ViewModel share a key: the model setup steps (S2 → S3 replaces the top) and the model page. */
private val Screen.storeKey: String
    get() = when (this) {
        Screen.ChooseModel, Screen.Download, Screen.ModelPage -> "models"
        else -> toString()
    }
