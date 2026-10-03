package com.shilapi.xcertplay.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Backend registration and switching for [ProjectionManager]. */
class ProjectionManagerTest {

    private class FakeBackend(
        override val id: String,
        override val requiredResources: Set<ProjectionResource> = emptySet(),
    ) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        val stateStore = ProjectionStateStore(id)
        override val state: ProjectionState get() = stateStore.state
        var connectCount = 0
        var disconnectCount = 0
        var stopCount = 0
        var connectDevice: ProjectionDevice? = null

        override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() { stopCount += 1 }
        override fun connect(device: ProjectionDevice?) {
            connectCount += 1
            connectDevice = device
            stateStore.publish(ProjectionState.Connected)
        }

        override fun disconnect() {
            disconnectCount += 1
            stateStore.publish(ProjectionState.Idle)
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}
    }

    @Test fun registersAndListsBackends() {
        val manager = ProjectionManager()
        val carPlay = FakeBackend("carplay")
        val carLink = FakeBackend("carlink")
        manager.register(carPlay)
        manager.register(carLink)
        assertEquals(listOf("carplay", "carlink"), manager.registeredBackends.map { it.id })
        assertEquals(carPlay, manager.backend("carplay"))
    }

    @Test fun selectPrefersNamedBackend() {
        val manager = ProjectionManager()
        val carPlay = FakeBackend("carplay")
        val carLink = FakeBackend("carlink")
        manager.register(carPlay)
        manager.register(carLink)
        manager.select(ProjectionMode.CARLINK)
        assertEquals("carlink", manager.activeBackend?.id)
        manager.select(ProjectionMode.CARPLAY)
        assertEquals("carplay", manager.activeBackend?.id)
    }

    @Test fun autoModePrefersCarPlayThenCarLink() {
        val manager = ProjectionManager()
        manager.register(FakeBackend("carlink"))
        manager.select(ProjectionMode.AUTO)
        assertEquals("carlink", manager.activeBackend?.id)
        manager.register(FakeBackend("carplay"))
        manager.select(ProjectionMode.AUTO)
        assertEquals("carplay", manager.activeBackend?.id)
    }

    @Test fun connectDispatchesToDeviceBackend() {
        val manager = ProjectionManager()
        val carPlay = FakeBackend("carplay")
        val carLink = FakeBackend("carlink")
        manager.register(carPlay)
        manager.register(carLink)
        val device = ProjectionDevice(id = "phone-1", name = "Pixel", backendId = "carlink")
        manager.connect(device)
        assertEquals(1, carLink.connectCount)
        assertEquals(0, carPlay.connectCount)
        assertEquals(device, carLink.connectDevice)
        assertEquals("carlink", manager.activeBackend?.id)
    }

    @Test fun switchingBackendDisconnectsThePreviousOne() {
        val manager = ProjectionManager()
        val carPlay = FakeBackend("carplay")
        val carLink = FakeBackend("carlink")
        manager.register(carPlay)
        manager.register(carLink)
        manager.select(ProjectionMode.CARPLAY)
        manager.connect(null)
        assertEquals(1, carPlay.connectCount)
        manager.select(ProjectionMode.CARLINK)
        // Switching deactivates the previous backend and releases its resources.
        assertEquals(1, carPlay.disconnectCount)
        assertEquals("carlink", manager.activeBackend?.id)
    }

    @Test fun disconnectReleasesResourcesOfTheActiveBackend() {
        val manager = ProjectionManager()
        val carLink = FakeBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(carLink)
        manager.connect(null)
        assertEquals(setOf(ProjectionResource.USB, ProjectionResource.AUDIO), manager.resourcesHeldBy("carlink"))
        manager.disconnect()
        assertTrue(manager.resourcesHeldBy("carlink").isEmpty())
        assertEquals(1, carLink.disconnectCount)
    }

    @Test fun unregisterRemovesBackendAndClearsActivePointer() {
        val manager = ProjectionManager()
        val carPlay = FakeBackend("carplay")
        manager.register(carPlay)
        manager.select(ProjectionMode.CARPLAY)
        manager.unregister("carplay")
        assertNull(manager.activeBackend)
        assertNull(manager.backend("carplay"))
    }

    @Test fun connectWithoutBackendsFails() {
        val manager = ProjectionManager()
        try {
            manager.connect(null)
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
    }

    @Test fun stateListenerReceivesBackendTaggedChanges() {
        val manager = ProjectionManager()
        val carLink = FakeBackend("carlink")
        manager.register(carLink)
        val seen = mutableListOf<Pair<String, ProjectionState>>()
        manager.addStateListener { backendId, state -> seen.add(backendId to state) }
        carLink.stateStore.publish(ProjectionState.Discovering)
        carLink.stateStore.publish(ProjectionState.Connected)
        assertEquals(listOf("carlink", "carlink"), seen.map { it.first })
        assertEquals(listOf<ProjectionState>(ProjectionState.Discovering, ProjectionState.Connected), seen.map { it.second })
    }

    @Test fun selectionListenerReportsBackendChanges() {
        val manager = ProjectionManager()
        manager.register(FakeBackend("carplay"))
        manager.register(FakeBackend("carlink"))
        val changes = mutableListOf<String?>()
        manager.addSelectionListener { backendId, _ -> changes.add(backendId) }
        manager.select(ProjectionMode.CARLINK)
        manager.select(ProjectionMode.CARPLAY)
        assertEquals(listOf("carlink", "carplay"), changes)
        assertFalse(changes.contains(null))
    }
}
