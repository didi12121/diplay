package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.media.AccessUnitMediaSink
import com.shilapi.xcertplay.media.AudioAccessUnit
import com.shilapi.xcertplay.media.AudioAccessUnitRouter
import com.shilapi.xcertplay.media.PcmEncoding
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionSampleFormat
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import com.shilapi.xcertplay.projection.media.ProjectionMediaSinkAdapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PCM byte order must survive the whole chain:
 * CarLinkAudioFormat.sampleFormat → CarLinkAudioChannel → ProjectionAudioFormat
 * → ProjectionMediaMapping → PcmEncoding → renderer (byte swap exactly once
 * for BE, not at all for LE). AAC/Opus ignore sampleFormat.
 */
class CarLinkPcmSampleFormatTest {

    private class RecordingAccessUnitSink : AccessUnitMediaSink {
        val starts = mutableListOf<Pair<AudioFormat, PcmEncoding>>()
        val units = mutableListOf<AudioAccessUnit>()
        override fun attachScreenSurface(streamId: Int, surface: android.view.Surface) {}
        override fun detachScreenSurface(streamId: Int) {}
        override fun setScreenStreamActive(streamId: Int, active: Boolean) {}
        override fun configureVideoStream(
            streamId: Int,
            codec: VideoCodec,
            codecData: ByteArray,
            width: Int,
            height: Int,
        ) {
        }

        override fun submitVideoAccessUnit(
            streamId: Int,
            payload: ByteArray,
            offset: Int,
            length: Int,
            presentationTimeUs: Long,
        ) {
        }

        override fun startAudioAccessUnitStream(
            streamId: AudioStreamId,
            format: AudioFormat,
            pcm: PcmEncoding,
            firstSample: Int,
        ) {
            starts.add(format to pcm)
        }

        override fun submitAudioAccessUnit(streamId: AudioStreamId, unit: AudioAccessUnit) {
            units.add(unit)
        }

        override fun stopAudioAccessUnitStream(streamId: AudioStreamId) {}
    }

    private fun startedPcmEncoding(
        sampleFormat: ProjectionSampleFormat,
        codec: ProjectionAudioCodec = ProjectionAudioCodec.LPCM,
    ): PcmEncoding {
        val sink = RecordingAccessUnitSink()
        val adapter = ProjectionMediaSinkAdapter(sink)
        val channel = CarLinkAudioChannel(CarLinkDiagnostics({ }))
        channel.bind(adapter)
        channel.start(
            CarLinkAudioFormat(
                streamId = 1,
                role = ProjectionAudioChannel.MEDIA,
                codec = codec,
                sampleRate = 48_000,
                channels = 2,
                sampleFormat = sampleFormat,
            ),
        )
        return sink.starts.single().second
    }

    @Test
    fun carLinkLittleEndianPcmReachesRendererAsLe() {
        assertEquals(PcmEncoding.PCM_S16_LE, startedPcmEncoding(ProjectionSampleFormat.PCM_S16_LE))
    }

    @Test
    fun carLinkBigEndianPcmReachesRendererAsBe() {
        assertEquals(PcmEncoding.PCM_S16_BE, startedPcmEncoding(ProjectionSampleFormat.PCM_S16_BE))
    }

    @Test
    fun bigEndianPcmIsByteSwappedExactlyOnce() {
        // BE samples are swapped exactly once at the renderer — the raw frame
        // arrives unmodified and the router applies the declared order once.
        val raw = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val out = mutableListOf<ByteArray>()
        val router = AudioAccessUnitRouter(
            codec = AudioCodecKind.LPCM,
            sampleRate = 48_000,
            channels = 1,
            pcmEncoding = PcmEncoding.PCM_S16_BE,
            onPcm = { out.add(it) },
            onEncoded = { _, _ -> },
        )
        router.route(AudioAccessUnit(raw))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03), out.single())
    }

    @Test
    fun littleEndianPcmIsNotSwapped() {
        val raw = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val out = mutableListOf<ByteArray>()
        val router = AudioAccessUnitRouter(
            codec = AudioCodecKind.LPCM,
            sampleRate = 48_000,
            channels = 1,
            pcmEncoding = PcmEncoding.PCM_S16_LE,
            onPcm = { out.add(it) },
            onEncoded = { _, _ -> },
        )
        router.route(AudioAccessUnit(raw))
        assertArrayEquals(raw, out.single())
    }

    @Test
    fun encodedCodecsIgnoreSampleFormat() {
        // AAC/Opus payloads are codec data, not PCM: the declared PCM order
        // must not alter them.
        for (codec in listOf(ProjectionAudioCodec.AAC_LC, ProjectionAudioCodec.OPUS)) {
            for (order in listOf(ProjectionSampleFormat.PCM_S16_LE, ProjectionSampleFormat.PCM_S16_BE)) {
                val sink = RecordingAccessUnitSink()
                val adapter = ProjectionMediaSinkAdapter(sink)
                val channel = CarLinkAudioChannel(CarLinkDiagnostics({ }))
                channel.bind(adapter)
                channel.start(
                    CarLinkAudioFormat(
                        streamId = 2,
                        role = ProjectionAudioChannel.MEDIA,
                        codec = codec,
                        sampleRate = 48_000,
                        channels = 2,
                        sampleFormat = order,
                    ),
                )
                val payload = byteArrayOf(0x21, 0x10, 0x56, 0x60)
                channel.frame(CarLinkAudioFrame(2, 1000, payload))
                // The raw access unit reaches the renderer byte-for-byte.
                assertArrayEquals(payload, sink.units.single().payload)
                channel.unbind()
            }
        }
    }
}
