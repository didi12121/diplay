// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import android.content.Context
import com.baidu.carlife.protobuf.CarlifeVideoEncoderInfoProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.receiver.display.RemoteDisplayRenderer
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy

/**
 * Phase 9.2a.1 startup/dispatch regressions for the REAL SDK message chain:
 *
 *  1. RAW_BRIDGE_MODE must be enabled BEFORE CarLife.init (the upstream
 *     RemoteDisplayRenderer reads CONFIG_EXTERNAL_VIDEO_SINK in its ctor).
 *  2. In external mode the upstream renderer must send MSG_CMD_VIDEO_ENCODER_START
 *     AND forward MSG_CMD_VIDEO_ENCODER_INIT_DONE to the host bridge
 *     (return false) instead of consuming it.
 *  3. The video bridge must be attached before video negotiation so the first
 *     INIT_DONE can never be missed.
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeVideoStartupTest {

    /** Scripted CarLifeContext capturing postMessage/getConfig calls. */
    private class ScriptedContext(
        private val configs: Map<String, Any>,
    ) {
        val posts = mutableListOf<Pair<Int, Int>>() // channel to serviceType
        var applicationContext: Context = RuntimeEnvironment.getApplication()
        val proxy: CarLifeContext = Proxy.newProxyInstance(
            CarLifeContext::class.java.classLoader,
            arrayOf(CarLifeContext::class.java),
        ) { _, method, args ->
            when (method.name) {
                "postMessage" -> {
                    // postMessage(message) or postMessage(channel, serviceType)
                    val first = args?.get(0)
                    if (first is CarLifeMessage) {
                        posts.add(first.channel to first.serviceType)
                    } else if (args != null && args.size >= 2) {
                        posts.add(args[0] as Int to args[1] as Int)
                    }
                    null
                }
                "getConfig" -> {
                    @Suppress("UNCHECKED_CAST")
                    configs[args?.get(0) as String]
                }
                "getApplicationContext", "getApplication" -> applicationContext
                "getConnectionState", "connectionState" -> 0
                else -> null
            }
        } as CarLifeContext
    }

    private class RecordingVideoSink : ProjectionVideoSink {
        val configs = mutableListOf<ProjectionVideoConfig>()
        val frames = mutableListOf<Int>()
        override fun onVideoConfig(config: ProjectionVideoConfig) {
            configs.add(config)
        }

        override fun onVideoFrame(frame: com.shilapi.xcertplay.projection.ProjectionVideoFrame) {
            frames.add(frame.length)
        }

        override fun onVideoStreamActive(active: Boolean) {}
    }

    private fun initDoneMessage(): CarLifeMessage {
        val message = CarLifeMessage.obtain(Constants.MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT_DONE)
        val info = CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo.newBuilder()
            .setWidth(1920).setHeight(1080).setFrameRate(30).build()
        message.payload(info)
        return message
    }

    /**
     * Replicates the REAL `CarLifeContextImpl.onReceiveMessage` dispatch
     * semantics (transportListeners in registration order, stop on first
     * `true`) with the real RemoteDisplayRenderer and the real
     * CarLifeVideoBridge in the production registration order.
     */
    private fun dispatch(
        listeners: List<com.baidu.carlife.sdk.internal.transport.TransportListener>,
        context: CarLifeContext,
        message: CarLifeMessage,
    ): Boolean {
        for (listener in listeners) {
            if (listener.onReceiveMessage(context, message)) return true
        }
        return true
    }

    // ---- #6 dispatch-order regression ----

    @Test
    fun rawBridgeModeForwardsInitDoneAndRendererSendsStart() {
        val ctx = ScriptedContext(mapOf(Configs.CONFIG_EXTERNAL_VIDEO_SINK to true))
        val renderer = RemoteDisplayRenderer(ctx.proxy) { _, _ -> }
        val sink = RecordingVideoSink()
        val bridge = CarLifeVideoBridge(CarLifeSessionToken(1), CarLifeProjectionVideoAdapter(sink))

        // Production order: upstream renderer first, host bridge after.
        val chain = listOf(renderer, bridge)
        dispatch(chain, ctx.proxy, initDoneMessage())

        // The upstream renderer asked the phone to start streaming...
        assertTrue(ctx.posts.contains(Constants.MSG_CHANNEL_CMD to ServiceTypes.MSG_CMD_VIDEO_ENCODER_START))
        // ...WITHOUT creating its own decoder, and did NOT starve the bridge:
        assertEquals("exactly one config must reach the host bridge", 1, sink.configs.size)
        assertEquals(1920, sink.configs.single().width)
        assertEquals(1080, sink.configs.single().height)
    }

    @Test
    fun nonExternalModePreservesUpstreamConsumption() {
        val ctx = ScriptedContext(mapOf(Configs.CONFIG_EXTERNAL_VIDEO_SINK to false))
        val renderer = RemoteDisplayRenderer(ctx.proxy) { _, _ -> }
        val sink = RecordingVideoSink()
        val bridge = CarLifeVideoBridge(CarLifeSessionToken(2), CarLifeProjectionVideoAdapter(sink))

        val chain = listOf(renderer, bridge)
        dispatch(chain, ctx.proxy, initDoneMessage())

        // Upstream mode keeps its original behavior: the message is consumed
        // by the renderer (no external config) and the host bridge sees none.
        assertEquals(0, sink.configs.size)
    }

    @Test
    fun videoDataInRawModeReachesHostBridge() {
        val ctx = ScriptedContext(mapOf(Configs.CONFIG_EXTERNAL_VIDEO_SINK to true))
        val renderer = RemoteDisplayRenderer(ctx.proxy) { _, _ -> }
        val sink = RecordingVideoSink()
        val bridge = CarLifeVideoBridge(CarLifeSessionToken(3), CarLifeProjectionVideoAdapter(sink))
        val chain = listOf(renderer, bridge)

        val data = CarLifeMessage.obtain(Constants.MSG_CHANNEL_VIDEO, ServiceTypes.MSG_VIDEO_DATA)
        data.payload(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), 0, 7)
        dispatch(chain, ctx.proxy, data)

        assertEquals(1, sink.frames.size)
        assertEquals(7, sink.frames.single())
    }

    // ---- #7 RAW mode config present before init ----

    @Test
    fun realFacadeEnablesExternalVideoSinkBeforeCarLifeInit() {
        val provider = CarLifeV2Provider()
        provider.initialize(
            RuntimeEnvironment.getApplication(),
            CarLifeProviderConfig(activityClass = android.app.Activity::class.java),
        )
        // The real CarLife.init ran; the receiver's config must already carry
        // the RAW_BRIDGE_MODE flag (RemoteDisplayRenderer read it at ctor).
        val receiver = com.baidu.carlife.sdk.receiver.CarLife.receiver()
        assertTrue(receiver.getConfig(Configs.CONFIG_EXTERNAL_VIDEO_SINK, false))
        assertEquals(4, receiver.getConfig(Configs.CONFIG_PROTOCOL_VERSION, -1))
    }

    // ---- #8 bridge attached before Established video negotiation ----

    @Test
    fun videoBridgeIsAttachedAtAoaAttachBeforeEstablished() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val sinkProvider = SinkProvider(sink)
        val backend = CarLifeProjectionBackend(
            provider,
            { _, _ -> AutoCloseable { } },
            60_000,
            sinkProvider,
        )
        backend.connect(null)
        val token = provider.lastToken!!

        // AOA attached: the video bridge must already be registered so an
        // immediate INIT_DONE cannot be missed.
        provider.emit(CarLifeConnectionEvent.Attached(token))
        val relay = provider.videoListeners[token]
        assertTrue("video bridge must be attached at AOA attach", relay != null)

        // Established + IMMEDIATE video handshake: no sleeps, no timing luck.
        provider.emit(CarLifeConnectionEvent.Established(token))
        relay!!.onVideoConfig(token, CarLifeVideoConfig(1920, 1080, 30))
        assertEquals(1, sink.configs.size)
        // Established must NOT create a second sink for the same session.
        assertEquals(1, sinkProvider.acquired)
    }

    @Test
    fun repeatedAttachedDoesNotDuplicateSinks() {
        val provider = ScriptedProvider()
        val sink = RecordingVideoSink()
        val sinkProvider = SinkProvider(sink)
        val backend = CarLifeProjectionBackend(
            provider,
            { _, _ -> AutoCloseable { } },
            60_000,
            sinkProvider,
        )
        backend.connect(null)
        val token = provider.lastToken!!
        provider.emit(CarLifeConnectionEvent.Attached(token))
        provider.emit(CarLifeConnectionEvent.Attached(token))
        provider.emit(CarLifeConnectionEvent.Reattached(token))
        provider.emit(CarLifeConnectionEvent.Established(token))
        assertEquals(1, sinkProvider.acquired)
    }

    // ---- support ----

    private class SinkProvider(private val sink: RecordingVideoSink) : CarLifeVideoSinkProvider {
        var acquired = 0
        override fun acquire(onDecoderDiagnostic: (String) -> Unit): CarLifeVideoSinkSession {
            acquired++
            return CarLifeVideoSinkSession(sink)
        }
    }

    private class ScriptedProvider : CarLifeProvider {
        override val isAvailable = true
        var listener: ((CarLifeConnectionEvent) -> Unit)? = null
        var lastToken: CarLifeSessionToken? = null
        val videoListeners = mutableMapOf<CarLifeSessionToken, CarLifeVideoListener>()
        var sinkProviderAcquired = 0
        private var sinkProvider: SinkProvider? = null

        fun bindSinkProvider(p: SinkProvider) {
            sinkProvider = p
        }

        override fun initialize(context: Context, config: CarLifeProviderConfig) {}
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
        }

        override fun dispose() {}
        override fun diagnostics(): CarLifeProviderDiagnostics = CarLifeProviderDiagnostics()

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)
    }
}
