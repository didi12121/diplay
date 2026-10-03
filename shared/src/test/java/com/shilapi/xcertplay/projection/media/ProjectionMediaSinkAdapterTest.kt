package com.shilapi.xcertplay.projection.media

import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AccessUnitMediaSink
import com.shilapi.xcertplay.media.AudioAccessUnit
import com.shilapi.xcertplay.media.PcmEncoding
import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionSampleFormat
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The bridge from projection media to the shared renderer must preserve the
 * real coded size, real PTS and raw payload windows (no RTP stripping and no
 * synthesized framing).
 */
class ProjectionMediaSinkAdapterTest {

    private class RecordingAccessUnitSink : AccessUnitMediaSink {
        data class VideoConfig(val codec: VideoCodec, val data: ByteArray, val width: Int, val height: Int)
        data class VideoFrame(val payload: ByteArray, val pts: Long)
        data class AudioStart(val id: AudioStreamId, val format: AudioFormat, val pcm: PcmEncoding)
        data class AudioUnit(val id: AudioStreamId, val unit: AudioAccessUnit)

        val videoConfigs = mutableListOf<VideoConfig>()
        val videoFrames = mutableListOf<VideoFrame>()
        val audioStarts = mutableListOf<AudioStart>()
        val audioUnits = mutableListOf<AudioUnit>()
        val audioStops = mutableListOf<AudioStreamId>()
        val surfaceAttach = mutableListOf<android.view.Surface>()
        val surfaceDetach = mutableListOf<Int>()
        val streamActive = mutableListOf<Boolean>()

        override fun attachScreenSurface(streamId: Int, surface: android.view.Surface) {
            surfaceAttach.add(surface)
        }

        override fun detachScreenSurface(streamId: Int) {
            surfaceDetach.add(streamId)
        }

        override fun setScreenStreamActive(streamId: Int, active: Boolean) {
            streamActive.add(active)
        }

        override fun configureVideoStream(streamId: Int, codec: VideoCodec, codecData: ByteArray, width: Int, height: Int) {
            videoConfigs.add(VideoConfig(codec, codecData, width, height))
        }

        override fun submitVideoAccessUnit(
            streamId: Int,
            payload: ByteArray,
            offset: Int,
            length: Int,
            presentationTimeUs: Long,
        ) {
            val window = if (offset == 0 && length == payload.size) payload
            else payload.copyOfRange(offset, offset + length)
            videoFrames.add(VideoFrame(window, presentationTimeUs))
        }

        override fun startAudioAccessUnitStream(
            streamId: AudioStreamId,
            format: AudioFormat,
            pcm: PcmEncoding,
            firstSample: Int,
        ) {
            audioStarts.add(AudioStart(streamId, format, pcm))
        }

        override fun submitAudioAccessUnit(streamId: AudioStreamId, unit: AudioAccessUnit) {
            audioUnits.add(AudioUnit(streamId, unit))
        }

        override fun stopAudioAccessUnitStream(streamId: AudioStreamId) {
            audioStops.add(streamId)
        }
    }

    private val sink = RecordingAccessUnitSink()
    private val adapter = ProjectionMediaSinkAdapter(sink, screenType = 110)

    @Test
    fun videoConfigCarriesRealCodecCodedSize() {
        adapter.onVideoConfig(
            ProjectionVideoConfig(
                codec = ProjectionVideoCodec.H265,
                codecData = byteArrayOf(1, 2),
                width = 1920,
                height = 1080,
            ),
        )
        val config = sink.videoConfigs.single()
        assertEquals(VideoCodec.H265, config.codec)
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertArrayEquals(byteArrayOf(1, 2), config.data)
    }

    @Test
    fun videoFramePreservesPtsAndPayloadWindow() {
        val payload = byteArrayOf(0, 1, 2, 3, 4, 5)
        adapter.onVideoFrame(
            ProjectionVideoFrame(
                codec = ProjectionVideoCodec.H264,
                width = 1600,
                height = 900,
                presentationTimeUs = 33_333,
                keyFrame = true,
                payload = payload,
                offset = 1,
                length = 4,
            ),
        )
        val frame = sink.videoFrames.single()
        // Real PTS reaches the decoder input — never re-generated.
        assertEquals(33_333L, frame.pts)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.payload)
    }

    @Test
    fun wholeBufferVideoFrameIsForwardedByReference() {
        val payload = byteArrayOf(7, 7, 7)
        adapter.onVideoFrame(
            ProjectionVideoFrame(
                codec = ProjectionVideoCodec.H264,
                width = 0,
                height = 0,
                presentationTimeUs = 0,
                keyFrame = false,
                payload = payload,
            ),
        )
        assertSame(payload, sink.videoFrames.single().payload)
    }

    @Test
    fun carLinkRawAudioIsForwardedWithoutStripping() {
        val id = ProjectionAudioStreamId(3, ProjectionAudioChannel.MEDIA)
        val format = ProjectionAudioFormat(
            codec = ProjectionAudioCodec.AAC_LC,
            sampleRate = 48_000,
            channels = 2,
            channel = ProjectionAudioChannel.MEDIA,
        )
        adapter.onAudioStarted(id, format)
        val payload = byteArrayOf(0x21, 0x10, 0x56)
        adapter.onAudioFrame(id, 20_000, payload, 0, payload.size)
        adapter.onAudioStopped(id)

        // Started with a typed PCM order.
        val start = sink.audioStarts.single()
        assertEquals(PcmEncoding.PCM_S16_LE, start.pcm)
        assertEquals(AudioCodecKind.AAC_LC, start.format.codec)
        // Frame arrives as a raw access unit: no 12-byte stripping, real PTS.
        val unit = sink.audioUnits.single().unit
        assertEquals(20_000L, unit.presentationTimeUs)
        assertEquals(3, unit.length)
        assertSame(payload, unit.payload)
        assertEquals(1, sink.audioStops.size)
    }

    @Test
    fun pcmByteOrderIsMappedFromTheNeutralFormat() {
        val id = ProjectionAudioStreamId(4, ProjectionAudioChannel.MEDIA)
        val be = ProjectionAudioFormat(
            codec = ProjectionAudioCodec.LPCM,
            sampleRate = 44_100,
            channels = 1,
            channel = ProjectionAudioChannel.MEDIA,
            sampleFormat = ProjectionSampleFormat.PCM_S16_BE,
        )
        adapter.onAudioStarted(id, be)
        assertEquals(PcmEncoding.PCM_S16_BE, sink.audioStarts.single().pcm)

        adapter.onAudioStopped(id)
        val le = ProjectionAudioFormat(
            codec = ProjectionAudioCodec.LPCM,
            sampleRate = 44_100,
            channels = 1,
            channel = ProjectionAudioChannel.MEDIA,
            sampleFormat = ProjectionSampleFormat.PCM_S16_LE,
        )
        adapter.onAudioStarted(id, le)
        assertEquals(PcmEncoding.PCM_S16_LE, sink.audioStarts.last().pcm)
    }

    @Test
    fun streamActivityIsForwarded() {
        adapter.onVideoStreamActive(true)
        adapter.onVideoStreamActive(false)
        assertEquals(listOf(true, false), sink.streamActive)
    }
}
