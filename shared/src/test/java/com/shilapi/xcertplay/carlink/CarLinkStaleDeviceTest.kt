package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stale explicit device handling: an explicitly requested device that vanished
 * from discovery before the connect executes must produce CONNECT_FAILED —
 * never a silent fallback to another phone (which would connect USB while WIFI
 * resources were arbitrated).
 */
class CarLinkStaleDeviceTest {

    /** Test adapter whose discovery list can shrink between rounds. */
    private class MutableDiscoveryAdapter : CarLinkProtocolAdapter {
        val devices = mutableListOf<CarLinkDevice>()
        val connected = mutableListOf<String>()
        private var listener: CarLinkProtocolListener? = null

        override val providerName: String = "mutable"
        override val isAvailable: Boolean = true

        override fun setListener(listener: CarLinkProtocolListener?) {
            this.listener = listener
        }

        override fun initialize() {}
        override fun dispose() {}
        override fun startDiscovery() {
            devices.forEach { listener?.onDeviceFound(it) }
        }

        override fun stopDiscovery() {}
        override fun connect(device: CarLinkDevice) {
            connected.add(device.deviceId)
        }

        override fun disconnect() {}
        override fun sendTouch(event: ProjectionTouchEvent) {}
        override fun sendKey(event: ProjectionKeyEvent) {}
    }

    private val xiaomiUsb = CarLinkDevice("dev-xiaomi", "Xiaomi", "xiaomi", ProjectionTransport.USB)
    private val vivoWifi = CarLinkDevice("dev-vivo", "vivo", "vivo", ProjectionTransport.WIFI)

    private fun sessionWith(
        adapter: MutableDiscoveryAdapter,
    ): Pair<ProjectionManager, CarLinkProjectionBackend> {
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        return manager to backend
    }

    private fun ProjectionManager.held(backend: CarLinkProjectionBackend) =
        resourcesHeldBy(CarLinkProjectionBackend.ID)

    @Test
    fun explicitStaleDeviceNeverFallsBackToAnotherDevice() {
        val adapter = MutableDiscoveryAdapter()
        adapter.devices += listOf(xiaomiUsb, vivoWifi)
        val (manager, backend) = sessionWith(adapter)

        // Capture vivo's handle, then make vivo disappear from discovery.
        val staleVivo = backend.discoveredDevices().first { it.id == "dev-vivo" }
        adapter.devices.removeAll { it.deviceId == "dev-vivo" }
        backend.stop()
        backend.start() // re-discovery: only Xiaomi USB remains

        manager.connect(staleVivo)

        // CONNECT_FAILED — and crucially the USB phone was NEVER connected.
        assertTrue(adapter.connected.isEmpty())
        val state = backend.state
        assertTrue("expected Error, got $state", state is ProjectionState.Error)
    }

    @Test
    fun staleExplicitWifiDeviceDoesNotConnectTheUsbDevice() {
        val adapter = MutableDiscoveryAdapter()
        adapter.devices += listOf(xiaomiUsb, vivoWifi)
        val (manager, backend) = sessionWith(adapter)

        val staleVivo = backend.discoveredDevices().first { it.id == "dev-vivo" }
        adapter.devices.removeAll { it.deviceId == "dev-vivo" }
        backend.stop()
        backend.start()

        manager.connect(staleVivo)

        // No fallback to the USB Xiaomi…
        assertTrue(adapter.connected.isEmpty())
        // …and no resources claimed or retained for the dead request:
        // USB not claimed, WIFI not retained, AUDIO not leaked.
        assertTrue(manager.held(backend).isEmpty())
    }

    @Test
    fun noDeviceAndConnectNullClaimsZeroResources() {
        val adapter = MutableDiscoveryAdapter() // no devices at all
        val (manager, backend) = sessionWith(adapter)

        manager.connect(null)

        // 0 resource claim, clean failure.
        assertTrue(manager.held(backend).isEmpty())
        assertTrue(backend.state is ProjectionState.Error)
    }

    @Test
    fun validExplicitDeviceStillConnectsNormally() {
        val adapter = MutableDiscoveryAdapter()
        adapter.devices += listOf(xiaomiUsb, vivoWifi)
        val (manager, backend) = sessionWith(adapter)

        val vivo = backend.discoveredDevices().first { it.id == "dev-vivo" }
        manager.connect(vivo)

        // Explicit valid device: connects THAT device with ITS resources.
        assertEquals(listOf("dev-vivo"), adapter.connected)
        assertEquals(
            setOf(com.shilapi.xcertplay.projection.ProjectionResource.WIFI,
                com.shilapi.xcertplay.projection.ProjectionResource.AUDIO),
            manager.held(backend),
        )
    }

    @Test
    fun staleRequestResolvesToNull() {
        val adapter = MutableDiscoveryAdapter()
        adapter.devices += listOf(xiaomiUsb)
        val (_, backend) = sessionWith(adapter)

        val gone = ProjectionDevice("dev-gone", "gone", CarLinkProjectionBackend.ID)
        assertNull(backend.resolveConnectDevice(gone))
    }

    @Test
    fun explicitRequestResolvesToTheDiscoveredDeviceWithAuthoritativeTransport() {
        val adapter = MutableDiscoveryAdapter()
        adapter.devices += listOf(xiaomiUsb, vivoWifi)
        val (_, backend) = sessionWith(adapter)

        // Request with an imprecise transport: resolution returns the
        // DISCOVERED device, so claims follow reality.
        val requested = ProjectionDevice("dev-vivo", "vivo", CarLinkProjectionBackend.ID)
        val resolved = backend.resolveConnectDevice(requested)
        assertNotNull(resolved)
        assertEquals("dev-vivo", resolved!!.id)
        assertEquals(ProjectionTransport.WIFI, resolved.transport)
    }
}
