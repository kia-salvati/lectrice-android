package app.lectrice.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.lectrice.core.data.EngineHost
import app.lectrice.core.data.RustCore
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import app.lectrice.core.model.TimedSentence
import uniffi.lectrice_ffi.SpeakArgs
import java.io.IOException

/** What the next `speak_sentence` calls use; written by the controller (main thread), read on ExoPlayer's loader thread. */
class SpeechParams {
    @Volatile var voiceId: String = ""
    @Volatile var rate: Float = 1f
    @Volatile var readSkipped: Boolean = false

    /** False while the read-ahead governor is Off (file 08 §3.3). */
    @Volatile var planAhead: Boolean = true

    /** Updated with every clip: estimated 1× audio already made after it (core C25). */
    @Volatile var readyAheadMs: Long = 0

    /** Set while the current book plays from its audiobook file (file 23): clips are slices of that file. */
    @Volatile var audiobook: AudiobookPlayback? = null
}

/** The current book's audiobook file and where each sentence is in it. */
class AudiobookPlayback(val documentId: String, val uri: String, val slices: Map<Int, TimedSentence>)

/** `lectrice://sentence/<docId>/<sentenceId>` */
object SentenceUri {
    private const val SCHEME = "lectrice"
    private const val HOST = "sentence"

    fun of(documentId: String, sentenceId: Int): Uri = Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(documentId).appendPath(sentenceId.toString()).build()

    fun parse(uri: Uri): Pair<String, Int>? {
        if (uri.scheme != SCHEME || uri.authority != HOST) return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return segments[0] to (segments[1].toIntOrNull() ?: return null)
    }
}

/** The core's error, carried through ExoPlayer's `IOException` path to the controller's `onPlayerError`. */
class SpeechIOException(val error: LectriceError) : IOException(error.name.name)

/**
 * Gets a sentence's WAV from the Rust core (spec §7.3). `open` blocks on ExoPlayer's loader thread until the clip is
 * cached or made: the core's one speech queue is the only place speech is made (playback and read-ahead share it).
 */
@UnstableApi
class SentenceDataSource(
    private val core: RustCore,
    private val engine: EngineHost,
    private val params: SpeechParams,
    private val slicer: AudiobookSlicer,
) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var bytes: ByteArray = EMPTY
    private var position = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val (documentId, sentenceId) = SentenceUri.parse(dataSpec.uri) ?: throw IOException("not a sentence uri")
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val audiobook = params.audiobook?.takeIf { it.documentId == documentId }
        bytes = if (audiobook != null) fromAudiobook(audiobook, sentenceId) else generated(documentId, sentenceId)
        position = dataSpec.position.toInt().coerceIn(0, bytes.size)
        opened = true
        transferStarted(dataSpec)
        val remaining = bytes.size - position
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining.toLong()) else remaining.toLong()
    }

    private fun generated(documentId: String, sentenceId: Int): ByteArray {
        if (engine.ensureLoadedBlocking() == null) {
            throw SpeechIOException(LectriceError(if (engine.activeModel() == null) ErrorName.ModelMissing else ErrorName.SpeechEngineUnavailable))
        }
        val spoken = try {
            core.blocking("speak_sentence") { it.speakSentence(SpeakArgs(documentId, sentenceId.toUInt(), params.voiceId, params.rate, params.readSkipped, params.planAhead)) }
        } catch (error: LectriceError) {
            throw SpeechIOException(error)
        }
        params.readyAheadMs = spoken.readyAheadMs.toLong()
        return spoken.wav
    }

    /** File 23 §3.4: the sentence's slice of the file; no engine, nothing cached. */
    private fun fromAudiobook(audiobook: AudiobookPlayback, sentenceId: Int): ByteArray {
        val slice = audiobook.slices[sentenceId] ?: throw SpeechIOException(LectriceError(ErrorName.SentenceNotFound))
        return try {
            slicer.slice(audiobook.uri, slice.startMs, slice.endMs)
        } catch (error: LectriceError) {
            throw SpeechIOException(error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = bytes.size - position
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val count = minOf(length, remaining)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
        bytes = EMPTY
    }

    class Factory(private val core: RustCore, private val engine: EngineHost, private val params: SpeechParams, private val slicer: AudiobookSlicer) : DataSource.Factory {
        override fun createDataSource(): DataSource = SentenceDataSource(core, engine, params, slicer)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
