package app.lectrice.core.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * File 23 §3.4: one sentence of an audiobook file as a WAV clip (16-bit mono), so the player treats it like a generated
 * clip. The extractor and decoder stay open between calls (sentences are asked for in order); each slice seeks a little
 * before its start, because AAC needs the previous frame to decode the first one cleanly, and trims to the exact sample.
 */
class AudiobookSlicer(private val context: Context) {
    private var openUri: String? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var sampleRate = 0
    private var channels = 1

    @Synchronized
    fun slice(uri: String, startMs: Long, endMs: Long): ByteArray {
        try {
            open(uri)
            // The times come from the file's own map: never trusted to be in order or short.
            val range = SliceLimits.range(startMs, endMs)
            return wav(decode(range.first * US_PER_MS, range.last * US_PER_MS))
        } catch (error: LectriceError) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw LectriceError(ErrorName.FileUnreadable, error)
        }
    }

    @Synchronized
    fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        openUri = null
    }

    private fun open(uri: String) {
        if (openUri == uri && codec != null) return
        close()
        // Kept in the fields from the start, so a failure below is released by close().
        val source = MediaExtractor().also { extractor = it }
        val parsed = Uri.parse(uri)
        if (parsed.scheme == null || parsed.scheme == "file") source.setDataSource(parsed.path ?: uri) else source.setDataSource(context, parsed, null)
        // AAC only (what Lectrice writes): a file can't pick another of the phone's decoders.
        val track = (0 until source.trackCount).firstOrNull { source.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            ?: throw LectriceError(ErrorName.AudiobookUnreadable)
        source.selectTrack(track)
        val format = source.getTrackFormat(track)
        setFormat(format)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
        decoder.configure(format, null, null, 0)
        decoder.start()
        openUri = uri
    }

    private fun setFormat(format: MediaFormat) {
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (!SliceLimits.formatOk(sampleRate, channels)) throw LectriceError(ErrorName.AudiobookUnreadable)
    }

    /** Mono 16-bit samples from [startUs] (inclusive) to [endUs] (exclusive). */
    private fun decode(startUs: Long, endUs: Long): ShortArray {
        val source = extractor!!
        val decoder = codec!!
        source.seekTo((startUs - PREROLL_US).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        val out = ShortBuilder(((endUs - startUs) * sampleRate / US_PER_S).toInt().coerceAtLeast(0))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        val deadline = System.nanoTime() + SliceLimits.MAX_DECODE_NANOS
        while (true) {
            // A file whose timestamps never reach the slice (or a stuck decoder) must not hold the player's loader forever.
            if (System.nanoTime() > deadline) throw LectriceError(ErrorName.AudiobookUnreadable)
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = source.readSampleData(buffer, 0)
                    if (size < 0 || source.sampleTime > endUs + PREROLL_US) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, source.sampleTime, 0)
                        source.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                setFormat(decoder.outputFormat)
                continue
            }
            if (outIndex < 0) continue
            val buffer = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
            val pcm = buffer.asShortBuffer()
            val frames = pcm.remaining() / channels
            for (frame in 0 until frames) {
                val timeUs = info.presentationTimeUs + frame * US_PER_S / sampleRate
                if (timeUs < startUs) continue
                if (timeUs >= endUs) break
                var sum = 0
                for (channel in 0 until channels) sum += pcm.get(frame * channels + channel)
                out.add((sum / channels).toShort())
            }
            val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            val pastEnd = info.presentationTimeUs + frames * US_PER_S / sampleRate >= endUs
            decoder.releaseOutputBuffer(outIndex, false)
            if (end || pastEnd) break
        }
        return out.toArray()
    }

    private fun wav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val buffer = ByteBuffer.allocate(WAV_HEADER + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(FMT_CHUNK).putShort(PCM).putShort(1).putInt(sampleRate).putInt(sampleRate * BYTES_PER_SAMPLE)
            .putShort(BYTES_PER_SAMPLE.toShort()).putShort(BITS)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    /** A growable short array without boxing. */
    private class ShortBuilder(initial: Int) {
        private var data = ShortArray(initial.coerceAtLeast(MIN_CAPACITY))
        private var size = 0

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val US_PER_MS = 1_000L
        const val US_PER_S = 1_000_000L
        /** One or two AAC frames before the slice, discarded after decoding. */
        const val PREROLL_US = 100_000L
        const val TIMEOUT_US = 10_000L
        const val WAV_HEADER = 44
        const val FMT_CHUNK = 16
        const val PCM: Short = 1
        const val BITS: Short = 16
        const val BYTES_PER_SAMPLE = 2
        const val MIN_CAPACITY = 1024
    }
}
