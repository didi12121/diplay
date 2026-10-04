// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import com.baidu.carlife.protobuf.CarlifeVideoEncoderInfoProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.transport.TransportListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLife video mapping + buffer ownership (Phase 9.2a):
 * encoder info -> exactly one ProjectionVideoConfig; raw frames -> exactly one
 * ProjectionVideoFrame with exact payload window; upstream pooled message
 * recycling must never corrupt delivered frames.
 */
class CarLifeVideoMappingTest {

    private class RecordingSink : ProjectionVideoSink {
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

    private class RecordingVideoListener : CarLifeVideoListener {
        val configs = mutableListOf<CarLifeVideoConfig>()
        val frames = mutableListOf<CarLifeVideoFrame>()
        val stops = mutableListOf<CarLifeSessionToken>()
        override fun onVideoConfig(session: CarLifeSessionToken, config: CarLifeVideoConfig) {
            configs.add(config)
        }

        override fun onVideoFrame(session: CarLifeSessionToken, frame: CarLifeVideoFrame) {
            frames.add(frame)
        }

        override fun onVideoStopped(session: CarLifeSessionToken) {
            stops.add(session)
        }
    }

    // ---- Config mapping ----

    @Test
    fun encoderInfoProducesExactlyOneConfigWithRealSize() {
        val sink = RecordingSink()
        val adapter = CarLifeProjectionVideoAdapter(sink)
        val token = CarLifeSessionToken(1)
        adapter.onVideoConfig(token, CarLifeVideoConfig(width = 1920, height = 1080, frameRate = 30))

        assertEquals(1, sink.configs.size)
        val config = sink.configs.single()
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(com.shilapi.xcertplay.projection.ProjectionVideoCodec.H264, config.codec)
        // SPS/PPS are in-band in CarLife V2 - no avcC is fabricated.
        assertEquals(0, config.codecData.size)
    }

    @Test
    fun sameConfigRepeatedMapsToEquivalentDecoderSetup() {
        val sink = RecordingSink()
        val adapter = CarLifeProjectionVideoAdapter(sink)
        val token = CarLifeSessionToken(1)
        adapter.onVideoConfig(token, CarLifeVideoConfig(1280, 720, 30))
        adapter.onVideoConfig(token, CarLifeVideoConfig(1280, 720, 30))
        // Two identical configs map to identical decoder setups; the shared
        // decoder keeps its MediaCodec (sameDecoderSetupAs semantics tested
        // in shared's VideoConfigEqualityTest).
        assertEquals(2, sink.configs.size)
        val a = sink.configs[0]
        val b = sink.configs[1]
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertEquals(a.codec, b.codec)
        assertEquals(0, a.codecData.size)
    }

    @Test
    fun widthOrHeightChangeProducesDifferentDecoderSetup() {
        val sink = RecordingSink()
        val adapter = CarLifeProjectionVideoAdapter(sink)
        val token = CarLifeSessionToken(1)
        adapter.onVideoConfig(token, CarLifeVideoConfig(1280, 720, 30))
        adapter.onVideoConfig(token, CarLifeVideoConfig(1920, 1080, 30))
        // Different coded sizes reach the shared pipeline as distinct configs
        // (the shared decoder reconfigures - VideoConfigEqualityTest).
        assertEquals(1280, sink.configs[0].width)
        assertEquals(1920, sink.configs[1].width)
        assertTrue(sink.configs[0].width != sink.configs[1].width || sink.configs[0].height != sink.configs[1].height)
    }
    // ---- Frame mapping ----

    @Test
    fun rawFrameProducesExactlyOneFrameWithExactWindow() {
        val sink = RecordingSink()
        val adapter = CarLifeProjectionVideoAdapter(sink)
        val token = CarLifeSessionToken(1)
        adapter.onVideoConfig(token, CarLifeVideoConfig(1280, 720, 30))

        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3) // Annex-B IDR
        adapter.onVideoFrame(
            token,
            CarLifeVideoFrame(payload, 0, payload.size, 33_333L, keyFrame = true),
        )

        assertEquals(1, sink.frames.size)
        val frame = sink.frames.single()
        assertEquals(payload.size, frame.length)
        assertEquals(0, frame.offset)
        assertEquals(33_333L, frame.presentationTimeUs)
        assertTrue(frame.keyFrame)
        assertArrayEquals(payload, frame.payload.copyOfRange(frame.offset, frame.offset + frame.length))
    }

    @Test
    fun payloadWindowIsRespectedNotWholeBuffer() {
        val sink = RecordingSink()
        val adapter = CarLifeProjectionVideoAdapter(sink)
        val token = CarLifeSessionToken(1)
        adapter.onVideoConfig(token, CarLifeVideoConfig(1280, 720, 30))
        val payload = byteArrayOf(9, 9, 0, 0, 1, 0x41, 8, 9)
        adapter.onVideoFrame(
            token,
            CarLifeVideoFrame(payload, 2, 4, 100L, keyFrame = false),
        )
        val frame = sink.frames.single()
        assertEquals(2, frame.offset)
        assertEquals(4, frame.length)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x41), frame.payload.copyOfRange(2, 6))
    }

    // ---- Buffer ownership ----

    @Test
    fun upstreamMessageRecyclingNeverCorruptsDeliveredFrames() {
        val listener = RecordingVideoListener()
        val bridge = CarLifeVideoBridge(CarLifeSessionToken(1), listener) { 42L }
        val message = CarLifeMessage.obtain(com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO, ServiceTypes.MSG_VIDEO_DATA)
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 10, 11, 12)
        message.payload(payload, 0, payload.size)

        bridge.onReceiveMessage(fakeContext(), message)
        assertEquals(1, listener.frames.size)
        val delivered = listener.frames.single()

        // Upstream recycles the pooled message right after dispatch...
        message.recycle()
        // ...and the pool may hand the same buffer out again with new data.
        val reused = CarLifeMessage.obtain(com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO, ServiceTypes.MSG_VIDEO_DATA)
        reused.payload(byteArrayOf(7, 7, 7, 7, 7, 7, 7, 7), 0, 8)

        // The delivered frame keeps its own copy of the original bytes.
        assertArrayEquals(payload, delivered.payload)
        reused.recycle()
    }

    @Test
    fun bridgeParsesKeyframesFromAnnexBNotSizeHeuristics() {
        val listener = RecordingVideoListener()
        val bridge = CarLifeVideoBridge(CarLifeSessionToken(1), listener) { 1L }
        // IDR access unit (start code + NAL 5) -> keyframe.
        bridge.onReceiveMessage(fakeContext(), videoMessage(byteArrayOf(0, 0, 0, 1, 0x65, 1)))
        // Non-IDR slice (NAL 1) -> not a keyframe, regardless of size.
        bridge.onReceiveMessage(fakeContext(), videoMessage(byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 3, 4, 5)))
        assertTrue(listener.frames[0].keyFrame)
        assertFalse(listener.frames[1].keyFrame)
    }

    @Test
    fun bridgeEmitsConfigFromEncoderInfoAndStopsOnPause() {
        val listener = RecordingVideoListener()
        val bridge = CarLifeVideoBridge(CarLifeSessionToken(7), listener) { 1L }
        val info = CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo.newBuilder()
            .setWidth(1600).setHeight(900).setFrameRate(30).build()
        val message = CarLifeMessage.obtain(com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT_DONE)
        message.payload(info)
        bridge.onReceiveMessage(fakeContext(), message)

        assertEquals(1, listener.configs.size)
        assertEquals(1600, listener.configs.single().width)
        assertEquals(900, listener.configs.single().height)

        val pause = CarLifeMessage.obtain(com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_VIDEO_ENCODER_PAUSE)
        bridge.onReceiveMessage(fakeContext(), pause)
        assertEquals(listOf(CarLifeSessionToken(7)), listener.stops)
        pause.recycle()
        message.recycle()
    }

    private fun videoMessage(payload: ByteArray): CarLifeMessage {
        val message = CarLifeMessage.obtain(com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO, ServiceTypes.MSG_VIDEO_DATA)
        message.payload(payload, 0, payload.size)
        return message
    }

    private fun fakeContext(): CarLifeContext =
        java.lang.reflect.Proxy.newProxyInstance(
            CarLifeContext::class.java.classLoader,
            arrayOf(CarLifeContext::class.java),
        ) { _, _, _ -> null } as CarLifeContext
}
