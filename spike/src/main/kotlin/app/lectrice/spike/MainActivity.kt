package app.lectrice.spike

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import java.io.File

private val THREAD_CHOICES = listOf(2, 4, 6, 8)
private val PROVIDERS = listOf("cpu", "xnnpack", "nnapi")
/** The plan adds 6 threads only on the S26 Ultra (Android 16); the S10+ ends at Android 12. */
private const val SIX_THREADS_FROM_API = 36
private const val DEFAULT_THREADS = 4
private const val EXTRA_STEP = "step"
private const val IMPORT_DIR = "import"
private const val TAG = "LectriceSpike"

/** One screen of plain buttons and a text log (plan §5.2). No design. */
class MainActivity : ComponentActivity() {
    private val controller by lazy { SpikeController(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Runs last up to 90 minutes; the screen stays on so the phone doesn't sleep through them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.start()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { SpikeScreen(controller, ::startShare) } } }
        intent?.let(::runAdbStep)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        runAdbStep(intent)
    }

    /**
     * Drives the buttons over adb for the automated runs, e.g.
     * `am start -n <pkg>/app.lectrice.spike.MainActivity --es step engine --es bundle int8 --ei threads 4 --es provider cpu`.
     * `--es cores all|big|<cpu list>` pins the speech thread before the engine loads (default all), for engine, bench and soak.
     * Steps: import (`--es file <name>` in the app's external files folder `import/`), engine,
     * bench (`--es threadList 2,4,8`, optional `--es bundles fp32,small,fast,int8`; default: every bundle on the phone),
     * accelerators (optional `--ei fp32Threads`, `--ei int8Threads`), play, soakWhenUnplugged (without `bundle`: this
     * session's fastest CPU combination; optional `--es batteryRuns int8:4:xnnpack,...` or `none`).
     * One step at a time: each logs "<step> done" (or "failed") when finished. spike/run-phone.sh drives them all.
     */
    private fun runAdbStep(intent: Intent) {
        val step = intent.getStringExtra(EXTRA_STEP) ?: return
        intent.removeExtra(EXTRA_STEP)
        val choice = EngineChoice(
            ModelBundle.valueOf((intent.getStringExtra("bundle") ?: "fp32").uppercase()),
            intent.getIntExtra("threads", DEFAULT_THREADS),
            intent.getStringExtra("provider") ?: "cpu",
            CorePolicy.parse(intent.getStringExtra("cores") ?: "all"),
        )
        when (step) {
            "import" -> controller.importPdf(Uri.fromFile(File(getExternalFilesDir(IMPORT_DIR), requireNotNull(intent.getStringExtra("file")))))
            "engine" -> controller.loadEngine(choice)
            "bench" -> controller.benchAll(
                intent.getStringExtra("threadList")?.split(",")?.map(String::toInt) ?: THREAD_CHOICES - 6,
                choice.cores,
                intent.getStringExtra("bundles")?.split(",")?.map { ModelBundle.valueOf(it.trim().uppercase()) },
            )
            "accelerators" -> controller.accelerators(
                mapOf(ModelBundle.FP32 to intent.getIntExtra("fp32Threads", 0), ModelBundle.INT8 to intent.getIntExtra("int8Threads", 0))
                    .filterValues { it > 0 }.ifEmpty { null },
            )
            "play" -> controller.play()
            "soakWhenUnplugged" -> controller.soakWhenUnplugged(
                choice.takeIf { intent.hasExtra("bundle") },
                intent.getStringExtra("batteryRuns")?.let(::parseBatteryRuns),
            )
            else -> Log.w(TAG, "unknown adb step $step")
        }
    }

    private fun parseBatteryRuns(spec: String): List<EngineChoice> = if (spec == "none") emptyList() else spec.split(",").map {
        val (bundle, threads, provider) = it.split(":")
        EngineChoice(ModelBundle.valueOf(bundle.uppercase()), threads.toInt(), provider)
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
    var threads by remember { mutableIntStateOf(DEFAULT_THREADS) }
    var provider by remember { mutableStateOf("cpu") }
    var cores by remember { mutableStateOf<CorePolicy>(CorePolicy.All) }
    var withSixThreads by remember { mutableStateOf(Build.VERSION.SDK_INT >= SIX_THREADS_FROM_API) }
    val choice = EngineChoice(bundle, threads, provider, cores)
    val benchThreads = if (withSixThreads) THREAD_CHOICES else THREAD_CHOICES - 6
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::importPdf) }

    Column(Modifier.safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lectrice spike · ${BuildConfig.SHERPA_SET} · core ${BuildConfig.CORE_REVISION}", style = MaterialTheme.typography.titleMedium)
        ChipRow(ModelBundle.entries, bundle, { it.name.lowercase() }) { bundle = it }
        ChipRow(THREAD_CHOICES, threads, { "$it threads" }) { threads = it }
        ChipRow(PROVIDERS, provider, { it }) { provider = it }
        ChipRow(listOf(CorePolicy.All, CorePolicy.Big), cores, { "${it.label} cores" }) { cores = it }
        FilterChip(selected = withSixThreads, onClick = { withSixThreads = !withSixThreads }, label = { Text("bench 6 threads too") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !busy) { Text("1 Import PDF") }
            Button(onClick = { controller.loadEngine(choice) }, enabled = !busy) { Text("2 Engine") }
            Button(onClick = { controller.benchAll(benchThreads, cores) }, enabled = !busy) { Text("3 Bench") }
            Button(onClick = controller::accelerators, enabled = !busy) { Text("3b Accelerators") }
            Button(onClick = { controller.soak(choice) }, enabled = !busy) { Text("4 Soak 10 min") }
            Button(onClick = { controller.soakWhenUnplugged(choice) }, enabled = !busy) { Text("4b Soak when unplugged") }
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
