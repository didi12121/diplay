package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport

/**
 * Deterministic in-memory [CarLinkProtocolAdapter] used for tests and for the
 * "CarLink framework" UI flow while no real ICCOA CarLink SDK is integrated.
 *
 * It speaks NO protocol: no sockets, no ports, no handshakes. It only simulates
 * the *shape* of a protocol provider (discovery, session, fake state, teardown)
 * so the whole projection pipeline above the adapter can be exercised and so an
 * eventual official SDK adapter drops in behind the same interface.
 */
class MockCarLinkProtocolAdapter(
    /** Devices discovery reports, in order. */
    private val devices: List<CarLinkDevice> = defaultDevices(),
    /** When true, connect() completes synchronously with a fake session. */
    private val autoConnect: Boolean = true,
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

    /** Test hook: pushes a fake error through the listener. */
    fun simulateError(code: String, message: String) {
        listener?.onError(code, message, null)
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

        fun defaultDevices(): List<CarLinkDevice> = listOf(
            CarLinkDevice(MOCK_XIAOMI_ID, "Mock Xiaomi (CarWith)", "xiaomi", ProjectionTransport.USB),
            CarLinkDevice(MOCK_VIVO_ID, "Mock vivo (Jovi InCar)", "vivo", ProjectionTransport.WIFI),
            CarLinkDevice(MOCK_OPPO_ID, "Mock OPPO (Car+)", "oppo", ProjectionTransport.WIFI),
        )
    }
}

/**
 * Placeholder adapter reported when no protocol provider is installed. Every
 * call fails soft and [isAvailable] is false so the UI can honestly show
 * "CarLink protocol provider unavailable" instead of a fake success.
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
