package app.lectrice.spike

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val THREAD_CHOICES = listOf(2, 4, 6, 8)
private val PROVIDERS = listOf("cpu", "xnnpack", "nnapi")
/** The plan adds 6 threads only on the S26 Ultra (Android 16); the S10+ ends at Android 12. */
private const val SIX_THREADS_FROM_API = 36

/** One screen of plain buttons and a text log (plan §5.2). No design. */
class MainActivity : ComponentActivity() {
    private val controller by lazy { SpikeController(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Runs last up to 90 minutes; the screen stays on so the phone doesn't sleep through them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.start()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { SpikeScreen(controller, ::startShare) } } }
    }

    private fun startShare() {
        controller.shareIntent()?.let(::startActivity)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpikeScreen(controller: SpikeController, onShare: () -> Unit) {
    val log by controller.log.collectAsState()
    val busy by controller.busy.collectAsState()
    var bundle by remember { mutableStateOf(ModelBundle.FP32) }
    var threads by remember { mutableIntStateOf(4) }
    var provider by remember { mutableStateOf("cpu") }
    var withSixThreads by remember { mutableStateOf(Build.VERSION.SDK_INT >= SIX_THREADS_FROM_API) }
    val choice = EngineChoice(bundle, threads, provider)
    val benchThreads = if (withSixThreads) THREAD_CHOICES else THREAD_CHOICES - 6
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::importPdf) }

    Column(Modifier.safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lectrice spike · ${BuildConfig.SHERPA_SET} · core ${BuildConfig.CORE_REVISION}", style = MaterialTheme.typography.titleMedium)
        ChipRow(ModelBundle.entries, bundle, { it.name.lowercase() }) { bundle = it }
        ChipRow(THREAD_CHOICES, threads, { "$it threads" }) { threads = it }
        ChipRow(PROVIDERS, provider, { it }) { provider = it }
        FilterChip(selected = withSixThreads, onClick = { withSixThreads = !withSixThreads }, label = { Text("bench 6 threads too") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !busy) { Text("1 Import PDF") }
            Button(onClick = { controller.loadEngine(choice) }, enabled = !busy) { Text("2 Engine") }
            Button(onClick = { controller.benchAll(benchThreads) }, enabled = !busy) { Text("3 Bench") }
            Button(onClick = controller::accelerators, enabled = !busy) { Text("3b Accelerators") }
            Button(onClick = { controller.soak(choice) }, enabled = !busy) { Text("4 Soak 10 min") }
            Button(onClick = controller::play, enabled = !busy) { Text("5 Play 2 min") }
            Button(onClick = { controller.runAll(choice, benchThreads) }, enabled = !busy) { Text("6 Run all") }
            Button(onClick = onShare, enabled = !busy) { Text("7 Share results") }
        }
        LogView(log)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) }) }
    }
}

@Composable
private fun LogView(lines: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.lastIndex) }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
