package app.lectrice.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.lectrice.core.data.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Seeking inside one sentence means nothing: the session never offers it (lock screen, notification, cars). */
@UnstableApi
private class SentencePlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build()

    override fun isCommandAvailable(command: Int): Boolean = command != Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM && super.isCommandAvailable(command)
}

/** The application gives the service its process-wide controller. */
interface PlaybackHost {
    @get:UnstableApi
    val playbackController: Media3PlaybackController
}

/**
 * The media session around the controller's player (file 09 §3.1): lock screen, notification, Now Bar, Bluetooth and
 * headset buttons. Media3's default notification; next/previous = one sentence; no seeking inside a sentence.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val controller: Media3PlaybackController get() = (application as PlaybackHost).playbackController

    override fun onCreate() {
        super.onCreate()
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = MediaSession.Builder(this, SentencePlayer(controller.player)).setCallback(callback)
        if (launch != null) builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        session = builder.build()
    }

    private val callback = object : MediaSession.Callback {
        /** Media3's default accepts every app; see [ControllerPolicy]. */
        override fun onConnect(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val caller = ControllerPolicy.Caller(
                sameApp = controller.uid == Process.myUid(),
                trusted = controller.isTrusted,
                car = mediaSession.isAutomotiveController(controller) || mediaSession.isAutoCompanionController(controller),
                packageVerified = controller.isPackageNameVerified,
            )
            return when (ControllerPolicy.access(caller)) {
                ControllerPolicy.Access.Full -> super.onConnect(mediaSession, controller)
                ControllerPolicy.Access.Limited -> MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession, controller)
                    .setAvailablePlayerCommands(
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .removeAll(Player.COMMAND_GET_TIMELINE, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM)
                            .build(),
                    )
                    .build()
                ControllerPolicy.Access.Refused -> {
                    Log.w(SCOPE, "media controller refused uid=${controller.uid} version=${controller.controllerVersion}")
                    MediaSession.ConnectionResult.reject()
                }
            }
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val resumed = runCatching { this@PlaybackService.controller.resumeLastBook() }.getOrDefault(false)
                val player = this@PlaybackService.controller.player
                if (!resumed || player.mediaItemCount == 0) {
                    Log.i(SCOPE, "playback resumption: nothing to resume")
                    result.setException(UnsupportedOperationException("no last book"))
                    return@launch
                }
                Log.i(SCOPE, "playback resumption items=${player.mediaItemCount} index=${player.currentMediaItemIndex} forPlayback=$isForPlayback")
                result.set(MediaSession.MediaItemsWithStartPosition((0 until player.mediaItemCount).map(player::getMediaItemAt), player.currentMediaItemIndex, 0))
            }
            return result
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away while paused: stop, so the notification goes. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            controller.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SCOPE = "PlaybackService"
    }
}
