package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioFormat
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLink media plumbing and adapter-exception boundary:
 * audio started → frame → stopped must reach the ProjectionAudioSink, video
 * config/frame must retain codec/size/PTS/keyframe/window, adapter exceptions
 * become ProjectionState.Error, and resources follow the session transport.
 */
class CarLinkMediaFlowTest {

    private class RecordingVideoSink : ProjectionVideoSink {
        val configs = mutableListOf<ProjectionVideoConfig>()
        val frames = mutableListOf<ProjectionVideoFrame>()
        val active = mutableListOf<Boolean>()

        override fun onVideoConfig(config: ProjectionVideoConfig) {
            configs.add(config)
        }

        override fun onVideoFrame(frame: ProjectionVideoFrame) {
            frames.add(frame)
        }

        override fun onVideoStreamActive(active: Boolean) {
            this.active.add(active)
        }
    }

    private class RecordingAudioSink : ProjectionAudioSink {
        data class Started(val id: ProjectionAudioStreamId, val format: ProjectionAudioFormat)
        data class Frame(
            val id: ProjectionAudioStreamId,
            val pts: Long,
            val payload: ByteArray,
            val offset: Int,
            val length: Int,
        )

        val started = mutableListOf<Started>()
        val frames = mutableListOf<Frame>()
        val stopped = mutableListOf<ProjectionAudioStreamId>()

        override fun onAudioStarted(id: ProjectionAudioStreamId, format: ProjectionAudioFormat) {
            started.add(Started(id, format))
        }

        override fun onAudioFrame(
            id: ProjectionAudioStreamId,
            presentationTimeUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int,
        ) {
            frames.add(Frame(id, presentationTimeUs, payload, offset, length))
        }

        override fun onAudioStopped(id: ProjectionAudioStreamId) {
            stopped.add(id)
        }
    }

    private fun backendOf(
        adapter: CarLinkProtocolAdapter,
        video: RecordingVideoSink = RecordingVideoSink(),
        audio: RecordingAudioSink = RecordingAudioSink(),
    ): Triple<CarLinkProjectionBackend, RecordingVideoSink, RecordingAudioSink> {
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(video, audio),
        )
        return Triple(backend, video, audio)
    }

    // ---- audio lifecycle ----

    @Test
    fun audioStartedFrameStoppedReachesProjectionAudioSink() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (backend, _, audio) = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)

        // Mock streams one typed audio lifecycle on connect when enabled; drive
        // it explicitly here so the assertion covers started → frame → stopped.
        adapter.simulateAudioStream(streamId = 42, role = ProjectionAudioChannel.NAVIGATION)

        assertEquals("exactly one started event", 1, audio.started.size)
        assertEquals(1, audio.frames.size)
        assertEquals(1, audio.stopped.size)
        // Order: started before frame before stopped.
        val startedId = audio.started.single().id
        val frame = audio.frames.single()
        val stoppedId = audio.stopped.single()
        assertEquals(42, startedId.stream)
        assertEquals(42, frame.id.stream)
        assertEquals(42, stoppedId.stream)
        assertEquals(ProjectionAudioChannel.NAVIGATION, startedId.channel)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_PTS_US, frame.pts)
        assertArrayEquals(MockCarLinkProtocolAdapter.FAKE_AUDIO_PAYLOAD, frame.payload.copyOfRange(frame.offset, frame.offset + frame.length))
    }

    @Test
    fun audioFramesOfUnknownStreamsAreNotSilentlySwallowed() {
        // Channel-level: a frame of a stream that was never started must not
        // reach the sink (it cannot be rendered without a format) — but the
        // channel must say so via diagnostics rather than swallow it silently.
        val events = mutableListOf<String>()
        val audio = CarLinkAudioChannel(CarLinkDiagnostics({ events.add(it) }))
        val sink = RecordingAudioSink()
        audio.bind(sink)

        audio.frame(CarLinkAudioFrame(9, 0L, byteArrayOf(1)))

        assertTrue(sink.frames.isEmpty())
        assertTrue(
            "expected an audio-frame-unknown-stream diagnostic, got $events",
            events.any { it.contains("audio-frame-unknown-stream") },
        )
    }

    @Test
    fun frameAfterStopIsDroppedAndReported() {
        val events = mutableListOf<String>()
        val audio = CarLinkAudioChannel(CarLinkDiagnostics({ events.add(it) }))
        val sink = RecordingAudioSink()
        audio.bind(sink)
        audio.start(CarLinkAudioFormat(5, ProjectionAudioChannel.MEDIA, ProjectionAudioCodec.OPUS, 48_000, 2))
        audio.stop(5)

        audio.frame(CarLinkAudioFrame(5, 0L, byteArrayOf(1)))

        assertEquals(1, sink.started.size)
        assertEquals(1, sink.stopped.size)
        assertTrue(sink.frames.isEmpty())
        assertTrue(events.any { it.contains("audio-frame-unknown-stream") })
    }

    @Test
    fun audioFormatIsBackendNeutralWithTypedRole() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = true)
        val (backend, _, audio) = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)

        val started = audio.started.first()
        assertEquals(ProjectionAudioChannel.MEDIA, started.format.channel)
        assertEquals(ProjectionAudioCodec.AAC_LC, started.format.codec)
        assertEquals(48_000, started.format.sampleRate)
        assertEquals(2, started.format.channels)
        // No Apple wire string leaks into the neutral format.
        assertEquals("", started.format.wireType)
    }

    // ---- video retention ----

    @Test
    fun videoConfigAndFrameRetainCodecSizePtsKeyFrameAndWindow() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = true)
        val (backend, video, _) = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)

        val config = video.configs.single()
        assertEquals(ProjectionVideoCodec.H264, config.codec)
        assertArrayEquals(MockCarLinkProtocolAdapter.FAKE_CODEC_DATA, config.codecData)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_WIDTH, config.width)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_HEIGHT, config.height)

        val frame = video.frames.single()
        assertEquals(ProjectionVideoCodec.H264, frame.codec)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_WIDTH, frame.width)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_HEIGHT, frame.height)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_PTS_US, frame.presentationTimeUs)
        assertTrue(frame.keyFrame)
        assertEquals(0, frame.offset)
        assertEquals(MockCarLinkProtocolAdapter.FAKE_VIDEO_PAYLOAD.size, frame.length)
        assertArrayEquals(MockCarLinkProtocolAdapter.FAKE_VIDEO_PAYLOAD, frame.payload)
    }

    @Test
    fun mediaSinksAreAcquiredOnSessionStartAndClosedOnSessionEnd() {
        var closed = 0
        val video = RecordingVideoSink()
        val audio = RecordingAudioSink()
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(video, audio) { closed += 1 },
        )
        backend.initialize()
        backend.start()
        backend.connect(null)
        assertEquals("sinks must not close while the session runs", 0, closed)

        adapter.simulateSessionEnd("peer detached")
        assertEquals(1, closed)
    }

    // ---- adapter exception boundary ----

    @Test
    fun adapterExceptionBecomesProjectionStateErrorWithCause() {
        val adapter = object : CarLinkProtocolAdapter by MockCarLinkProtocolAdapter() {
            override fun connect(device: CarLinkDevice, session: CarLinkSessionToken) {
                throw IllegalStateException("sdk exploded")
            }
        }
        val (backend, _, _) = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)

        val error = backend.state
        assertTrue("expected Error, got $error", error is ProjectionState.Error)
        (error as ProjectionState.Error).let {
            assertEquals(ProjectionErrorCode.PROTOCOL_ERROR, it.code)
            assertEquals("carlink", it.backendId)
            assertTrue(it.message.contains("connect"))
            assertNotNull(it.cause)
            assertTrue(it.cause is IllegalStateException)
        }
        // Session/resource state is cleaned up.
        assertFalse(backend.isSessionActive)
    }

    @Test
    fun adapterExceptionOnDiscoveryBecomesErrorAndKeepsProviderState() {
        val adapter = object : CarLinkProtocolAdapter by MockCarLinkProtocolAdapter() {
            override fun startDiscovery() {
                throw IllegalArgumentException("bad sdk state")
            }
        }
        val (backend, _, _) = backendOf(adapter)
        backend.initialize()
        backend.start()

        val error = backend.state
        assertTrue(error is ProjectionState.Error)
        (error as ProjectionState.Error).let {
            assertEquals("carlink", it.backendId)
            assertNotNull(it.cause)
        }
    }

    // ---- transport-aware resources ----

    @Test
    fun usbCarLinkRequestsUsbAndAudio() {
        val (backend, _, _) = backendOf(MockCarLinkProtocolAdapter())
        val device = ProjectionDevice("d1", "phone", "carlink", ProjectionTransport.USB)
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            backend.requiredResourcesFor(device),
        )
    }

    @Test
    fun wirelessCarLinkRequestsWifiAndAudio() {
        val (backend, _, _) = backendOf(MockCarLinkProtocolAdapter())
        val device = ProjectionDevice("d1", "phone", "carlink", ProjectionTransport.WIFI)
        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO),
            backend.requiredResourcesFor(device),
        )
        val direct = ProjectionDevice("d2", "phone", "carlink", ProjectionTransport.WIFI_DIRECT)
        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO),
            backend.requiredResourcesFor(direct),
        )
    }

    @Test
    fun unknownTransportClaimsNothing() {
        val (backend, _, _) = backendOf(MockCarLinkProtocolAdapter())
        // No resolved device → transport unknown → zero speculative claims.
        assertEquals(
            emptySet<ProjectionResource>(),
            backend.requiredResourcesFor(null),
        )
        assertEquals(
            emptySet<ProjectionResource>(),
            CarLinkCapabilities.resourcesFor(ProjectionTransport.UNKNOWN),
        )
    }

    @Test
    fun microphoneAndBluetoothAreNotClaimedWhileReserved() {
        val (backend, _, _) = backendOf(MockCarLinkProtocolAdapter())
        val device = ProjectionDevice("d1", "phone", "carlink", ProjectionTransport.WIFI)
        val resources = backend.requiredResourcesFor(device)
        assertFalse(resources.contains(ProjectionResource.MICROPHONE))
        assertFalse(resources.contains(ProjectionResource.BLUETOOTH))
    }
}
