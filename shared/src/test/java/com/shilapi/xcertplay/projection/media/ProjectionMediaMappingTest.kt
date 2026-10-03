package com.shilapi.xcertplay.projection.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Video format and audio channel mapping across the shared media layer. */
class ProjectionMediaMappingTest {

    // ---- Video codec mapping ----

    @Test
    fun videoCodecsRoundTrip() {
        for (codec in ProjectionVideoCodec.entries) {
            assertEquals(codec, ProjectionMediaMapping.toProjectionCodec(ProjectionMediaMapping.toSharedCodec(codec)))
        }
        assertEquals(VideoCodec.H264, ProjectionMediaMapping.toSharedCodec(ProjectionVideoCodec.H264))
        assertEquals(VideoCodec.H265, ProjectionMediaMapping.toSharedCodec(ProjectionVideoCodec.H265))
    }

    // ---- Audio channel mapping ----

    @Test
    fun carPlayWireTypesMapToNeutralChannels() {
        assertEquals(ProjectionAudioChannel.MEDIA, ProjectionMediaMapping.channelForWireType("media"))
        assertEquals(ProjectionAudioChannel.NAVIGATION, ProjectionMediaMapping.channelForWireType("alert"))
        assertEquals(ProjectionAudioChannel.PHONE_CALL, ProjectionMediaMapping.channelForWireType("telephony"))
        assertEquals(
            ProjectionAudioChannel.VOICE_ASSISTANT,
            ProjectionMediaMapping.channelForWireType("speechrecognition"),
        )
    }

    @Test
    fun neutralChannelsRoundTripThroughInternalChannels() {
        for (channel in ProjectionAudioChannel.entries) {
            assertEquals(
                channel,
                ProjectionMediaMapping.toProjectionChannel(ProjectionMediaMapping.toSharedChannel(channel)),
            )
        }
    }

    @Test
    fun wireTypeLabelsAreProtocolNeutralNames() {
        assertEquals("media", ProjectionMediaMapping.wireTypeFor(ProjectionAudioChannel.MEDIA))
        assertEquals("alert", ProjectionMediaMapping.wireTypeFor(ProjectionAudioChannel.NAVIGATION))
        assertEquals("telephony", ProjectionMediaMapping.wireTypeFor(ProjectionAudioChannel.PHONE_CALL))
        assertEquals("speechrecognition", ProjectionMediaMapping.wireTypeFor(ProjectionAudioChannel.VOICE_ASSISTANT))
    }

    // ---- Audio codec mapping ----

    @Test
    fun audioCodecsRoundTrip() {
        for (codec in ProjectionAudioCodec.entries) {
            assertEquals(codec, ProjectionMediaMapping.toProjectionAudioCodec(ProjectionMediaMapping.toSharedAudioCodec(codec)))
        }
        assertEquals(AudioCodecKind.OPUS, ProjectionMediaMapping.toSharedAudioCodec(ProjectionAudioCodec.OPUS))
    }

    @Test
    fun sharedFormatCarriesWireTypeForMapping() {
        val format = ProjectionAudioFormat(
            codec = ProjectionAudioCodec.AAC_LC,
            sampleRate = 44_100,
            channels = 2,
            channel = ProjectionAudioChannel.NAVIGATION,
            payloadType = 96,
            wireType = "alert",
        )
        val shared: AudioFormat = ProjectionMediaMapping.toSharedFormat(format)
        assertEquals(AudioCodecKind.AAC_LC, shared.codec)
        assertEquals(44_100, shared.sampleRate)
        assertEquals(2, shared.channels)
        assertEquals("alert", shared.audioType)
    }

    // ---- Sink adapter ----

    private class RecordingSink : MediaSink {
        val videoCodecs = mutableListOf<VideoCodec>()
        val videoConfigs = mutableListOf<ByteArray>()
        val videoFrames = mutableListOf<ByteArray>()
        var streamActive: Boolean? = null

        override fun onVideoCodec(type: Int, codec: VideoCodec) {
            videoCodecs.add(codec)
        }

        override fun onVideoConfig(type: Int, codecData: ByteArray) {
            videoConfigs.add(codecData)
        }

        override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
            videoFrames.add(naluBytes)
        }

        override fun onScreenStreamActive(type: Int, active: Boolean) {
            streamActive = active
        }
    }

    @Test
    fun sinkAdapterForwardsVideoWithoutCopyingWholeBuffers() {
        val sink = RecordingSink()
        val adapter = ProjectionMediaSinkAdapter(sink)
        val payload = byteArrayOf(0, 1, 2, 3, 4)
        adapter.onVideoConfig(ProjectionVideoConfig(ProjectionVideoCodec.H265, byteArrayOf(9, 9)))
        adapter.onVideoFrame(
            ProjectionVideoFrame(
                codec = ProjectionVideoCodec.H265,
                width = 1920,
                height = 720,
                presentationTimeUs = 1L,
                keyFrame = true,
                payload = payload,
            ),
        )
        // Codec is announced once with the config, not per frame.
        assertEquals(listOf(VideoCodec.H265), sink.videoCodecs)
        assertArrayEquals(byteArrayOf(9, 9), sink.videoConfigs.single())
        // Whole-buffer windows must be forwarded by reference, not copied.
        assertSame(payload, sink.videoFrames.single())
    }

    @Test
    fun sinkAdapterCopiesOnlySubRangePayloads() {
        val sink = RecordingSink()
        val adapter = ProjectionMediaSinkAdapter(sink)
        val payload = byteArrayOf(0, 1, 2, 3, 4)
        adapter.onVideoFrame(
            ProjectionVideoFrame(
                codec = ProjectionVideoCodec.H264,
                width = 0,
                height = 0,
                presentationTimeUs = 0L,
                keyFrame = false,
                payload = payload,
                offset = 1,
                length = 3,
            ),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), sink.videoFrames.single())
    }

    @Test
    fun sinkAdapterForwardsStreamActiveFlag() {
        val sink = RecordingSink()
        val adapter = ProjectionMediaSinkAdapter(sink)
        adapter.onVideoStreamActive(true)
        assertEquals(true, sink.streamActive)
        adapter.onVideoStreamActive(false)
        assertEquals(false, sink.streamActive)
    }
}
