package app.lectrice

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.StrictMode
import app.lectrice.core.data.EngineHost
import app.lectrice.core.model.SystemPauseCause
import app.lectrice.core.work.WorkCenter
import app.lectrice.core.work.WorkHost
import app.lectrice.core.playback.Media3PlaybackController
import app.lectrice.core.playback.PlaybackHost
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
class LectriceApplication : Application(), PlaybackHost, WorkHost {
    lateinit var container: AppContainer
        private set

    override val playbackController: Media3PlaybackController get() = container.media3
    override val workCenter: WorkCenter get() = container.work
    override val engineHost: EngineHost get() = container.engine

    override fun systemPauseCause(): SystemPauseCause? = container.systemPauseCause()

    override fun launchIntent(): Intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        container = AppContainer(this, BuildConfig.DEBUG)
        container.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !container.playback.state.value.isPlaying) {
            container.media3.readAhead.onLowMemory()
            container.scope.launch { container.engine.unload("trim memory") }
        }
    }
}
