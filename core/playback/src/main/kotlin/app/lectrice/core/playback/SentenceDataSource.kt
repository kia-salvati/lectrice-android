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

/** Where a sentence's audio comes from, chosen when a book loads. [audio] blocks on ExoPlayer's loader thread. */
sealed interface SentenceAudioSource {
    /** The sentence's WAV; throws [SpeechIOException]. */
    fun audio(documentId: String, sentenceId: Int): ByteArray
}

/** Speech made by the core (on the phone, or by the computer helper), cached or made now. */
class GeneratedSpeech(private val core: RustCore, private val engine: EngineHost, private val params: SpeechParams) : SentenceAudioSource {
    override fun audio(documentId: String, sentenceId: Int): ByteArray {
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
}

/** The book's audiobook file: each sentence is its slice of the file; no engine, nothing cached. */
class AudiobookSlices(val playback: AudiobookPlayback, private val slicer: AudiobookSlicer) : SentenceAudioSource {
    override fun audio(documentId: String, sentenceId: Int): ByteArray {
        val slice = playback.slices[sentenceId]?.takeIf { documentId == playback.documentId } ?: throw SpeechIOException(LectriceError(ErrorName.SentenceNotFound))
        return try {
            slicer.slice(playback.uri, slice.startMs, slice.endMs)
        } catch (error: LectriceError) {
            throw SpeechIOException(error)
        }
    }
}

/**
 * Gets a sentence's WAV from the current [SentenceAudioSource]. `open` blocks on ExoPlayer's loader thread until the clip
 * is ready: for speech, the core's one speech queue is the only place it is made (playback and read-ahead share it).
 */
@UnstableApi
class SentenceDataSource(private val source: () -> SentenceAudioSource) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var bytes: ByteArray = EMPTY
    private var position = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val (documentId, sentenceId) = SentenceUri.parse(dataSpec.uri) ?: throw IOException("not a sentence uri")
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        bytes = source().audio(documentId, sentenceId)
        position = dataSpec.position.toInt().coerceIn(0, bytes.size)
        opened = true
        transferStarted(dataSpec)
        val remaining = bytes.size - position
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining.toLong()) else remaining.toLong()
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

    class Factory(private val source: () -> SentenceAudioSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = SentenceDataSource(source)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
