// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-scoped VIDEO fencing and media ownership (Phase 9.2a):
 * a late video callback of a dead session can never reach the live decoder,
 * and teardown of session A never closes session B's media path.
 */
class CarLifeVideoFencingTest {

    private class RecordingVideoSink : ProjectionVideoSink {
        val configs = mutableListOf<ProjectionVideoConfigView>()
        val frames = mutableListOf<FrameView>()
        var active = false
        var closed = false

        data class ProjectionConfigView(val w: Int, val h: Int)
        data class ProjectionVideoConfigView(val w: Int, val h: Int)
        data class FrameView(val pts: Long, val key: Boolean, val bytes: List<Byte>)

        override fun onVideoConfig(config: com.shilapi.xcertplay.projection.ProjectionVideoConfig) {
            configs.add(ProjectionVideoConfigView(config.width, config.height))
        }

        override fun onVideoFrame(frame: com.shilapi.xcertplay.projection.ProjectionVideoFrame) {
            frames.add(
                FrameView(
                    frame.presentationTimeUs,
                    frame.keyFrame,
                    frame.payload.copyOfRange(frame.offset, frame.offset + frame.length).toList(),
                ),
            )
        }

        override fun onVideoStreamActive(active: Boolean) {
            this.active = active
        }
    }

    private class ScriptedProvider : CarLifeProvider {
        override val isAvailable = true
        var listener: ((CarLifeConnectionEvent) -> Unit)? = null
        var lastToken: CarLifeSessionToken? = null
        val videoListeners = mutableMapOf<CarLifeSessionToken, CarLifeVideoListener>()
        val detached = mutableListOf<CarLifeSessionToken>()

        override fun initialize(context: android.content.Context, config: CarLifeProviderConfig) {}
        override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
            lastToken = token
            this.listener = listener
        }

        override fun stopConnection(token: CarLifeSessionToken) {}
        override fun attachVideo(token: CarLifeSessionToken, video: CarLifeVideoListener) {
            videoListeners[token] = video
        }

        override fun detachVideo(token: CarLifeSessionToken) {
            videoListeners.remove(token)
            detached.add(token)
        }

        override fun dispose() {}
        override fun diagnostics(): CarLifeProviderDiagnostics = CarLifeProviderDiagnostics()

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)
    }

    private class SinkProvider(private val sink: RecordingVideoSink) : CarLifeVideoSinkProvider {
        var acquired = 0
        var closed = 0
        override fun acquire(onDecoderDiagnostic: (String) -> Unit): CarLifeVideoSinkSession {
            acquired++
            return CarLifeVideoSinkSession(sink) { closed++ }
        }
    }

    private fun frame(bytes: List<Byte>, pts: Long = 1L) = CarLifeVideoFrame(
        payload = bytes.toByteArray(),
        offset = 0,
        length = bytes.size,
        presentationTimeUs = pts,
        keyFrame = bytes.size > 4 && bytes[4].toInt() == 0x65,
    )

    // ---- Task 25: fencing tests A-D ----

    @Test
    fun testALateVideoOfAReachesZeroFramesOfB() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val backend = CarLifeProjectionBackend(
            provider,
            { _, _ -> AutoCloseable { } },
            60_000,
            SinkProvider(sink),
        )
        // Session A.
        backend.connect(null)
        val tokenA = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        val relayA = provider.videoListeners.getValue(tokenA)
        relayA.onVideoFrame(tokenA, frame(listOf(1, 2, 3)))
        assertEquals(1, sink.frames.size)

        // A ends; B connects.
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))
        backend.connect(null)
        val tokenB = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenB))

        // LATE video frame of A: dropped by identity, zero frames reach B.
        relayA.onVideoFrame(tokenA, frame(listOf(9, 9, 9)))
        assertEquals(1, sink.frames.size) // still only A's original frame

        // B's real frames flow.
        provider.videoListeners.getValue(tokenB).onVideoFrame(tokenB, frame(listOf(4, 5, 6)))
        assertEquals(2, sink.frames.size)
    }

    @Test
    fun testBLateConfigOfALeavesBConfigUnchanged() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val backend = CarLifeProjectionBackend(provider, { _, _ -> AutoCloseable { } }, 60_000, SinkProvider(sink))
        backend.connect(null)
        val tokenA = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        val relayA = provider.videoListeners.getValue(tokenA)
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        backend.connect(null)
        val tokenB = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenB))
        provider.videoListeners.getValue(tokenB).onVideoConfig(tokenB, CarLifeVideoConfig(1280, 720, 30))

        // Late config of A must not rewrite B's decoder config.
        relayA.onVideoConfig(tokenA, CarLifeVideoConfig(640, 360, 15))
        assertEquals(1, sink.configs.size)
        assertEquals(1280, sink.configs.single().w)
    }

    @Test
    fun testCLateVideoStopOfALeavesBDecoderActive() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val sinkProvider = SinkProvider(sink)
        val backend = CarLifeProjectionBackend(provider, { _, _ -> AutoCloseable { } }, 60_000, sinkProvider)
        backend.connect(null)
        val tokenA = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        val relayA = provider.videoListeners.getValue(tokenA)
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        backend.connect(null)
        val tokenB = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenB))
        val closesBefore = sinkProvider.closed

        // Late video stop of A must not close B's media path.
        relayA.onVideoStopped(tokenA)
        assertEquals(closesBefore, sinkProvider.closed)
        assertTrue(backend.isSessionActive)
    }

    @Test
    fun testDBRealFramesFlowNormally() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val backend = CarLifeProjectionBackend(provider, { _, _ -> AutoCloseable { } }, 60_000, SinkProvider(sink))
        backend.connect(null)
        val tokenA = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))
        backend.connect(null)
        val tokenB = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenB))

        provider.videoListeners.getValue(tokenB).onVideoConfig(tokenB, CarLifeVideoConfig(1920, 1080, 30))
        provider.videoListeners.getValue(tokenB).onVideoFrame(tokenB, frame(listOf(0, 0, 0, 1, 0x65, 7), 33_333))
        assertEquals(1, sink.configs.size)
        assertEquals(1, sink.frames.size)
        assertEquals(33_333L, sink.frames.single().pts)
        assertTrue(sink.frames.single().key)
        assertEquals(1920, sink.configs.single().w)
    }

    // ---- Task 26: lifecycle (USB detach during video, reconnect) ----

    @Test
    fun usbDetachDuringVideoClosesDecoderAndReleasesResources() {
        val manager = ProjectionManager()
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val sinkProvider = SinkProvider(sink)
        val backend = CarLifeProjectionBackend(provider, { _, _ -> AutoCloseable { } }, 60_000, sinkProvider)
        manager.register(backend)
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        val token = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(token))
        provider.videoListeners.getValue(token).onVideoFrame(token, frame(listOf(1)))
        assertEquals(1, sinkProvider.acquired)

        // Spontaneous USB unplug mid-video.
        provider.emit(CarLifeConnectionEvent.Detached(token))

        assertFalse(backend.isSessionActive)
        assertEquals("decoder must close", 1, sinkProvider.closed)
        assertEquals("USB/AUDIO must be released", emptySet<Any>(), manager.resourcesHeldBy(CarLifeProjectionBackend.ID).toSet())
    }

    @Test
    fun reconnectStartsFreshSessionWithoutStaleFramesOrDuplicateListeners() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val sinkProvider = SinkProvider(sink)
        val backend = CarLifeProjectionBackend(provider, { _, _ -> AutoCloseable { } }, 60_000, sinkProvider)
        backend.connect(null)
        val tokenA = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        backend.connect(null)
        val tokenB = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenB))

        // Fresh video path per session, old listener fenced out.
        assertEquals(2, sinkProvider.acquired)
        assertEquals(1, sinkProvider.closed) // A's sink closed, B's open
        assertEquals(setOf(tokenB), provider.videoListeners.keys)

        // connect -> video -> disconnect -> connect -> video works.
        provider.videoListeners.getValue(tokenB).onVideoFrame(tokenB, frame(listOf(2)))
        assertEquals(1, sink.frames.size)
        backend.disconnect()
        assertEquals(2, sinkProvider.closed)
        assertTrue(provider.videoListeners.isEmpty())

        backend.connect(null)
        val tokenC = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Established(tokenC))
        provider.videoListeners.getValue(tokenC).onVideoFrame(tokenC, frame(listOf(3)))
        assertEquals(2, sink.frames.size)
    }
}
