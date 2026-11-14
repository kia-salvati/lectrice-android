package app.lectrice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lectrice.core.designsystem.LectriceTheme
import app.lectrice.core.model.Document
import app.lectrice.core.model.IncomingKind
import app.lectrice.core.model.IncomingRules
import app.lectrice.core.model.PlaybackStatus
import app.lectrice.feature.library.LibraryNavigation
import app.lectrice.feature.library.LibraryScreen
import app.lectrice.feature.library.LibraryViewModel
import app.lectrice.feature.reader.ExportPage
import app.lectrice.feature.reader.ExportViewModel
import app.lectrice.feature.reader.ReaderMessage
import app.lectrice.feature.reader.ReaderNavigation
import app.lectrice.feature.reader.ReaderScreen
import app.lectrice.feature.reader.ReaderViewModel
import app.lectrice.core.link.ComputerHelperHub
import app.lectrice.feature.settings.ChooseModelScreen
import app.lectrice.feature.settings.ComputerHelperScreen
import app.lectrice.feature.settings.ComputerHelperViewModel
import app.lectrice.feature.settings.DownloadScreen
import app.lectrice.feature.settings.LicensesScreen
import app.lectrice.feature.settings.ModelPageScreen
import app.lectrice.feature.settings.ModelSetupNavigation
import app.lectrice.feature.settings.ModelSetupViewModel
import app.lectrice.feature.settings.SettingsNavigation
import app.lectrice.feature.settings.SettingsScreen
import app.lectrice.feature.settings.SettingsViewModel
import app.lectrice.feature.settings.StorageNavigation
import app.lectrice.feature.settings.StorageScreen
import app.lectrice.feature.settings.StorageViewModel
import app.lectrice.feature.settings.WelcomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The one Activity (spec §4.5). Screens are composables on our own back stack. */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as LectriceApplication).container
    private val navigator by lazy { Navigator(listOf(startScreen())) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.settings.update { it.copy(notificationAsked = true) }
        app.lectrice.core.data.Log.i(SCOPE, "notification permission granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        handleIntent(intent)
        askNotificationsBeforeFirstWork()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            LectriceTheme(settings.theme) {
                AppScreens(navigator, container)
            }
        }
    }

    /** S1 on a first start with no model; the Library otherwise (file 01 §2). */
    private fun startScreen(): Screen =
        if (!container.settings.settings.value.firstRunDone && container.models.installed().isEmpty()) Screen.Welcome else Screen.Library

    /**
     * File 17 §3.2: asked once ever, right before the first play or download (Android 13+). The answer never blocks
     * anything: playback, downloads and exports work without notifications.
     */
    private fun askNotificationsBeforeFirstWork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || container.settings.settings.value.notificationAsked) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            val playing = launch { container.playback.state.first { it.status == PlaybackStatus.Preparing || it.status == PlaybackStatus.Playing } }
            val downloading = launch { container.work.download.first { it != null } }
            while (playing.isActive && downloading.isActive) delay(POLL_MS)
            playing.cancel()
            downloading.cancel()
            if (!container.settings.settings.value.notificationAsked) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * "Open with Lectrice" / "Share to Lectrice" (file 03 §3.5). The activity is exported, so the intent can come from
     * any app with any content: [IncomingRules] decides what is taken, and the library asks before it is imported.
     */
    private fun handleIntent(intent: Intent?) {
        // Debug builds only: `adb shell am start … --es lectrice.play <docId>` opens a book playing (device checks over adb).
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra(DEBUG_PLAY_EXTRA)?.let { navigator.push(Screen.Reader(it, play = true)) }
            intent?.getStringExtra(DEBUG_EXPORT_EXTRA)?.let { documentId -> debugExport(documentId, intent.getIntExtra(DEBUG_SPEED_EXTRA, 100)) }
        }
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            // The two-argument getParcelableExtra exists from Android 13 only; minSdk is 29.
            Intent.ACTION_SEND -> runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
            else -> null
        } ?: return
        val action = intent?.action
        val declaredType = intent?.type
        lifecycleScope.launch {
            // Asking the sender's provider for the type and the name can block or throw: off the main thread, and guarded.
            val (type, name) = withContext(Dispatchers.IO) {
                val type = declaredType ?: runCatching { contentResolver.getType(uri) }.getOrNull()
                type to runCatching { displayName(uri) }.getOrNull()
            }
            val kind = IncomingRules.kind(action, uri.scheme, uri.authority, type, packageName)
            if (kind == null) {
                app.lectrice.core.data.Log.w(SCOPE, "incoming file dropped action=$action scheme=${uri.scheme} type=$type")
                return@launch
            }
            val fallback = if (kind == IncomingKind.Pdf) DEFAULT_PDF_NAME else DEFAULT_AUDIOBOOK_NAME
            val waiting = container.incoming.offer(uri.toString(), kind, IncomingRules.displayName(name, fallback))
            app.lectrice.core.data.Log.i(SCOPE, "incoming file kind=$kind waiting=$waiting")
            if (waiting) navigator.resetTo(Screen.Library)
        }
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Debug builds: `--es lectrice.export <docId> --ei lectrice.speed 150` starts an export with the model in use. */
    private fun debugExport(documentId: String, speedPercent: Int) {
        lifecycleScope.launch {
            val model = container.engine.activeModel() ?: return@launch
            val request = app.lectrice.core.model.ExportRequest(documentId, app.lectrice.core.model.Voices.DEFAULT_VOICE_ID, model.info.id, false, app.lectrice.core.model.PlaybackSpeed.fromPercent(speedPercent))
            runCatching { container.exports.start(request) }.onFailure { app.lectrice.core.data.Log.w(SCOPE, "debug export failed", it) }
            container.work.onExportsChanged()
        }
    }

    override fun onStart() {
        super.onStart()
        container.work.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        container.positions.flush()
        lifecycleScope.launch { container.removal.purgeNow() }
    }

    private companion object {
        const val SCOPE = "MainActivity"
        const val POLL_MS = 500L
        const val DEBUG_PLAY_EXTRA = "lectrice.play"
        const val DEBUG_EXPORT_EXTRA = "lectrice.export"
        const val DEBUG_SPEED_EXTRA = "lectrice.speed"
        const val DEFAULT_PDF_NAME = "document.pdf"
        const val DEFAULT_AUDIOBOOK_NAME = "audiobook.m4b"
    }
}

@Composable
private fun AppScreens(navigator: Navigator, container: AppContainer) {
    BackHandler(enabled = navigator.stack.size > 1) { navigator.pop() }
    val stores = remember { ScreenStores() }
    AnimatedContent(
        targetState = navigator.top,
        transitionSpec = {
            val direction = if (navigator.lastWasBack) -1 else 1
            (slideInHorizontally(tween(TRANSITION_MS)) { it * direction / SLIDE_DIVISOR } + fadeIn(tween(TRANSITION_MS)))
                .togetherWith(slideOutHorizontally(tween(TRANSITION_MS)) { -it * direction / SLIDE_DIVISOR } + fadeOut(tween(TRANSITION_MS)))
        },
        label = "screens",
    ) { screen ->
        stores.Scope(screen, navigator.stack) {
            when (screen) {
                Screen.Library -> LibraryRoute(navigator, container)
                is Screen.Reader -> ReaderRoute(screen, navigator, container)
                Screen.Settings -> SettingsRoute(navigator, container)
                Screen.Welcome -> WelcomeScreen(onGetVoice = { navigator.push(Screen.ChooseModel) }, onNotNow = {
                    container.settings.update { it.copy(firstRunDone = true) }
                    navigator.resetTo(Screen.Library)
                })
                Screen.ChooseModel, Screen.Download, Screen.ModelPage -> ModelRoute(screen, navigator, container)
                is Screen.Storage -> StorageRoute(screen, navigator, container, closeInsteadOfBack = false, onClose = { navigator.pop() })
                Screen.Licenses -> LicensesScreen(onBack = { navigator.pop() })
                Screen.ComputerHelper -> ComputerHelperRoute(navigator, container)
            }
        }
    }
}

@Composable
private fun LibraryRoute(navigator: Navigator, container: AppContainer) {
    val navigation = remember(navigator) {
        object : LibraryNavigation {
            override fun openReader(documentId: String, play: Boolean) = navigator.push(Screen.Reader(documentId, play))
            override fun openSettings() = navigator.push(Screen.Settings)
            override fun openModelSetup() = navigator.push(Screen.ChooseModel)
            override fun openDownload() = navigator.push(Screen.Download)
        }
    }
    val viewModel: LibraryViewModel = viewModel {
        LibraryViewModel(container.library, container.playback, container.imports, container.removal, container.settings, container.engine, navigation, container.audiobooks, container.incoming)
    }
    LaunchedEffect(viewModel) { viewModel.reload() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.pickedPdf(it.toString()) } }
    val audiobookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.pickedAudiobook(it.toString()) } }
    LibraryScreen(state, viewModel, onPickPdf = { picker.launch(arrayOf(PDF_MIME)) }, onPickAudiobook = { audiobookPicker.launch(arrayOf(AUDIO_MIME)) })
}

@Composable
private fun ReaderRoute(screen: Screen.Reader, navigator: Navigator, container: AppContainer) {
    val navigation = remember(navigator) {
        object : ReaderNavigation {
            override fun backToLibrary(message: ReaderMessage?) = navigator.resetTo(Screen.Library)
            override fun openModelSetup() = navigator.push(Screen.ChooseModel)
            override fun openExport(documentId: String) = Unit
            override fun openComputerHelper() = navigator.push(Screen.ComputerHelper)
        }
    }
    val viewModel: ReaderViewModel = viewModel(key = screen.documentId) {
        ReaderViewModel(
            screen.documentId, screen.play, container.library, container.playback, container.settings, container.voices, container.voiceSamples,
            container.engine, navigation, container.prepare, container.voiceModels, container.audiobooks, container.computerHelper,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audiobookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.linkAudiobook(it.toString()) } }
    val activity = LocalActivity.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var listenOpen by remember { mutableStateOf(false) }
    // File 06 §3.6: with the setting on, the Listen view stays above the lock screen if it was open when the screen went
    // off. Derived from the current state, and cleared when the reader goes, so it can't be left on (B3).
    val overLockScreen = listenOpen && settings.listenOverLockScreen
    DisposableEffect(activity, overLockScreen) {
        activity?.setShowWhenLocked(overLockScreen)
        onDispose { activity?.setShowWhenLocked(false) }
    }
    ReaderScreen(
        state,
        viewModel,
        onBack = { navigator.pop() },
        listenOverLockScreen = settings.listenOverLockScreen,
        onListenViewChanged = { open -> listenOpen = open },
        exportPage = { onBack -> ExportRoute(screen.documentId, navigator, container, onBack) },
        onPickAudiobook = { audiobookPicker.launch(arrayOf(AUDIO_MIME)) },
    )
}

@Composable
private fun ExportRoute(documentId: String, navigator: Navigator, container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val document by produceState<Document?>(null, documentId) { value = runCatching { container.library.getDocument(documentId, markOpened = false) }.getOrNull() }
    val opened = document ?: return
    val viewModel: ExportViewModel = viewModel(key = "export-$documentId") {
        val files = AndroidExportFiles(context.applicationContext)
        val platform = AndroidExportPlatform(files, container.voiceSamples, container.work::onExportsChanged) { bytes -> navigator.push(Screen.Storage(bytes)) }
        ExportViewModel(opened, container.exports, container.voiceModels, container.voices, container.playback, container.settings, platform, container.audiobooks)
    }
    LaunchedEffect(viewModel) { container.work.onExportsChanged() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.close) { if (state.close) onBack() }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri -> uri?.let { viewModel.saveAs(it.toString()) } }
    ExportPage(state, viewModel, onBack = onBack, onSaveAs = { saveAs.launch("${opened.title}.m4b") })
}

@Composable
private fun SettingsRoute(navigator: Navigator, container: AppContainer) {
    val navigation = remember(navigator) {
        object : SettingsNavigation {
            override fun openModelPage() = navigator.push(Screen.ModelPage)
            override fun openStorage() = navigator.push(Screen.Storage())
            override fun openLicenses() = navigator.push(Screen.Licenses)
            override fun openComputerHelper() = navigator.push(Screen.ComputerHelper)
        }
    }
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel {
        SettingsViewModel(container.settings, container.voices, container.voiceSamples, AndroidSettingsPlatform(context.applicationContext, container), navigation, container.computerHelper)
    }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, viewModel, onBack = { navigator.pop() })
}

@Composable
private fun ModelRoute(screen: Screen, navigator: Navigator, container: AppContainer) {
    val navigation = remember(navigator) {
        object : ModelSetupNavigation {
            override fun toLibrary() = navigator.resetTo(Screen.Library)
            override fun toDownload() = navigator.replaceTop(Screen.Download)
            override fun pickPdf() = navigator.resetTo(Screen.Library)
        }
    }
    val viewModel: ModelSetupViewModel = viewModel(key = "models") { ModelSetupViewModel(container.voiceModels, container.settings, container.voiceSamples, navigation) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importPicked(it.toString()) } }
    // A cancelled download leaves nothing to show on S3: back to the choice.
    if (screen == Screen.Download && state.download == null && !state.ready && !state.importing) {
        LaunchedEffect(Unit) { navigator.replaceTop(Screen.ChooseModel) }
    }
    when (screen) {
        Screen.ChooseModel -> ChooseModelScreen(state, viewModel, onImportFile = { importer.launch(arrayOf("application/x-bzip2", "application/octet-stream")) }, onBack = { navigator.pop() })
        Screen.Download -> DownloadScreen(state, viewModel, onOpenPdf = { navigator.resetTo(Screen.Library) }, onLibrary = { navigator.resetTo(Screen.Library) })
        else -> ModelPageScreen(state, viewModel, onBack = { navigator.pop() })
    }
}

/** T3, from Settings or from Android's "Clear storage" ([ManageSpaceActivity], with ✕ instead of back). */
@Composable
fun StorageRoute(screen: Screen.Storage, navigator: Navigator?, container: AppContainer, closeInsteadOfBack: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val navigation = remember(navigator) {
        object : StorageNavigation {
            override fun openModelPage() {
                navigator?.push(Screen.ModelPage)
            }

            override fun close() = onClose()
        }
    }
    val viewModel: StorageViewModel = viewModel(key = "storage-${screen.requestedBytes}") {
        val files = AndroidExportFiles(context.applicationContext)
        StorageViewModel(container.library, container.exports, container.voiceModels, container.removal, AndroidStoragePlatform(context.applicationContext, files), navigation, screen.requestedBytes)
    }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StorageScreen(state, viewModel, onBack = onClose, closeInsteadOfBack = closeInsteadOfBack)
}

private const val PDF_MIME = "application/pdf"
private const val EXPORT_MIME = "audio/mp4"
private const val AUDIO_MIME = "audio/*"
private const val TRANSITION_MS = 220
private const val SLIDE_DIVISOR = 6

/** File 24 H1–H6: Settings → Computer helper, with the pairing pages on top of it. */
@Composable
private fun ComputerHelperRoute(navigator: Navigator, container: AppContainer) {
    val viewModel: ComputerHelperViewModel = viewModel { ComputerHelperViewModel(container.computerHelper) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Android 17+ only (target SDK 37): asked when the person turns the helper on or pairs, after the H6 rationale.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.permissionChanged() }
    BackHandler(enabled = state.pair != null) { viewModel.pairBack() }
    ComputerHelperScreen(
        state,
        viewModel,
        onBack = { navigator.pop() },
        onRequestPermission = { permission.launch(ComputerHelperHub.LOCAL_NETWORK_PERMISSION) },
        onOpenAppSettings = { container.openAppSettings() },
    )
}
