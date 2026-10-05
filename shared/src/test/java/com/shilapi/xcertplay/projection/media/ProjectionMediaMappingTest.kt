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

    // ---- PCM byte-order mapping ----

    @Test
    fun pcmSampleFormatMapsToRendererEncoding() {
        assertEquals(
            com.shilapi.xcertplay.media.PcmEncoding.PCM_S16_LE,
            ProjectionMediaMapping.toPcmEncoding(com.shilapi.xcertplay.projection.ProjectionSampleFormat.PCM_S16_LE),
        )
        assertEquals(
            com.shilapi.xcertplay.media.PcmEncoding.PCM_S16_BE,
            ProjectionMediaMapping.toPcmEncoding(com.shilapi.xcertplay.projection.ProjectionSampleFormat.PCM_S16_BE),
        )
    }

    // Sink-adapter forwarding (codec/size/PTS/window preservation) is covered
    // by ProjectionMediaSinkAdapterTest against the neutral AccessUnitMediaSink.
}
