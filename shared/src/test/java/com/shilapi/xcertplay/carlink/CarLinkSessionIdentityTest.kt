package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionAudioStreamId
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Out-of-order interleavings (tests C–G of the session-identity spec): once
 * session A is dead, EVERY late callback of A — video frames, audio streams,
 * metadata, session starts, fatal errors — is ignored by identity. Only
 * callbacks carrying session B's token may reach B's decoders/sinks/state.
 */
class CarLinkSessionIdentityTest {

    private class RecordingVideo : ProjectionVideoSink {
        val configs = mutableListOf<ProjectionVideoConfig>()
        val frames = mutableListOf<ProjectionVideoFrame>()
        override fun onVideoConfig(config: ProjectionVideoConfig) {
            configs.add(config)
        }

        override fun onVideoFrame(frame: ProjectionVideoFrame) {
            frames.add(frame)
        }

        override fun onVideoStreamActive(active: Boolean) {}
    }

    private class RecordingAudio : ProjectionAudioSink {
        val started = mutableListOf<CarLinkAudioFormat>()
        val frames = mutableListOf<CarLinkAudioFrame>()
        val stopped = mutableListOf<Int>()
        override fun onAudioStarted(id: ProjectionAudioStreamId, format: com.shilapi.xcertplay.projection.ProjectionAudioFormat) {
            started.add(
                CarLinkAudioFormat(
                    streamId = id.stream,
                    role = format.channel,
                    codec = when (format.codec) {
                        com.shilapi.xcertplay.projection.ProjectionAudioCodec.AAC_LC -> ProjectionAudioCodec.AAC_LC
                        com.shilapi.xcertplay.projection.ProjectionAudioCodec.OPUS -> ProjectionAudioCodec.OPUS
                        com.shilapi.xcertplay.projection.ProjectionAudioCodec.LPCM -> ProjectionAudioCodec.LPCM
                    },
                    sampleRate = format.sampleRate,
                    channels = format.channels,
                ),
            )
        }

        override fun onAudioFrame(
            id: ProjectionAudioStreamId,
            presentationTimeUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int,
        ) {
            frames.add(CarLinkAudioFrame(id.stream, presentationTimeUs, payload, offset, length))
        }

        override fun onAudioStopped(id: ProjectionAudioStreamId) {
            stopped.add(id.stream)
        }
    }

    /** A dead A and a live B, driven through the real controller. */
    private class Scenario {
        val video = RecordingVideo()
        val audio = RecordingAudio()
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val metadata = mutableListOf<ProjectionMetadata>()
        val manager = ProjectionManager()
        val backend: CarLinkProjectionBackend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(video, audio),
        )

        /** Token of session A (dead). */
        val tokenA: CarLinkSessionToken get() = adapter.connectTokens[0]

        /** Token of session B (live). */
        val tokenB: CarLinkSessionToken get() = adapter.connectTokens[1]

        init {
            manager.register(backend)
            backend.initialize()
            backend.start()
            manager.connect(null) // A
            adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)
            backend.initialize()
            backend.start()
            manager.connect(null) // B
        }

        /** Mirrors what the host observes: metadata publishes. */
        fun observeMetadata() {
            backend.carLink.onMetadata { metadata.add(it) }
        }
    }

    // ---- Test C ----

    @Test
    fun testLateVideoFrameOfDeadSessionNeverReachesNewDecoder() {
        val s = Scenario()
        assertEquals(ProjectionState.Connected, s.backend.state)

        // Session B streams its own frame — it must arrive.
        val frameB = ProjectionVideoFrame(
            codec = ProjectionVideoCodec.H264,
            width = 100,
            height = 100,
            presentationTimeUs = 2,
            keyFrame = true,
            payload = byteArrayOf(2),
        )
        s.adapter.simulateVideoFrame(frameB, session = s.tokenB)
        assertEquals(1, s.video.frames.size)

        // Late frames of dead session A must never reach B's decoder.
        val frameA = ProjectionVideoFrame(
            codec = ProjectionVideoCodec.H265,
            width = 1920,
            height = 1080,
            presentationTimeUs = 1,
            keyFrame = true,
            payload = byteArrayOf(1),
        )
        s.adapter.simulateVideoFrame(frameA, session = s.tokenA)
        s.adapter.simulateVideoConfig(
            ProjectionVideoConfig(ProjectionVideoCodec.H265, byteArrayOf(9), 1920, 1080),
            session = s.tokenA,
        )

        assertEquals("B video sink must receive 0 frames from A", 1, s.video.frames.size)
        assertEquals("B video sink must receive 0 configs from A", 0, s.video.configs.size)
    }

    // ---- Test D ----

    @Test
    fun testLateAudioOfDeadSessionNeverTouchesNewAudioSink() {
        val s = Scenario()

        // Late audio lifecycle of dead session A.
        s.adapter.simulateAudioStarted(
            CarLinkAudioFormat(11, ProjectionAudioChannel.MEDIA, ProjectionAudioCodec.AAC_LC, 48_000, 2),
            session = s.tokenA,
        )
        s.adapter.simulateAudioFrame(CarLinkAudioFrame(11, 1, byteArrayOf(1)), session = s.tokenA)
        s.adapter.simulateAudioStopped(11, session = s.tokenA)

        assertEquals("B audio sink must see no stream from A", 0, s.audio.started.size)
        assertEquals(0, s.audio.frames.size)
        assertEquals(0, s.audio.stopped.size)

        // B's own audio still works.
        s.adapter.simulateAudioStarted(
            CarLinkAudioFormat(22, ProjectionAudioChannel.NAVIGATION, ProjectionAudioCodec.OPUS, 48_000, 1),
            session = s.tokenB,
        )
        assertEquals(1, s.audio.started.size)
    }

    // ---- Test E ----

    @Test
    fun testLateMetadataOfDeadSessionDoesNotUpdateNewSession() {
        val s = Scenario()
        s.observeMetadata()

        s.adapter.simulateMetadata(
            ProjectionMetadata(title = "stale A track"),
            session = s.tokenA,
        )
        assertEquals("A's metadata must not update B", 0, s.metadata.size)

        s.adapter.simulateMetadata(
            ProjectionMetadata(title = "B track"),
            session = s.tokenB,
        )
        assertEquals(1, s.metadata.size)
        assertEquals("B track", s.metadata.single().title)
    }

    // ---- Test F ----

    @Test
    fun testLateSessionStartedOfDeadAttemptIsIgnored() {
        // autoConnect=false so both attempts stay pending until simulated.
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false, autoConnect = false)
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(RecordingVideo(), RecordingAudio()),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        val device = backend.discoveredDevices().first()
        backend.connect(device) // attempt A
        val tokenA = adapter.connectTokens[0]

        // A fails before ever starting.
        adapter.simulateError("TIMEOUT", "A timed out", CarLinkErrorSeverity.FATAL, session = tokenA)

        backend.initialize()
        backend.start()
        backend.connect(device) // attempt B
        val tokenB = adapter.connectTokens[1]

        // Late SessionStarted of dead attempt A.
        adapter.simulateSessionStarted(
            CarLinkDevice("mock-xiaomi", "Xiaomi", "xiaomi",
                com.shilapi.xcertplay.projection.ProjectionTransport.USB),
            session = tokenA,
        )

        // Must NOT publish Connected(A) or mark a session active.
        assertTrue(
            "stale session start must not activate a session",
            !backend.isSessionActive && backend.state !is ProjectionState.Connected,
        )

        // B's real start is accepted.
        adapter.simulateSessionStarted(
            CarLinkDevice("mock-xiaomi", "Xiaomi", "xiaomi",
                com.shilapi.xcertplay.projection.ProjectionTransport.USB),
            session = tokenB,
        )
        assertTrue(backend.isSessionActive)
        assertEquals(ProjectionState.Connected, backend.state)
    }

    // ---- Test G ----

    @Test
    fun testLateFatalErrorOfDeadSessionDoesNotTearDownNewSession() {
        var closes = 0
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(
                RecordingVideo(),
                RecordingAudio(),
                onClose = { closes++ },
            ),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        manager.connect(null) // A
        val tokenA = adapter.connectTokens[0]
        adapter.simulateError("LINK_LOST", "A fatal", CarLinkErrorSeverity.FATAL, session = tokenA)

        backend.initialize()
        backend.start()
        manager.connect(null) // B
        val closesAfterB = closes

        // Late FATAL error of dead session A (and a second one).
        adapter.simulateError("LINK_LOST", "late A fatal", CarLinkErrorSeverity.FATAL, session = tokenA)
        adapter.simulateError("AUTH", "late A auth fatal", CarLinkErrorSeverity.FATAL, session = tokenA)

        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertEquals("B's media must stay open", closesAfterB, closes)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isNotEmpty())
    }

    @Test
    fun testProviderScopedErrorStillApplies() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(RecordingVideo(), RecordingAudio()),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        manager.connect(null)

        // Provider-scoped (session == null) FATAL applies globally: no token
        // to be stale — it tears the current session down.
        adapter.simulateError(
            "SDK_ABORT",
            "sdk died",
            CarLinkErrorSeverity.FATAL,
            session = null,
        )
        assertTrue(!backend.isSessionActive)
        assertTrue(backend.state is ProjectionState.Error)
    }
}
