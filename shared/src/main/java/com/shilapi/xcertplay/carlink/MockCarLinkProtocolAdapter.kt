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
 *
 * Session identity: like a real adapter, it binds the controller-minted
 * [CarLinkSessionToken] to its "SDK session" and echoes that token on every
 * callback. The `simulate*` hooks take an explicit token so tests can drive
 * out-of-order A/B callback interleavings deterministically.
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

    /** The token of the current "SDK session" — what a real SDK handle maps to. */
    @Volatile
    var currentToken: CarLinkSessionToken? = null
        private set

    /** Records every touch event for assertions. */
    val touchLog = java.util.Collections.synchronizedList(mutableListOf<ProjectionTouchEvent>())

    /** Records every key event for assertions. */
    val keyLog = java.util.Collections.synchronizedList(mutableListOf<ProjectionKeyEvent>())

    /** Increments on every successful connect; lets tests observe reconnects. */
    var connectCount: Int = 0
        private set

    /** Tokens received via connect(), in order. */
    val connectTokens = java.util.Collections.synchronizedList(mutableListOf<CarLinkSessionToken>())

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

    override fun connect(device: CarLinkDevice, session: CarLinkSessionToken) {
        if (!initialized) {
            listener?.onError(
                null,
                "PROVIDER_UNAVAILABLE",
                "mock adapter not initialized",
                CarLinkErrorSeverity.FATAL,
            )
            return
        }
        stopDiscovery()
        connectTokens.add(session)
        if (autoConnect) {
            connected = device
            currentToken = session
            connectCount += 1
            listener?.onSessionStarted(session, device)
            if (streamFakeMedia) streamFakeSessionMedia(session)
            // Stream a single fake state packet so the pipeline sees live data.
            listener?.onMetadata(
                session,
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
        val token = currentToken ?: return
        connected = null
        currentToken = null
        listener?.onAudioStopped(token, FAKE_AUDIO_STREAM_ID)
        listener?.onSessionEnded(token, "mock disconnect device=${device.deviceId}")
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
    private fun streamFakeSessionMedia(session: CarLinkSessionToken) {
        listener?.onVideoConfig(
            session,
            ProjectionVideoConfig(
                codec = ProjectionVideoCodec.H264,
                codecData = FAKE_CODEC_DATA,
                width = FAKE_WIDTH,
                height = FAKE_HEIGHT,
            ),
        )
        listener?.onVideoFrame(
            session,
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
            session,
            CarLinkAudioFormat(
                streamId = FAKE_AUDIO_STREAM_ID,
                role = ProjectionAudioChannel.MEDIA,
                codec = ProjectionAudioCodec.AAC_LC,
                sampleRate = 48_000,
                channels = 2,
            ),
        )
        listener?.onAudioFrame(
            session,
            CarLinkAudioFrame(
                streamId = FAKE_AUDIO_STREAM_ID,
                presentationTimeUs = FAKE_PTS_US,
                payload = FAKE_AUDIO_PAYLOAD,
            ),
        )
    }

    // ---- Test hooks: every one takes an explicit session token ----

    /**
     * Pushes an error. [session] null = provider-scoped; non-null = that
     * session's error (tests use old tokens to simulate late failures).
     */
    fun simulateError(
        code: String,
        message: String,
        severity: CarLinkErrorSeverity = CarLinkErrorSeverity.FATAL,
        cause: Throwable? = null,
        session: CarLinkSessionToken? = currentToken,
    ) {
        listener?.onError(session, code, message, severity, cause)
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
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onAudioStarted(
            session,
            CarLinkAudioFormat(streamId, role, ProjectionAudioCodec.OPUS, 48_000, 2),
        )
        listener?.onAudioFrame(session, CarLinkAudioFrame(streamId, FAKE_PTS_US, payload))
        listener?.onAudioStopped(session, streamId)
    }

    /** Test hook: pushes a typed video config (developer harnesses). */
    fun simulateVideoConfig(
        config: ProjectionVideoConfig,
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onVideoConfig(session, config)
    }

    /** Test hook: pushes a typed video frame (developer harnesses). */
    fun simulateVideoFrame(
        frame: ProjectionVideoFrame,
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onVideoFrame(session, frame)
    }

    /** Test hook: opens a typed audio stream (developer harnesses). */
    fun simulateAudioStarted(
        format: CarLinkAudioFormat,
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onAudioStarted(session, format)
    }

    /** Test hook: pushes one raw audio access unit (developer harnesses). */
    fun simulateAudioFrame(
        frame: CarLinkAudioFrame,
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onAudioFrame(session, frame)
    }

    /** Test hook: closes a typed audio stream (developer harnesses). */
    fun simulateAudioStopped(
        streamId: Int,
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onAudioStopped(session, streamId)
    }

    /** Test hook: pushes a metadata update (developer harnesses). */
    fun simulateMetadata(
        metadata: ProjectionMetadata,
        session: CarLinkSessionToken = currentToken!!,
    ) {
        listener?.onMetadata(session, metadata)
    }

    /**
     * Test hook: reports a session start as if the SDK had connected.
     * [session] defaults to the token of the latest connect request but can be
     * an OLD token to simulate a stale late session-start.
     */
    fun simulateSessionStarted(
        device: CarLinkDevice,
        session: CarLinkSessionToken = currentToken ?: connectTokens.last(),
    ) {
        connected = device
        currentToken = session
        connectCount += 1
        listener?.onSessionStarted(session, device)
    }

    /**
     * Test hook: reports a session end. [session] defaults to the current
     * token but can be an OLD token to simulate a stale trailing end.
     */
    fun simulateSessionEnd(
        reason: String,
        session: CarLinkSessionToken = currentToken ?: connectTokens.last(),
    ) {
        listener?.onSessionEnded(session, reason)
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
    override fun connect(device: CarLinkDevice, session: CarLinkSessionToken) {}
    override fun disconnect() {}
    override fun sendTouch(event: ProjectionTouchEvent) {}
    override fun sendKey(event: ProjectionKeyEvent) {}
    override fun setListener(listener: CarLinkProtocolListener?) {}
}
