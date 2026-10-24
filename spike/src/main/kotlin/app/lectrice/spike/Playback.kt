package app.lectrice.spike

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.lectrice_ffi.LectriceCore
import uniffi.lectrice_ffi.SpeakArgs
import java.util.concurrent.ConcurrentLinkedQueue

private const val SCHEME = "lectrice"
private const val HOST = "sentence"
private const val PLAYLIST_ITEMS = 80
private const val TARGET_PLAYED_MS = 120_000L
private const val GIVE_UP_MS = 360_000L
private const val POLL_MS = 250L

fun sentenceUri(documentId: String, sentenceId: UInt): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(documentId).appendPath(sentenceId.toString()).build()

/** One `open` of a sentence: how long the core took, and how much audio it handed back. */
data class SentenceOpen(val sentenceId: Long, val openMs: Long, val durationMs: Long, val readyAheadMs: Long)

/** Plan §5.2 button 5 / spec §7.3: ExoPlayer reads each sentence's WAV straight from the core. */
@OptIn(UnstableApi::class)
class SentenceDataSource(private val core: LectriceCore, private val voice: String, private val opens: ConcurrentLinkedQueue<SentenceOpen>) :
    BaseDataSource(false) {
    private var uri: Uri? = null
    private var bytes = ByteArray(0)
    private var position = 0
    private var remaining = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (documentId, sentenceId) = dataSpec.uri.pathSegments
        val startedAt = SystemClock.elapsedRealtime()
        val spoken = core.speakSentence(SpeakArgs(documentId, sentenceId.toUInt(), voice, 1f, false))
        opens += SentenceOpen(sentenceId.toLong(), SystemClock.elapsedRealtime() - startedAt, spoken.durationMs.toLong(), spoken.readyAheadMs.toLong())
        bytes = spoken.wav
        uri = dataSpec.uri
        position = dataSpec.position.toInt()
        remaining = bytes.size - position
        transferStarted(dataSpec)
        return remaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0) return C.RESULT_END_OF_INPUT
        val count = minOf(length, remaining)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
        bytes = ByteArray(0)
    }

    class Factory(private val core: LectriceCore, private val voice: String, private val opens: ConcurrentLinkedQueue<SentenceOpen>) : DataSource.Factory {
        override fun createDataSource(): DataSource = SentenceDataSource(core, voice, opens)
    }
}

/** Plays about two minutes from `startSentenceId` on and records the time to the first sound and every rebuffer. */
@OptIn(UnstableApi::class)
suspend fun playTwoMinutes(context: Context, core: LectriceCore, documentId: String, startSentenceId: UInt): JSONObject {
    val order = withContext(Dispatchers.IO) { core.playbackOrder(documentId, false) }
    val start = order.indexOf(startSentenceId).coerceAtLeast(0)
    val playlist = order.drop(start).take(PLAYLIST_ITEMS)
    val opens = ConcurrentLinkedQueue<SentenceOpen>()

    return withContext(Dispatchers.Main) {
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(SentenceDataSource.Factory(core, SpikeCore.VOICE, opens)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .build()
        val recorder = PlaybackRecorder(SystemClock.elapsedRealtime())
        player.addListener(recorder)
        player.setMediaItems(playlist.map { MediaItem.Builder().setMediaId(it.toString()).setUri(sentenceUri(documentId, it)).build() })
        player.prepare()
        player.play()
        while (recorder.playedMs() < TARGET_PLAYED_MS && !recorder.finished && SystemClock.elapsedRealtime() - recorder.pressedAt < GIVE_UP_MS) {
            delay(POLL_MS)
        }
        player.release()
        recorder.toJson()
            .put("playlistItems", playlist.size)
            .put("opens", JSONArray(opens.map { JSONObject().put("sentenceId", it.sentenceId).put("openMs", it.openMs).put("durationMs", it.durationMs).put("readyAheadMs", it.readyAheadMs) }))
    }
}

private class PlaybackRecorder(val pressedAt: Long) : Player.Listener {
    var finished = false
        private set
    private var firstSoundMs: Long? = null
    private var playingSince: Long? = null
    private var playedBefore = 0L
    private var bufferingSince: Long? = null
    private var itemIndex = 0
    private var error: String? = null
    private val rebuffers = JSONArray()
    private val transitionsAtMs = JSONArray()

    fun playedMs(): Long = playedBefore + (playingSince?.let { SystemClock.elapsedRealtime() - it } ?: 0)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) {
            if (firstSoundMs == null) firstSoundMs = now - pressedAt
            playingSince = now
        } else {
            playingSince?.let { playedBefore += now - it }
            playingSince = null
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> if (firstSoundMs != null) bufferingSince = now
            Player.STATE_READY -> bufferingSince?.let {
                rebuffers.put(JSONObject().put("itemIndex", itemIndex).put("atMs", it - pressedAt).put("durationMs", now - it))
                bufferingSince = null
            }
            Player.STATE_ENDED -> finished = true
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (firstSoundMs == null) return
        itemIndex++
        transitionsAtMs.put(SystemClock.elapsedRealtime() - pressedAt)
    }

    override fun onPlayerError(playbackError: PlaybackException) {
        error = playbackError.errorCodeName
        finished = true
    }

    fun toJson(): JSONObject {
        var total = 0L
        for (index in 0 until rebuffers.length()) total += rebuffers.getJSONObject(index).getLong("durationMs")
        return JSONObject()
            .put("timeToFirstSoundMs", firstSoundMs ?: JSONObject.NULL)
            .put("playedMs", playedMs())
            .put("sentencesStarted", itemIndex + 1)
            .put("rebufferCount", rebuffers.length())
            .put("rebufferTotalMs", total)
            .put("rebuffers", rebuffers)
            .put("transitionsAtMs", transitionsAtMs)
            .put("error", error ?: JSONObject.NULL)
    }
}
