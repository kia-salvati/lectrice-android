package app.lectrice.core.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.Log
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.VoiceSamplePlayer
import app.lectrice.core.model.VoiceSampleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Hear a sample" (file 16): the fixed sample sentence in a voice, made on demand and cached on disk by the core. The
 * book pauses while it plays and continues after, if it was playing.
 */
@UnstableApi
class CoreVoiceSamplePlayer(
    private val context: Context,
    private val core: RustCore,
    private val engine: EngineHost,
    private val book: Media3PlaybackController,
    private val scope: CoroutineScope,
) : VoiceSamplePlayer {
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var resumeBook = false
    private val sampleState = MutableStateFlow(VoiceSampleState())
    override val state: StateFlow<VoiceSampleState> = sampleState.asStateFlow()

    override fun play(voiceId: String) {
        job?.cancel()
        player?.stop()
        sampleState.value = VoiceSampleState(voiceId, VoiceSampleState.Phase.Loading)
        job = scope.launch(Dispatchers.Main) {
            val wav = try {
                withContext(Dispatchers.IO) {
                    engine.ensureLoaded() ?: return@withContext null
                    core.blocking("speak_voice_sample") { it.speakVoiceSample(voiceId) }.wav
                }
            } catch (error: LectriceError) {
                Log.w(SCOPE, "sample failed", error)
                null
            }
            if (wav == null) {
                sampleState.value = VoiceSampleState()
                return@launch
            }
            if (book.player.playWhenReady) {
                resumeBook = true
                book.pause()
            }
            val sample = player ?: build().also { player = it }
            val factory = DataSource.Factory { ByteArrayDataSource(wav) }
            sample.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.EMPTY)))
            sample.prepare()
            sample.play()
            sampleState.value = VoiceSampleState(voiceId, VoiceSampleState.Phase.Playing)
        }
    }

    private fun build(): ExoPlayer {
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
        return ExoPlayer.Builder(context).setAudioAttributes(attributes, false).build().also {
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) return
                    sampleState.value = VoiceSampleState()
                    if (resumeBook) {
                        resumeBook = false
                        book.play()
                    }
                }
            })
        }
    }

    private companion object {
        const val SCOPE = "VoiceSample"
    }
}
