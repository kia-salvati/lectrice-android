package app.lectrice.core.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.Log
import app.lectrice.core.data.ModelSpec
import app.lectrice.core.model.AudioExport
import app.lectrice.core.model.ExportStatus
import app.lectrice.core.model.ModelInstallState
import app.lectrice.core.model.SystemPauseCause
import app.lectrice.core.model.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the application gives the service. */
interface WorkHost {
    val workCenter: WorkCenter
    val engineHost: EngineHost

    /** Heat or battery reasons to pause exports now (file 10 §3.7), else null. */
    fun systemPauseCause(): SystemPauseCause?

    /** Opens the app (notification taps). */
    fun launchIntent(): Intent
}

/**
 * Keeps the process in the foreground while a model downloads or an export runs (spike S7: Samsung throttles background
 * work 4–8×). Type `dataSync` for downloads, `mediaProcessing` (API 35+) for exports. The core does the work on its own
 * threads; this loop watches it, picks the engine an export needs when nobody listens, pauses exports for heat and
 * battery, updates the notification, and stops when there's nothing left.
 */
class BackgroundWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var holdingEngine = false
    private var lastListeningMs = 0L
    /** What this run worked on: only those are announced when it ends (an export finished yesterday is not news). */
    private val exportsThisRun = mutableSetOf<String>()
    private var downloadedThisRun = false
    private val host: WorkHost get() = application as WorkHost

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(notification(working = true))
        when (intent?.action) {
            ACTION_PAUSE_DOWNLOAD -> host.workCenter.pauseDownload()
            ACTION_PAUSE_EXPORT -> intent.getStringExtra(EXTRA_EXPORT_ID)?.let { id -> scope.launch { suspendRunCatching { host.workCenter.exports.pause(id) } } }
            ACTION_CANCEL_EXPORT -> intent.getStringExtra(EXTRA_EXPORT_ID)?.let { id -> scope.launch { suspendRunCatching { host.workCenter.exports.cancel(id) } } }
            ACTION_CONTINUE_EXPORTS -> scope.launch { host.workCenter.exports.resumeSystemPaused() }
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val center = host.workCenter
        exportsThisRun.clear()
        downloadedThisRun = false
        while (true) {
            suspendRunCatching { center.exports.refresh() }
            val running = center.exports.exports.value.filter { it.status == ExportStatus.Queued || it.status == ExportStatus.Running }
            running.mapTo(exportsThisRun) { it.id }
            if (center.isDownloading) downloadedThisRun = true
            // The engine first: the core starts a prepare job only on a loaded engine (B7: SpeechEngineUnavailable).
            if (center.prepare.hasWork()) {
                holdEngine()
                if (host.engineHost.loadedModel() == null) host.engineHost.ensureLoadedForExport()
            }
            center.prepare.tick(System.currentTimeMillis())
            if (running.isNotEmpty()) {
                holdEngine()
                host.systemPauseCause()?.let { cause ->
                    Log.i(SCOPE, "exports paused for the system ($cause)")
                    center.exports.pauseAllForSystem(cause)
                }
                pickEngine(running.first())
            } else if (!center.prepare.hasWork()) {
                releaseEngine()
            }
            if (!center.hasWork()) break
            notify(notification(working = true))
            delay(POLL_MS)
        }
        releaseEngine()
        notifyResult()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * File 10 §3.6: an export made with another model runs only while nobody listens. While nobody listens the engine
     * also runs on the export's thread count (a no-op when it already does).
     */
    private suspend fun pickEngine(export: AudioExport) {
        if (!listenerGone()) return
        val spec = ModelSpec.entries.firstOrNull { it.info.engineId == export.engineId } ?: return
        if (host.engineHost.loadedModel() != spec) Log.i(SCOPE, "loading the export's model id=${spec.info.id}")
        host.engineHost.ensureLoadedForExport(spec)
    }

    /**
     * Nobody has listened for [LISTENER_GONE_MS]: a short pause doesn't count, so the player (which loads the engine on
     * the playback thread count while it buffers) and this loop never take turns reloading it.
     */
    private fun listenerGone(): Boolean {
        val now = System.currentTimeMillis()
        if (host.workCenter.listeningActive()) lastListeningMs = now
        return now - lastListeningMs >= LISTENER_GONE_MS
    }

    private fun holdEngine() {
        if (holdingEngine) return
        holdingEngine = true
        host.engineHost.acquire()
    }

    private fun releaseEngine() {
        if (!holdingEngine) return
        holdingEngine = false
        host.engineHost.release()
    }

    /** Android 15+: the foreground time limit for these types ran out (6 h a day). Exports pause until the app opens. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(SCOPE, "foreground time limit reached")
        scope.launch {
            host.workCenter.exports.pauseAllForSystem(SystemPauseCause.TimeLimit)
            host.workCenter.pauseDownload()
            ServiceCompat.stopForeground(this@BackgroundWorkService, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseEngine()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(notification: Notification) {
        val type = when {
            host.workCenter.isDownloading -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Not ServiceCompat: its API 34 path masks out mediaProcessing (an API 35 type), which Android then refuses as "none".
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** File 10 §3.10 and file 01 (N3, N4). Shown on the device only; never logged. */
    private fun notification(working: Boolean): Notification {
        val center = host.workCenter
        val builder = NotificationCompat.Builder(this, CHANNEL_WORK)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(working)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, host.launchIntent(), PendingIntent.FLAG_IMMUTABLE))
        val download = center.download.value
        val export = center.exports.exports.value.firstOrNull { it.status == ExportStatus.Running || it.status == ExportStatus.Queued }
        when {
            download != null && center.isDownloading -> {
                val percent = if (download.totalBytes > 0) (download.doneBytes * PERCENT / download.totalBytes).toInt() else 0
                builder.setContentTitle(getString(R.string.work_downloading, percent))
                    .setProgress(PERCENT, percent, download.state is ModelInstallState.Installing)
                    .addAction(0, getString(R.string.work_pause), action(ACTION_PAUSE_DOWNLOAD, null))
            }
            export != null && export.waitingForEngine -> builder.setContentTitle(getString(R.string.work_export_waiting, export.title))
                .setContentText(getString(R.string.work_export_waiting_listening))
                .addAction(0, getString(R.string.work_cancel), action(ACTION_CANCEL_EXPORT, export.id))
            export != null -> builder.setContentTitle(getString(R.string.work_exporting, export.title, export.percent))
                .setContentText("${export.voiceId.substringAfter('_').replaceFirstChar(Char::uppercase)} · ${export.speed.label} · M4B")
                .setProgress(PERCENT, export.percent, false)
                .addAction(0, getString(R.string.work_pause), action(ACTION_PAUSE_EXPORT, export.id))
                .addAction(0, getString(R.string.work_cancel), action(ACTION_CANCEL_EXPORT, export.id))
            else -> builder.setContentTitle(getString(R.string.work_preparing))
        }
        return builder.build()
    }

    /** When the work ends: "Audiobook ready" / "The voice is ready" / paused, on the results channel. */
    private fun notifyResult() {
        val center = host.workCenter
        val text = when {
            downloadedThisRun && center.download.value?.state == ModelInstallState.Installed -> getString(R.string.work_voice_ready)
            else -> {
                val latest = center.exports.exports.value.firstOrNull { it.id in exportsThisRun } ?: return
                when (latest.status) {
                    ExportStatus.Done -> getString(R.string.work_export_ready, latest.title)
                    ExportStatus.Failed -> getString(R.string.work_export_failed, latest.title)
                    ExportStatus.Paused -> getString(R.string.work_export_paused, latest.title)
                    else -> return
                }
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, host.launchIntent(), PendingIntent.FLAG_IMMUTABLE))
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun action(action: String, exportId: String?): PendingIntent {
        val intent = Intent(this, BackgroundWorkService::class.java).setAction(action).putExtra(EXTRA_EXPORT_ID, exportId)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val SCOPE = "WorkService"
        private const val POLL_MS = 1_000L
        private const val LISTENER_GONE_MS = 60_000L
        private const val PERCENT = 100
        private const val NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        const val CHANNEL_WORK = "work"
        const val CHANNEL_RESULTS = "work-results"
        private const val ACTION_PAUSE_DOWNLOAD = "app.lectrice.work.PAUSE_DOWNLOAD"
        private const val ACTION_PAUSE_EXPORT = "app.lectrice.work.PAUSE_EXPORT"
        private const val ACTION_CANCEL_EXPORT = "app.lectrice.work.CANCEL_EXPORT"
        const val ACTION_CONTINUE_EXPORTS = "app.lectrice.work.CONTINUE_EXPORTS"
        private const val EXTRA_EXPORT_ID = "exportId"

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_WORK, context.getString(R.string.channel_work), NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
