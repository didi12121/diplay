package com.shilapi.xcertplay.carlink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * DEVELOPER TEST HARNESS ONLY — never referenced by production flows.
 *
 * Generates a real H.264 stream (a hardware encoder painting a moving test
 * pattern into an input Surface) and a real PCM sine tone, then feeds them
 * through [MockCarLinkProtocolAdapter]'s typed media hooks. This lets a
 * developer verify the whole pipeline end to end on a head unit without any
 * phone or SDK:
 *
 * ```
 * MockCarLinkTestPattern → MockCarLinkProtocolAdapter → CarLinkController
 *   → ProjectionMediaSinkAdapter → AndroidMediaSink → MediaCodec/AudioTrack
 *   → real Surface + real speaker
 * ```
 */
class MockCarLinkTestPattern(
    private val adapter: MockCarLinkProtocolAdapter,
    private val width: Int = 640,
    private val height: Int = 360,
    private val frameRate: Int = 15,
    private val sampleRate: Int = 48_000,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** Opens the audio stream and starts the encoder/tone threads. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        adapter.simulateAudioStarted(
            CarLinkAudioFormat(
                streamId = TEST_AUDIO_STREAM_ID,
                role = ProjectionAudioChannel.MEDIA,
                codec = ProjectionAudioCodec.LPCM,
                sampleRate = sampleRate,
                channels = 1,
            ),
        )
        audioThread = Thread(::runAudio, "mock-carlink-audio").apply { isDaemon = true; start() }
        videoThread = Thread(::runVideo, "mock-carlink-video").apply { isDaemon = true; start() }
    }

    override fun close() {
        running.set(false)
        videoThread?.interrupt()
        audioThread?.interrupt()
        adapter.simulateAudioStopped(TEST_AUDIO_STREAM_ID)
    }

    // ---- Audio: 440 Hz sine as PCM16 little-endian ----

    private fun runAudio() {
        val frameMillis = 20
        val samples = sampleRate * frameMillis / 1000
        var phase = 0.0
        val step = 2.0 * PI * 440.0 / sampleRate
        var presentationTimeUs = 0L
        while (running.get()) {
            val payload = ByteArray(samples * 2)
            for (index in 0 until samples) {
                val sample = (sin(phase) * 8000).toInt()
                phase += step
                payload[index * 2] = (sample and 0xff).toByte()
                payload[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
            }
            adapter.simulateAudioFrame(
                CarLinkAudioFrame(TEST_AUDIO_STREAM_ID, presentationTimeUs, payload),
            )
            presentationTimeUs += frameMillis * 1000L
            try {
                Thread.sleep(frameMillis.toLong())
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    // ---- Video: H.264 encoder painting a moving bar ----

    private fun runVideo() {
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            val paint = Paint()
            val frameIntervalMs = 1000L / frameRate
            val info = MediaCodec.BufferInfo()
            var configSent = false
            var frameIndex = 0L
            while (running.get()) {
                drawTestPattern(inputSurface, paint, frameIndex)
                frameIndex++
                drainEncoder(encoder, info) { buffer, offset, length, ptsUs, keyFrame ->
                    if (!configSent) {
                        configSent = true
                        encoder.outputFormat.getByteBuffer("csd-0")?.let { sps ->
                            encoder.outputFormat.getByteBuffer("csd-1")?.let { pps ->
                                adapter.simulateVideoConfig(
                                    ProjectionVideoConfig(
                                        codec = ProjectionVideoCodec.H264,
                                        codecData = avcDecoderConfigurationRecord(sps, pps),
                                        width = width,
                                        height = height,
                                    ),
                                )
                            }
                        }
                    }
                    val payload = ByteArray(length)
                    buffer.position(offset)
                    buffer.get(payload)
                    adapter.simulateVideoFrame(
                        ProjectionVideoFrame(
                            codec = ProjectionVideoCodec.H264,
                            width = width,
                            height = height,
                            presentationTimeUs = ptsUs,
                            keyFrame = keyFrame,
                            payload = payload,
                        ),
                    )
                }
                try {
                    Thread.sleep(frameIntervalMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
        } catch (error: Exception) {
            // Test harness: log and give up quietly — production never runs this.
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
        }
    }

    private fun drawTestPattern(surface: Surface, paint: Paint, frame: Long) {
        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            return
        }
        try {
            canvas.drawColor(Color.rgb(20, 30, 44))
            paint.color = Color.rgb(166, 200, 255)
            val barWidth = width / 8f
            val offset = ((frame * 12) % (width + barWidth.toInt())).toFloat()
            canvas.drawRect(offset, 0f, offset + barWidth, height.toFloat(), paint)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        onFrame: (buffer: java.nio.ByteBuffer, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) -> Unit,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        onFrame(
                            buffer,
                            info.offset,
                            info.size,
                            info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                        )
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * Builds an AVCDecoderConfigurationRecord (avcC) from encoder CSD buffers,
     * which carry start-code-prefixed SPS/PPS.
     */
    private fun avcDecoderConfigurationRecord(spsBuffer: java.nio.ByteBuffer, ppsBuffer: java.nio.ByteBuffer): ByteArray {
        val sps = stripStartCode(bufferBytes(spsBuffer))
        val pps = stripStartCode(bufferBytes(ppsBuffer))
        val record = ByteArray(11 + sps.size + pps.size)
        var cursor = 0
        record[cursor++] = 1 // configurationVersion
        record[cursor++] = sps.getOrElse(1) { 0x42 } // AVCProfileIndication
        record[cursor++] = sps.getOrElse(2) { 0 } // profile_compatibility
        record[cursor++] = sps.getOrElse(3) { 0x1e } // AVCLevelIndication
        record[cursor++] = 0xff.toByte() // lengthSizeMinusOne
        record[cursor++] = 0xe1.toByte() // numOfSPS
        record[cursor++] = ((sps.size shr 8) and 0xff).toByte()
        record[cursor++] = (sps.size and 0xff).toByte()
        sps.copyInto(record, cursor)
        cursor += sps.size
        record[cursor++] = 1 // numOfPPS
        record[cursor++] = ((pps.size shr 8) and 0xff).toByte()
        record[cursor++] = (pps.size and 0xff).toByte()
        pps.copyInto(record, cursor)
        return record
    }

    private fun bufferBytes(buffer: java.nio.ByteBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        val offset = when {
            nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
            nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3
            else -> 0
        }
        return if (offset == 0) nal else nal.copyOfRange(offset, nal.size)
    }

    companion object {
        const val TEST_AUDIO_STREAM_ID = 900
    }
}
