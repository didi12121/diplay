package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame

/**
 * Deterministic in-memory [CarLinkProtocolAdapter] for **unit tests and
 * developer test harnesses only** — never for production: `ProjectionHost`
 * defaults to [UnavailableCarLinkProtocolAdapter], and this adapter must be
 * injected explicitly.
 *
 * It speaks NO protocol: no sockets, no ports, no handshakes. It only simulates
 * the *shape* of a protocol provider (discovery, session, typed media
 * lifecycle, teardown) so the whole projection pipeline above the adapter can be
 * exercised and so an eventual official SDK adapter drops in behind the same
 * interface.
 */
class MockCarLinkProtocolAdapter(
    /** Devices discovery reports, in order. */
    private val devices: List<CarLinkDevice> = defaultDevices(),
    /** When true, connect() completes synchronously with a fake session. */
    private val autoConnect: Boolean = true,
    /** When true, connect() also streams one fake video config/frame + audio stream. */
    private val streamFakeMedia: Boolean = true,
) : CarLinkProtocolAdapter {

    override val providerName: String = "mock"

    override val isAvailable: Boolean = true

    private var listener: CarLinkProtocolListener? = null
    private var initialized = false
    private var discovering = false
    private var connected: CarLinkDevice? = null

    /** Records every touch event for assertions. */
    val touchLog = java.util.Collections.synchronizedList(mutableListOf<ProjectionTouchEvent>())

    /** Records every key event for assertions. */
    val keyLog = java.util.Collections.synchronizedList(mutableListOf<ProjectionKeyEvent>())

    /** Increments on every successful connect; lets tests observe reconnects. */
    var connectCount: Int = 0
        private set

    override fun initialize() {
        initialized = true
    }

    override fun dispose() {
        stopDiscovery()
        disconnect()
        listener = null
        initialized = false
    }

    override fun startDiscovery() {
        if (!initialized || discovering) return
        discovering = true
        for (device in devices) {
            listener?.onDeviceFound(device)
        }
    }

    override fun stopDiscovery() {
        discovering = false
    }

    override fun connect(device: CarLinkDevice) {
        if (!initialized) {
            listener?.onError("PROVIDER_UNAVAILABLE", "mock adapter not initialized", null)
            return
        }
        stopDiscovery()
        if (autoConnect) {
            connected = device
            connectCount += 1
            listener?.onSessionStarted(device)
            if (streamFakeMedia) streamFakeSessionMedia()
            // Stream a single fake state packet so the pipeline sees live data.
            listener?.onMetadata(
                ProjectionMetadata(
                    title = "Mock track",
                    artist = "Mock artist",
                    playing = true,
                    street = "Mock street",
                ),
            )
        }
    }

    override fun disconnect() {
        val device = connected ?: return
        connected = null
        listener?.onAudioStopped(FAKE_AUDIO_STREAM_ID)
        listener?.onSessionEnded("mock disconnect device=${device.deviceId}")
    }

    override fun sendTouch(event: ProjectionTouchEvent) {
        if (connected != null) touchLog.add(event)
    }

    override fun sendKey(event: ProjectionKeyEvent) {
        if (connected != null) keyLog.add(event)
    }

    override fun setListener(listener: CarLinkProtocolListener?) {
        this.listener = listener
    }

    /** Streams one typed video config/frame and one audio start/frame/stop cycle. */
    private fun streamFakeSessionMedia() {
        listener?.onVideoConfig(
            ProjectionVideoConfig(
                codec = ProjectionVideoCodec.H264,
                codecData = FAKE_CODEC_DATA,
                width = FAKE_WIDTH,
                height = FAKE_HEIGHT,
            ),
        )
        listener?.onVideoFrame(
            ProjectionVideoFrame(
                codec = ProjectionVideoCodec.H264,
                width = FAKE_WIDTH,
                height = FAKE_HEIGHT,
                presentationTimeUs = FAKE_PTS_US,
                keyFrame = true,
                payload = FAKE_VIDEO_PAYLOAD,
            ),
        )
        listener?.onAudioStarted(
            CarLinkAudioFormat(
                streamId = FAKE_AUDIO_STREAM_ID,
                role = ProjectionAudioChannel.MEDIA,
                codec = ProjectionAudioCodec.AAC_LC,
                sampleRate = 48_000,
                channels = 2,
            ),
        )
        listener?.onAudioFrame(
            CarLinkAudioFrame(
                streamId = FAKE_AUDIO_STREAM_ID,
                presentationTimeUs = FAKE_PTS_US,
                payload = FAKE_AUDIO_PAYLOAD,
            ),
        )
    }

    /** Test hook: pushes a fake error through the listener. */
    fun simulateError(code: String, message: String) {
        listener?.onError(code, message, null)
    }

    /** Test hook: pushes a fake adapter exception surface (protocol layer). */
    fun simulateAdapterThrow() {
        throw IllegalStateException("mock adapter simulated failure")
    }

    /** Test hook: pushes a fake audio stream lifecycle through the listener. */
    fun simulateAudioStream(
        streamId: Int,
        role: ProjectionAudioChannel = ProjectionAudioChannel.NAVIGATION,
        payload: ByteArray = FAKE_AUDIO_PAYLOAD,
    ) {
        listener?.onAudioStarted(
            CarLinkAudioFormat(streamId, role, ProjectionAudioCodec.OPUS, 48_000, 2),
        )
        listener?.onAudioFrame(CarLinkAudioFrame(streamId, FAKE_PTS_US, payload))
        listener?.onAudioStopped(streamId)
    }

    /** Test hook: pushes a fake session end through the listener. */
    fun simulateSessionEnd(reason: String) {
        val device = connected ?: return
        connected = null
        listener?.onSessionEnded(reason)
    }

    companion object {
        const val MOCK_XIAOMI_ID = "mock-xiaomi-carwith"
        const val MOCK_VIVO_ID = "mock-vivo-jovi"
        const val MOCK_OPPO_ID = "mock-oppo-carplus"

        const val FAKE_AUDIO_STREAM_ID = 7
        const val FAKE_WIDTH = 1280
        const val FAKE_HEIGHT = 720
        const val FAKE_PTS_US = 33_333L
        val FAKE_CODEC_DATA: ByteArray = byteArrayOf(1, 2, 3, 4)
        val FAKE_VIDEO_PAYLOAD: ByteArray = byteArrayOf(9, 8, 7)
        val FAKE_AUDIO_PAYLOAD: ByteArray = byteArrayOf(5, 6)

        fun defaultDevices(): List<CarLinkDevice> = listOf(
            CarLinkDevice(MOCK_XIAOMI_ID, "Mock Xiaomi (CarWith)", "xiaomi", ProjectionTransport.USB),
            CarLinkDevice(MOCK_VIVO_ID, "Mock vivo (Jovi InCar)", "vivo", ProjectionTransport.WIFI),
            CarLinkDevice(MOCK_OPPO_ID, "Mock OPPO (Car+)", "oppo", ProjectionTransport.WIFI),
        )
    }
}

/**
 * Placeholder adapter used when no protocol provider is installed. Every call
 * fails soft and [isAvailable] is false so the UI can honestly show
 * "CarLink protocol provider unavailable" instead of a fake success. This is the
 * production default of `ProjectionHost`.
 */
class UnavailableCarLinkProtocolAdapter : CarLinkProtocolAdapter {
    override val providerName: String = "none"
    override val isAvailable: Boolean = false
    override fun initialize() {}
    override fun dispose() {}
    override fun startDiscovery() {}
    override fun stopDiscovery() {}
    override fun connect(device: CarLinkDevice) {}
    override fun disconnect() {}
    override fun sendTouch(event: ProjectionTouchEvent) {}
    override fun sendKey(event: ProjectionKeyEvent) {}
    override fun setListener(listener: CarLinkProtocolListener?) {}
}
