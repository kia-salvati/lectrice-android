package app.lectrice.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lectrice.core.designsystem.LocalReducedMotion
import app.lectrice.core.designsystem.motionSpring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ReaderSheet { None, Player, Chapters, Speed, Sleep, AudioSource }

/**
 * The reader's screen state: which sheet is open, follow-scroll, the collapsed pill and the Listen view. The values
 * handed in are saved with the screen, so the reader and the Listen view come back after the process was killed.
 */
@Stable
internal class ReaderScreenState(
    sheet: MutableState<ReaderSheet>,
    exportOpen: MutableState<Boolean>,
    listenOpen: MutableState<Boolean>,
    collapsed: MutableState<Boolean>,
    follow: MutableState<Boolean>,
    voiceFailedHidden: MutableState<Boolean>,
    hintShown: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val reducedMotion: State<Boolean>,
    private val onListenViewChanged: State<(Boolean) -> Unit>,
) {
    var sheet by sheet
    var exportOpen by exportOpen
    var listenOpen by listenOpen
        private set
    var collapsed by collapsed
    var follow by follow
    var voiceFailedHidden by voiceFailedHidden
    var hintShown by hintShown

    // A restored screen comes back with the Listen view already open, so it starts fully open instead of at the pill.
    val listenProgress = Animatable(if (listenOpen.value) 1f else 0f)

    /** The Listen view has finished opening and hides everything under it. */
    val listenFullyOpen by derivedStateOf { this.listenOpen && listenProgress.value >= 1f }
    var origin by mutableStateOf(Offset.Zero)
    var pillBounds by mutableStateOf<Rect?>(null)
    var lastTouchMs by mutableLongStateOf(System.currentTimeMillis())
    var locked by mutableStateOf(false)

    /** The slow-phone tip's measured height: the text list gets that much extra bottom padding so the tip never covers it. */
    var tipHeight: Dp by mutableStateOf(0.dp)

    fun openListen() {
        if (listenOpen) return
        listenOpen = true
        onListenViewChanged.value(true)
        scope.launch {
            listenProgress.snapTo(0f)
            if (reducedMotion.value) listenProgress.snapTo(1f) else listenProgress.animateTo(1f, motionSpring(reducedMotion.value))
        }
    }

    fun closeListen() {
        if (!listenOpen) return
        follow = true
        scope.launch {
            if (reducedMotion.value) listenProgress.snapTo(0f) else listenProgress.animateTo(0f, motionSpring(reducedMotion.value))
            listenOpen = false
            collapsed = false
            onListenViewChanged.value(false)
        }
    }

    /** Leaves the Listen view at once, without its closing animation (the screen is going away). */
    fun dropListen() {
        if (!listenOpen) return
        listenOpen = false
        onListenViewChanged.value(false)
    }

    fun closeSheet() {
        exportOpen = false
        sheet = ReaderSheet.None
    }
}

@Composable
internal fun rememberReaderScreenState(onListenViewChanged: (Boolean) -> Unit): ReaderScreenState {
    val sheet = rememberSaveable { mutableStateOf(ReaderSheet.None) }
    val exportOpen = rememberSaveable { mutableStateOf(false) }
    val listenOpen = rememberSaveable { mutableStateOf(false) }
    val collapsed = rememberSaveable { mutableStateOf(false) }
    val follow = rememberSaveable { mutableStateOf(true) }
    val voiceFailedHidden = rememberSaveable { mutableStateOf(false) }
    val hintShown = rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberUpdatedState(LocalReducedMotion.current)
    val listenChanged = rememberUpdatedState(onListenViewChanged)
    return remember { ReaderScreenState(sheet, exportOpen, listenOpen, collapsed, follow, voiceFailedHidden, hintShown, scope, reducedMotion, listenChanged) }
}
