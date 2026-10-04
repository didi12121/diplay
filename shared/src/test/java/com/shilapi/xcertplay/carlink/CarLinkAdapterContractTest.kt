package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionSampleFormat
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import com.shilapi.xcertplay.projection.media.ProjectionMediaSinkAdapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract fixture for a future OfficialCarLinkSdkAdapter: any adapter that
 * drives the listener in this canonical order works through the whole stack —
 * no MediaCodec needed, recording sinks prove the media reached the renderer
 * inputs intact.
 *
 * ```
 * initialize → discover → connect → onSessionStarted →
 * onVideoConfig → onVideoFrame → onAudioStarted → onAudioFrame → … →
 * onSessionEnded
 * ```
 *
 * The adapter is NOT responsible for MediaCodec/AudioTrack/Activity/Surface/
 * resource arbitration — only for translating SDK callbacks into these typed
 * events.
 */
class CarLinkAdapterContractTest {

    /**
     * Minimal compliant adapter: SDK callbacks come in through the public
     * `onXxx` methods a real SDK would call; everything else is a no-op like a
     * real transport wrapper. It binds the controller-minted token to its
     * "SDK session" and echoes it back — the real-adapter contract.
     */
    private class ContractAdapter : CarLinkProtocolAdapter {
        override val providerName = "contract-fake"
        override val isAvailable = true
        private var listener: CarLinkProtocolListener? = null

        /** Token received at connect; echoed on every callback. */
        var boundToken: CarLinkSessionToken? = null
            private set

        override fun setListener(listener: CarLinkProtocolListener?) {
            this.listener = listener
        }

        // SDK entry points a real adapter would wire to the ICCOA stack.
        fun sdkDeviceFound(device: CarLinkDevice) = listener!!.onDeviceFound(device)
        fun sdkSessionStarted(device: CarLinkDevice, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onSessionStarted(session, device)

        fun sdkVideoConfig(config: ProjectionVideoConfig, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onVideoConfig(session, config)

        fun sdkVideoFrame(frame: ProjectionVideoFrame, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onVideoFrame(session, frame)

        fun sdkAudioStarted(format: CarLinkAudioFormat, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onAudioStarted(session, format)

        fun sdkAudioFrame(frame: CarLinkAudioFrame, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onAudioFrame(session, frame)

        fun sdkAudioStopped(streamId: Int, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onAudioStopped(session, streamId)

        fun sdkMetadata(metadata: ProjectionMetadata, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onMetadata(session, metadata)

        fun sdkSessionEnded(reason: String, session: CarLinkSessionToken = boundToken!!) =
            listener!!.onSessionEnded(session, reason)

        fun sdkError(
            code: String,
            message: String,
            severity: CarLinkErrorSeverity,
            cause: Throwable? = null,
            session: CarLinkSessionToken? = boundToken,
        ) = listener!!.onError(session, code, message, severity, cause)

        // Connection side: remember what the host asked for.
        val connectRequests = mutableListOf<CarLinkDevice>()
        val touchSent = mutableListOf<ProjectionTouchEvent>()
        val keySent = mutableListOf<ProjectionKeyEvent>()

        override fun initialize() {}
        override fun dispose() {}
        override fun startDiscovery() {}
        override fun stopDiscovery() {}
        override fun connect(device: CarLinkDevice, session: CarLinkSessionToken) {
            connectRequests.add(device)
            boundToken = session
        }

        override fun disconnect() {}
        override fun sendTouch(event: ProjectionTouchEvent) {
            touchSent.add(event)
        }

        override fun sendKey(event: ProjectionKeyEvent) {
            keySent.add(event)
        }
    }

    /** Recording sinks at the renderer boundary. */
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
        val started = mutableListOf<Pair<Int, com.shilapi.xcertplay.projection.ProjectionAudioFormat>>()
        val frames = mutableListOf<Triple<Int, Long, ByteArray>>()
        val stopped = mutableListOf<Int>()
        override fun onAudioStarted(
            id: com.shilapi.xcertplay.projection.ProjectionAudioStreamId,
            format: com.shilapi.xcertplay.projection.ProjectionAudioFormat,
        ) {
            started.add(id.stream to format)
        }

        override fun onAudioFrame(
            id: com.shilapi.xcertplay.projection.ProjectionAudioStreamId,
            presentationTimeUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int,
        ) {
            frames.add(
                Triple(
                    id.stream,
                    presentationTimeUs,
                    payload.copyOfRange(offset, offset + length),
                ),
            )
        }

        override fun onAudioStopped(id: com.shilapi.xcertplay.projection.ProjectionAudioStreamId) {
            stopped.add(id.stream)
        }
    }

    @Test
    fun canonicalAdapterSequenceWorksThroughTheWholeStack() {
        val adapter = ContractAdapter()
        val video = RecordingVideo()
        val audio = RecordingAudio()
        val controller = CarLinkController(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(video, audio),
        )

        // ---- Canonical order from the adapter checklist ----
        controller.initialize()
        controller.startDiscovery()
        val phone = CarLinkDevice(
            deviceId = "iccoa-1",
            name = "Xiaomi 14",
            vendorHint = "xiaomi",
            transport = ProjectionTransport.USB,
        )
        adapter.sdkDeviceFound(phone)

        controller.connect(null)
        assertEquals(listOf(phone), adapter.connectRequests)
        // Token contract: the controller minted a token, the adapter received
        // it at connect, and every accepted callback echoes it back.
        val session = adapter.boundToken
        assertTrue("controller must mint a session token", session != null)

        adapter.sdkSessionStarted(phone)

        // Video: config then frames, real size/PTS/keyframe/window.
        val config = ProjectionVideoConfig(
            codec = ProjectionVideoCodec.H264,
            codecData = byteArrayOf(1, 2, 3),
            width = 1920,
            height = 1080,
        )
        adapter.sdkVideoConfig(config)
        val payload = byteArrayOf(0, 1, 2, 3, 4, 5, 6)
        adapter.sdkVideoFrame(
            ProjectionVideoFrame(
                codec = ProjectionVideoCodec.H264,
                width = 1920,
                height = 1080,
                presentationTimeUs = 33_333,
                keyFrame = true,
                payload = payload,
                offset = 1,
                length = 5,
            ),
        )

        // Audio: started → frames → stopped with explicit PCM order.
        adapter.sdkAudioStarted(
            CarLinkAudioFormat(
                streamId = 7,
                role = ProjectionAudioChannel.MEDIA,
                codec = ProjectionAudioCodec.LPCM,
                sampleRate = 48_000,
                channels = 2,
                sampleFormat = ProjectionSampleFormat.PCM_S16_BE,
            ),
        )
        val pcm = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        adapter.sdkAudioFrame(CarLinkAudioFrame(7, 40_000, pcm))
        adapter.sdkAudioStopped(7)

        adapter.sdkMetadata(ProjectionMetadata(title = "Song"))
        adapter.sdkSessionEnded("peer detach")

        // ---- Everything reached the renderer inputs intact ----
        assertEquals(listOf(config), video.configs)
        val frame = video.frames.single()
        assertEquals(33_333L, frame.presentationTimeUs)
        assertTrue(frame.keyFrame)
        assertEquals(5, frame.length)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), frame.payload.copyOfRange(frame.offset, frame.offset + frame.length))

        val (stream, format) = audio.started.single()
        assertEquals(7, stream)
        assertEquals(ProjectionSampleFormat.PCM_S16_BE, format.sampleFormat)
        assertEquals(40_000L, audio.frames.single().second)
        assertArrayEquals(pcm, audio.frames.single().third)
        assertEquals(listOf(7), audio.stopped)

        // Session ended cleanly.
        assertTrue(!controller.sessionActive)
    }

    @Test
    fun contractAdapterFeedsTheBackendStateMachine() {
        val adapter = ContractAdapter()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        backend.initialize()
        backend.start()
        adapter.sdkDeviceFound(
            CarLinkDevice("iccoa-2", "vivo X100", "vivo", ProjectionTransport.WIFI),
        )
        backend.connect(null)
        adapter.sdkSessionStarted(
            CarLinkDevice("iccoa-2", "vivo X100", "vivo", ProjectionTransport.WIFI),
        )
        assertEquals(ProjectionState.Connected, backend.state)

        // Input flows down to the adapter.
        val touch = ProjectionTouchEvent(
            action = com.shilapi.xcertplay.projection.ProjectionTouchAction.DOWN,
            pointers = listOf(
                com.shilapi.xcertplay.projection.ProjectionTouchPointer(0, 10f, 20f, down = true),
            ),
            geometry = com.shilapi.xcertplay.projection.ProjectionDisplayGeometry(
                1920,
                1080,
                com.shilapi.xcertplay.projection.ProjectionRect(0f, 0f, 1920f, 1080f),
            ),
        )
        backend.onTouchEvent(touch)
        assertEquals(1, adapter.touchSent.size)

        adapter.sdkSessionEnded("peer detach")
        assertEquals(ProjectionState.Ready, backend.state)
        assertTrue(!backend.isSessionActive)
    }

    @Test
    fun recoverableAndFatalMappingFromTheChecklist() {
        val adapter = ContractAdapter()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        backend.initialize()
        backend.start()
        adapter.sdkDeviceFound(CarLinkDevice("iccoa-3", "OPPO Find", "oppo", ProjectionTransport.WIFI))
        backend.connect(null)
        adapter.sdkSessionStarted(CarLinkDevice("iccoa-3", "OPPO Find", "oppo", ProjectionTransport.WIFI))

        // Recoverable: session keeps running.
        adapter.sdkError("GLITCH", "one frame dropped", CarLinkErrorSeverity.RECOVERABLE)
        assertTrue(backend.isSessionActive)

        // Fatal: session ends, error latched.
        adapter.sdkError("LINK_LOST", "usb unplugged", CarLinkErrorSeverity.FATAL)
        assertTrue(!backend.isSessionActive)
        assertTrue(backend.state is ProjectionState.Error)
    }

    @Test
    fun wrongOrStaleTokenIsIgnored() {
        val adapter = ContractAdapter()
        val video = RecordingVideo()
        val audio = RecordingAudio()
        val controller = CarLinkController(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(video, audio),
        )
        controller.initialize()
        controller.startDiscovery()
        val phone = CarLinkDevice("iccoa-4", "Xiaomi 15", "xiaomi", ProjectionTransport.USB)
        adapter.sdkDeviceFound(phone)
        controller.connect(null)
        adapter.sdkSessionStarted(phone)
        val goodToken = adapter.boundToken!!
        assertTrue(controller.sessionActive)

        // A fabricated/wrong token is refused for every callback kind.
        val wrong = CarLinkSessionToken(goodToken.value + 999)
        adapter.sdkVideoFrame(
            ProjectionVideoFrame(ProjectionVideoCodec.H264, 10, 10, 1, true, byteArrayOf(1)),
            session = wrong,
        )
        adapter.sdkSessionEnded("fake end", session = wrong)
        adapter.sdkError("BOGUS", "fake fatal", CarLinkErrorSeverity.FATAL, session = wrong)

        // Session unaffected: still active, no frames, no teardown.
        assertTrue(controller.sessionActive)
        assertEquals(0, video.frames.size)

        // The real token still works afterwards.
        adapter.sdkSessionEnded("real end", session = goodToken)
        assertTrue(!controller.sessionActive)
    }
}
