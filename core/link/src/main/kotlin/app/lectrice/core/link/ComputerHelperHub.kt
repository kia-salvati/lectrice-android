package app.lectrice.core.link

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.Log
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.ComputerHelperRepository
import app.lectrice.core.model.FoundComputer
import app.lectrice.core.model.HelperAttention
import app.lectrice.core.model.HelperLink
import app.lectrice.core.model.HelperNotice
import app.lectrice.core.model.HelperState
import app.lectrice.core.model.NeededModel
import app.lectrice.core.model.PairResult
import app.lectrice.core.model.PairedComputer
import app.lectrice.core.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.lectrice_ffi.HelperComputer
import uniffi.lectrice_ffi.HelperConfig
import uniffi.lectrice_ffi.HelperListener
import uniffi.lectrice_ffi.HelperStateRecord
import uniffi.lectrice_ffi.PairOutcome
import uniffi.lectrice_ffi.helperLostReadAheadMinutes

/**
 * The computer helper on the phone (file 24 C50/C51). Kotlin's part: the paired computers, the keys, discovery, the
 * Wi-Fi network and when the link runs; the core makes it work (link, fill, reconnects, the 30-minute plan).
 *
 * The governor decides when ([onActive]): while someone listens (Full or Reduced) the link runs, Off stops it (the
 * computer's queue is dropped and the session ends). When the link goes while running, the phone's model is loaded at
 * once and the local read-ahead gets its "lost" length ([onLostMinutes]): the cached minutes cover the gap.
 */
class ComputerHelperHub(
    private val context: Context,
    private val core: RustCore,
    private val engine: EngineHost,
    private val playback: StateFlow<PlaybackState>,
    private val readAheadSetting: () -> Int,
    private val scope: CoroutineScope,
) : ComputerHelperRepository {
    private val files = LinkFiles(context)
    private val identity = IdentityKeyStore(files.identity)
    private val mutex = Mutex()
    private var peers = PeerList()
    private var coreState: HelperLinkState.Core = HelperLinkState.Core.Off
    private var active = false
    private var running = false
    private var noticeShown = false
    private var watch: Job? = null
    /** This phone's wrong codes per address, for the "n of 3" text. */
    private val wrongCodes = mutableMapOf<String, Int>()

    private val wifi = WifiNetwork(context) { if (running) retryNow() }
    private val finder = ComputerFinder(context, ::onSighting)
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WIFI_LOCK_MODE, WIFI_LOCK_TAG).apply { setReferenceCounted(false) }

    private val mutable = MutableStateFlow(HelperState())
    override val state: StateFlow<HelperState> = mutable.asStateFlow()
    private val mutableNotices = MutableSharedFlow<HelperNotice>(extraBufferCapacity = 1)
    override val notices: SharedFlow<HelperNotice> = mutableNotices.asSharedFlow()
    override val found: StateFlow<List<FoundComputer>> = combine(finder.sightings, mutable) { sightings, state ->
        val known = state.computers.map { it.peerId }.toSet()
        sightings.map { FoundComputer(it.peerId, it.name, it.address, it.peerId in known) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Set by the app: the local read-ahead's minutes while the computer is lost (`null` = the setting). */
    var onLostMinutes: (UInt?) -> Unit = {}

    /** Set by the app: something besides listening needs the computer (a Prepare range made there). */
    var keepAlive: () -> Boolean = { false }

    /** Main thread, once. */
    fun start() {
        wifi.start()
        scope.launch {
            core.call("helper_set_platform") { it.helperSetPlatform(wifi, Listener()) }
            mutex.withLock {
                peers = withContext(Dispatchers.IO) { files.readPeers() }
                configureLocked()
            }
        }
        scope.launch { wifi.network.collect { network -> mutable.update { it.copy(onWifi = network != null) } } }
    }

    /** The governor: someone listens (Full or Reduced) or not (Off). */
    fun onActive(active: Boolean) {
        scope.launch {
            mutex.withLock {
                this@ComputerHelperHub.active = active
                applyLocked()
            }
        }
    }

    /** [keepAlive] changed (a Prepare range on the computer started or ended): start or stop the link accordingly. */
    fun onKeepAliveChanged() {
        scope.launch { mutex.withLock { applyLocked() } }
    }

    /** Play was pressed: try the computer again at once instead of waiting for the next retry. */
    fun onPlayPressed() {
        if (running) retryNow()
    }

    override fun startDiscovery() = finder.start()

    override fun stopDiscovery() {
        if (!running) finder.stop()
    }

    override suspend fun pair(address: String, code: String): PairResult = withContext(Dispatchers.IO) {
        val key = identity.privateKey()
        val outcome = try {
            core.blocking("helper_pair") { it.helperPair(address, code, key, deviceName(), appVersion()) }
        } finally {
            key.fill(0)
        }
        when (outcome) {
            is PairOutcome.Paired -> {
                wrongCodes.remove(address)
                val computer = outcome.computer
                val now = System.currentTimeMillis()
                val paired = PairedComputer(computer.peerId, computer.name, null, address, now, now)
                mutex.withLock {
                    peers = peers.paired(Peer(paired, computer.staticPublic))
                    saveLocked()
                    configureLocked()
                }
                Log.i(SCOPE, "paired peer=${computer.peerId.take(LOG_ID)}")
                PairResult.Paired(paired)
            }
            PairOutcome.WrongCode -> PairResult.WrongCode(wrongCodes.merge(address, 1, Int::plus) ?: 1)
            PairOutcome.NoCode -> PairResult.NoCode.also { wrongCodes.remove(address) }
            PairOutcome.NotLocal -> PairResult.NotLocal
            PairOutcome.Incompatible -> PairResult.Incompatible
            PairOutcome.Unreachable -> PairResult.Unreachable
        }
    }

    override suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        peers = peers.switched(enabled)
        saveLocked()
        configureLocked()
    }

    override suspend fun forget(peerId: String) = mutex.withLock {
        peers = peers.forgotten(peerId)
        saveLocked()
        configureLocked()
        Log.i(SCOPE, "forgot peer=${peerId.take(LOG_ID)}")
    }

    override suspend fun rename(peerId: String, label: String?) = mutex.withLock {
        peers = peers.renamed(peerId, label)
        saveLocked()
        publishLocked()
    }

    override fun retry() {
        scope.launch { mutex.withLock { if (running) retryNow() else applyLocked() } }
    }

    override fun permissionChanged() {
        scope.launch { mutex.withLock { configureLocked() } }
    }

    /** Hands the active computer to the core (or none); the engine is rebuilt around it on its next load. */
    private suspend fun configureLocked() {
        stopLocked()
        val peer = peers.active.takeIf { peers.enabled && !permissionDenied() }
        val config = peer?.let {
            val key = withContext(Dispatchers.IO) { identity.privateKey() }
            HelperConfig(key, HelperComputer(it.computer.peerId, it.computer.name, it.staticPublic), listOfNotNull(it.computer.lastAddress), deviceName(), appVersion())
        }
        val configured = core.call("helper_configure") { it.helperConfigure(config) }
        config?.privateKey?.fill(0)
        if (!configured) Log.w(SCOPE, "helper not configured")
        engine.modelChanged()
        Log.i(SCOPE, "helper configured on=${config != null}")
        applyLocked()
    }

    /** Runs the link while someone listens (or a Prepare range needs it), stops it otherwise. */
    private suspend fun applyLocked() {
        val wanted = (active || keepAlive()) && peers.enabled && peers.active != null && !permissionDenied()
        if (wanted && !running) startLocked() else if (!wanted && running) stopLocked()
        publishLocked()
    }

    private suspend fun startLocked() {
        if (engine.ensureLoaded() == null) {
            Log.w(SCOPE, "no voice model, the computer can't help")
            return
        }
        if (!core.call("helper_start") { it.helperStart() }) return
        running = true
        finder.start()
        watch?.cancel()
        watch = scope.launch { watchLoop() }
        Log.i(SCOPE, "helper started")
    }

    private suspend fun stopLocked() {
        if (!running) return
        running = false
        watch?.cancel()
        finder.stop()
        core.call("helper_stop") { it.helperStop() }
        if (wifiLock.isHeld) wifiLock.release()
        onLostMinutes(null)
        Log.i(SCOPE, "helper stopped")
    }

    /**
     * While running: holds the Wi-Fi lock only while clips are on their way, and starts the fill again when the core
     * rebuilt its services (an engine reload makes a new, stopped fill). While the computer is lost, browses again
     * now and then: Android doesn't report a computer that went away without a goodbye (crash, sleep) when it comes
     * back, and the fresh sighting is what brings the link back at once instead of at the next 60 s retry.
     * The phone's model, loaded when the computer was lost, is let go once the computer has been back for a while
     * (playback holds the engine, so the engine's own idle unload never comes while listening).
     */
    private suspend fun watchLoop() {
        var lostForMs = 0L
        var connectedForMs = 0L
        while (true) {
            delay(WATCH_MS)
            val status = runCatching { core.call("helper_status") { it.helperStatus() } }.getOrNull() ?: continue
            lostForMs = if (status.state == HelperStateRecord.Lost) lostForMs + WATCH_MS else 0L
            if (lostForMs >= REDISCOVER_MS) {
                lostForMs = 0L
                finder.restart()
            }
            val connected = status.state == HelperStateRecord.Connected || status.state == HelperStateRecord.Busy
            connectedForMs = if (connected && status.engineLoaded) connectedForMs + WATCH_MS else 0L
            if (connectedForMs >= RELEASE_MODEL_MS) {
                connectedForMs = 0L
                runCatching { core.blocking("unload_engine") { it.unloadEngine() } }
                Log.i(SCOPE, "phone model released, the computer makes the audio")
            }
            val busy = connected && status.inFlight > 0u
            if (busy && !wifiLock.isHeld) wifiLock.acquire() else if (!busy && wifiLock.isHeld) wifiLock.release()
            if (status.state == HelperStateRecord.Off) {
                mutex.withLock { if (running && core.call("helper_start") { it.helperStart() }) Log.i(SCOPE, "helper fill restarted after an engine reload") }
            }
        }
    }

    private fun retryNow() {
        scope.launch { runCatching { core.call("helper_retry_now") { it.helperRetryNow() } } }
    }

    private fun onSighting(sighting: Sighting) {
        scope.launch {
            mutex.withLock {
                val peer = peers.active ?: return@launch
                if (sighting.peerId != peer.computer.peerId) return@launch
                peers = peers.seen(sighting.peerId, sighting.address, System.currentTimeMillis())
                saveLocked()
                if (running) core.call("helper_set_addresses") { it.helperSetAddresses(listOf(sighting.address)) }
                publishLocked()
            }
        }
    }

    private fun onCoreState(next: HelperLinkState.Core) {
        scope.launch {
            mutex.withLock {
                val before = mutable.value.link
                coreState = next
                publishLocked()
                val after = mutable.value.link
                // "Last seen" is when the session started or ended, so the row is right once it says "Connected" no more.
                if (after.makesAudio != before.makesAudio) {
                    peers.active?.let { peers = peers.seen(it.computer.peerId, null, System.currentTimeMillis()) }
                    saveLocked()
                    publishLocked()
                }
                if (after == HelperLink.Connected && before != HelperLink.Connected) onLostMinutes(null)
                if (HelperLinkState.lostNow(before, after)) takeOver()
            }
        }
    }

    /** File 24 §3.6: load the phone's model now and give the local read-ahead its "lost" length. */
    private fun takeOver() {
        val now = playback.value
        val minutes = helperLostReadAheadMinutes(now.rtf, now.speed.rate, readAheadSetting().toUInt())
        onLostMinutes(minutes)
        Log.i(SCOPE, "computer lost, phone takes over read_ahead_min=$minutes")
        if (now.isPlaying && !noticeShown) {
            noticeShown = true
            mutableNotices.tryEmit(HelperNotice.ComputerLost)
        }
        scope.launch(Dispatchers.IO) { runCatching { core.blocking("preload_engine") { it.preloadEngine() } } }
    }

    private fun publishLocked() {
        val link = HelperLinkState.of(coreState, running, peers.enabled && permissionDenied())
        // The phone opened the link with its own model, so that is the one the computer lacks.
        val needed = if (link == HelperLink.NeedsAttention(HelperAttention.ModelMissing)) engine.activeModel()?.info?.let { NeededModel(it.name, it.downloadBytes) } else null
        mutable.update {
            it.copy(
                enabled = peers.enabled,
                computers = peers.peers.map(Peer::computer),
                active = peers.active?.computer,
                link = link,
                needsPermission = permissionNeeded(),
                neededModel = needed,
            )
        }
    }

    private suspend fun saveLocked() = withContext(Dispatchers.IO) { runCatching { files.writePeers(peers) }.onFailure { Log.w(SCOPE, "peers not saved", it) } }

    /** Android 17 with target SDK 37+: "Nearby devices" (file 24 §3.10). Never true on today's target SDK 36. */
    private fun permissionNeeded(): Boolean =
        Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK && context.applicationInfo.targetSdkVersion >= LOCAL_NETWORK_SDK &&
            context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED

    private fun permissionDenied(): Boolean = permissionNeeded()

    @SuppressLint("HardwareIds")
    private fun deviceName(): String = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun appVersion(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private inner class Listener : HelperListener {
        override fun onState(state: HelperStateRecord) {
            onCoreState(
                when (state) {
                    HelperStateRecord.Off -> HelperLinkState.Core.Off
                    HelperStateRecord.Connecting -> HelperLinkState.Core.Connecting
                    HelperStateRecord.Connected -> HelperLinkState.Core.Connected
                    HelperStateRecord.Busy -> HelperLinkState.Core.Busy
                    HelperStateRecord.Lost -> HelperLinkState.Core.Lost
                    is HelperStateRecord.NeedsAttention -> HelperLinkState.Core.NeedsAttention(state.error.name)
                },
            )
        }
    }

    companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val LOCAL_NETWORK_SDK = 37
        private const val SCOPE = "Helper"
        private const val LOG_ID = 8
        private const val WATCH_MS = 2_000L
        private const val REDISCOVER_MS = 10_000L
        /** Like the engine's own idle unload (file 08): 5 minutes. */
        private const val RELEASE_MODEL_MS = 5 * 60_000L
        private const val WIFI_LOCK_TAG = "lectrice:helper"
        /** Deprecated on API 34+ in favour of the low-latency mode, which only works with the screen on (file 24 §3.10). */
        @Suppress("DEPRECATION")
        private const val WIFI_LOCK_MODE = WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
