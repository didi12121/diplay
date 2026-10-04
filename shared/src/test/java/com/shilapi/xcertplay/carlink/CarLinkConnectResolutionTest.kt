package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connect-target resolution: the device resources are claimed for must be the
 * very device the controller connects. No "manager thinks UNKNOWN / controller
 * picks USB" drift, and no speculative claims without a target device.
 */
class CarLinkConnectResolutionTest {

    private class RecordingAdapter(
        devices: List<CarLinkDevice> = defaultThree(),
    ) : CarLinkProtocolAdapter {
        val connected = mutableListOf<String>()
        private val delegate = MockCarLinkProtocolAdapter(
            devices = devices,
            streamFakeMedia = false,
        )

        override val providerName: String get() = delegate.providerName
        override val isAvailable: Boolean get() = delegate.isAvailable
        override fun setListener(listener: CarLinkProtocolListener?) = delegate.setListener(listener)
        override fun initialize() = delegate.initialize()
        override fun dispose() = delegate.dispose()
        override fun startDiscovery() = delegate.startDiscovery()
        override fun stopDiscovery() = delegate.stopDiscovery()
        override fun connect(device: CarLinkDevice, session: CarLinkSessionToken) {
            connected.add(device.deviceId)
            delegate.connect(device, session)
        }

        override fun disconnect() = delegate.disconnect()
        override fun sendTouch(event: com.shilapi.xcertplay.projection.ProjectionTouchEvent) =
            delegate.sendTouch(event)

        override fun sendKey(event: com.shilapi.xcertplay.projection.ProjectionKeyEvent) =
            delegate.sendKey(event)

        companion object {
            fun defaultThree() = listOf(
                CarLinkDevice("dev-xiaomi", "Xiaomi", "xiaomi", ProjectionTransport.USB),
                CarLinkDevice("dev-vivo", "vivo", "vivo", ProjectionTransport.WIFI),
                CarLinkDevice("dev-oppo", "OPPO", "oppo", ProjectionTransport.WIFI),
            )
        }
    }

    private fun backendOf(adapter: CarLinkProtocolAdapter): CarLinkProjectionBackend =
        CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )

    @Test
    fun connectNullWithUsbPhoneClaimsUsbAudioAndConnectsTheSameDevice() {
        val manager = ProjectionManager()
        val adapter = RecordingAdapter()
        val backend = backendOf(adapter)
        manager.register(backend)
        backend.initialize()
        backend.start()
        manager.select(com.shilapi.xcertplay.projection.ProjectionMode.CARLINK)

        manager.connect(null)

        // USB phone resolved before arbitration → USB + AUDIO claimed…
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            manager.resourcesHeldBy(CarLinkProjectionBackend.ID),
        )
        // …and the controller connected THAT SAME phone (no drift).
        assertEquals(listOf("dev-xiaomi"), adapter.connected)
    }

    @Test
    fun connectNullWithWirelessPhoneClaimsWifiAudio() {
        val manager = ProjectionManager()
        val adapter = RecordingAdapter(
            devices = listOf(
                CarLinkDevice("dev-vivo", "vivo", "vivo", ProjectionTransport.WIFI),
            ),
        )
        val backend = backendOf(adapter)
        manager.register(backend)
        backend.initialize()
        backend.start()

        manager.connect(null)

        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO),
            manager.resourcesHeldBy(CarLinkProjectionBackend.ID),
        )
        assertEquals(listOf("dev-vivo"), adapter.connected)
    }

    @Test
    fun explicitSecondDeviceDrivesBothArbitrationAndConnection() {
        val manager = ProjectionManager()
        val adapter = RecordingAdapter()
        val backend = backendOf(adapter)
        manager.register(backend)
        backend.initialize()
        backend.start()

        // Explicitly request the second discovered device (Wi-Fi vivo).
        val second = backend.discoveredDevices()[1]
        manager.connect(second)

        // Resources follow the SECOND device (WIFI, not USB)…
        assertEquals(
            setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO),
            manager.resourcesHeldBy(CarLinkProjectionBackend.ID),
        )
        // …and the controller connects the second device.
        assertEquals(listOf("dev-vivo"), adapter.connected)
    }

    @Test
    fun connectWithoutDiscoveredDeviceClaimsNothingSpeculative() {
        val manager = ProjectionManager()
        val adapter = RecordingAdapter(
            devices = emptyList(),
        )
        val backend = backendOf(adapter)
        manager.register(backend)
        backend.initialize()
        backend.start()

        // Nothing discovered: no speculative claims, a clear failure instead.
        manager.connect(null)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
        assertTrue(backend.state is com.shilapi.xcertplay.projection.ProjectionState.Error)
    }

    @Test
    fun resolvedDeviceIsExactlyTheConnectedDevice() {
        val manager = ProjectionManager()
        val adapter = RecordingAdapter()
        val backend = backendOf(adapter)
        manager.register(backend)
        backend.initialize()
        backend.start()

        val resolved = backend.resolveConnectDevice(null)
        assertNotNull(resolved)
        manager.connect(null)
        // The arbitration-time resolved device is the one the adapter got.
        assertEquals(resolved!!.id, adapter.connected.single())
    }

    @Test
    fun explicitRequestIsResolvedAgainstDiscovery() {
        val adapter = RecordingAdapter()
        val backend = backendOf(adapter)
        backend.initialize()
        backend.start()
        // An explicit request is validated against the discovery list and
        // resolved to the DISCOVERED device — the authoritative transport wins.
        val requested = ProjectionDevice("dev-oppo", "OPPO", CarLinkProjectionBackend.ID)
        val resolved = backend.resolveConnectDevice(requested)
        assertEquals("dev-oppo", resolved?.id)
        assertEquals(ProjectionTransport.WIFI, resolved?.transport)
    }

    @Test
    fun resolveIsNullWhenNothingIsDiscovered() {
        val adapter = RecordingAdapter(devices = emptyList())
        val backend = backendOf(adapter)
        backend.initialize()
        backend.start()
        assertNull(backend.resolveConnectDevice(null))
    }

    private fun assertSameDevice(expected: ProjectionDevice, actual: ProjectionDevice?) {
        assertEquals(expected.id, actual?.id)
        assertEquals(expected.transport, actual?.transport)
    }
}
