package app.lectrice.core.work

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.lectrice.core.data.CoreLibraryRepository
import app.lectrice.core.data.Log
import uniffi.lectrice_ffi.AudioEncoderSink
import uniffi.lectrice_ffi.ChapterMarkRecord
import uniffi.lectrice_ffi.CoreException
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin's side of the core's exporter (spec §8.4): each export part is encoded to AAC-LC (mono, 64 kbit/s) with
 * MediaCodec as the core makes it, and written as ADTS frames; assembling joins the parts with MediaMuxer into an MP4
 * and adds the audiobook tags ([Mp4Tags]). Called on the core's export thread.
 */
class AacEncoderSink : AudioEncoderSink {
    private val parts = ConcurrentHashMap<ULong, PartEncoder>()
    private val nextHandle = AtomicLong(1)

    override fun openPart(path: String, sampleRate: UInt): ULong = guarded {
        val handle = nextHandle.getAndIncrement().toULong()
        parts[handle] = PartEncoder(File(path), sampleRate.toInt())
        handle
    }

    override fun writePcm(handle: ULong, pcm: ByteArray): Unit = guarded { part(handle).write(pcm) }

    override fun closePart(handle: ULong): Unit = guarded { parts.remove(handle)?.finish() ?: throw failed(INTERNAL) }

    override fun abandonPart(handle: ULong) {
        parts.remove(handle)?.abandon()
    }

    override fun assemble(parts: List<String>, outputPath: String, title: String, artist: String, chapters: List<ChapterMarkRecord>): Unit = guarded {
        val output = File(outputPath)
        mux(parts.map(::File), output)
        Mp4Tags.write(output, title, artist, chapters.map { Chapter(it.title, it.startMs.toLong()) })
        Log.i(SCOPE, "assembled parts=${parts.size} bytes=${output.length()} chapters=${chapters.size}")
    }

    private fun part(handle: ULong) = parts[handle] ?: throw failed(INTERNAL)

    /** Joins ADTS parts into one AAC track; each part's samples follow the previous part's last one. */
    private fun mux(inputs: List<File>, output: File) {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var offsetUs = 0L
        val buffer = ByteBuffer.allocateDirect(MAX_FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        try {
            inputs.forEachIndexed { index, input ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(input.path)
                    extractor.selectTrack(0)
                    if (track < 0) {
                        track = muxer.addTrack(extractor.getTrackFormat(0))
                        muxer.start()
                    }
                    var firstUs: Long? = null
                    var lastUs = 0L
                    var frameUs = 0L
                    var frames = 0
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val time = extractor.sampleTime
                        frames++
                        // The encoder's priming frame: kept at the very start, dropped between parts so chapter times don't drift.
                        if (index > 0 && frames == 1) {
                            extractor.advance()
                            continue
                        }
                        val base = firstUs ?: time.also { firstUs = it }
                        info.set(0, size, offsetUs + time - base, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(track, buffer, info)
                        if (time > lastUs) frameUs = time - lastUs
                        lastUs = time
                        extractor.advance()
                    }
                    firstUs?.let { offsetUs += lastUs - it + frameUs }
                } finally {
                    extractor.release()
                }
            }
            muxer.stop()
        } catch (error: IllegalStateException) {
            throw IOException("muxing failed", error)
        } finally {
            muxer.release()
        }
    }

    /** One part: PCM in, ADTS AAC frames out. */
    private class PartEncoder(private val file: File, private val sampleRate: Int) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val out = BufferedOutputStream(file.outputStream())
        private val info = MediaCodec.BufferInfo()
        private val frequencyIndex = ADTS_RATES.indexOf(sampleRate).also { require(it >= 0) { "unsupported sample rate" } }
        private var presentationUs = 0L

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        /** [pcm]: 16-bit little-endian mono samples. */
        fun write(pcm: ByteArray) {
            var offset = 0
            while (offset < pcm.size) {
                val input = codec.dequeueInputBuffer(TIMEOUT_US)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)!!
                    buffer.clear()
                    val count = minOf(buffer.remaining() / 2 * 2, pcm.size - offset)
                    buffer.put(pcm, offset, count)
                    codec.queueInputBuffer(input, 0, count, presentationUs, 0)
                    presentationUs += count / 2 * MICROS_PER_SECOND / sampleRate
                    offset += count
                }
                drain(endOfStream = false)
            }
        }

        fun finish() {
            while (true) {
                val input = codec.dequeueInputBuffer(TIMEOUT_US)
                if (input >= 0) {
                    codec.queueInputBuffer(input, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(endOfStream = false)
            }
            drain(endOfStream = true)
            release()
        }

        fun abandon() {
            runCatching { release() }
            file.delete()
        }

        private fun release() {
            codec.stop()
            codec.release()
            out.close()
        }

        private fun drain(endOfStream: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                when {
                    index >= 0 -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            val frame = ByteArray(info.size).also(buffer::get)
                            out.write(adtsHeader(frame.size))
                            out.write(frame)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    else -> Unit
                }
            }
        }

        /** 7-byte ADTS header: MPEG-4, AAC-LC, no CRC, mono. */
        private fun adtsHeader(payload: Int): ByteArray {
            val length = payload + ADTS_HEADER
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                ((AAC_LC_OBJECT - 1 shl 6) or (frequencyIndex shl 2)).toByte(),
                ((MONO shl 6) or (length shr 11)).toByte(),
                ((length shr 3) and 0xFF).toByte(),
                (((length and 0x7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }
    }

    /** Runs [block], turning failures into the core's errors (`StorageFull` when the disk is full). */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: CoreException) {
        throw error
    } catch (error: IOException) {
        Log.w(SCOPE, "encoder I/O failed", error)
        throw failed(if (CoreLibraryRepository.isOutOfSpace(error)) STORAGE_FULL else STORAGE_FAILED)
    } catch (error: RuntimeException) {
        Log.w(SCOPE, "encoder failed", error)
        throw failed(INTERNAL)
    }

    private fun failed(name: Pair<String, UInt>) = CoreException.Failed(name.first, name.second, true, name.first)

    private companion object {
        const val SCOPE = "AacEncoder"
        const val BIT_RATE = 64_000
        const val INPUT_BYTES = 16_384
        const val MAX_FRAME_BYTES = 8_192
        const val TIMEOUT_US = 10_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val ADTS_HEADER = 7
        const val AAC_LC_OBJECT = 2
        const val MONO = 1
        val ADTS_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val STORAGE_FULL = "StorageFull" to 4004u
        val STORAGE_FAILED = "StorageFailed" to 9001u
        val INTERNAL = "Internal" to 9999u
    }
}
