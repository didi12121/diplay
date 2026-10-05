package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionKeyAction
import com.shilapi.xcertplay.projection.ProjectionKeyCode
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionTouchAction
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTouchPointer
import com.shilapi.xcertplay.projection.ProjectionDisplayGeometry
import com.shilapi.xcertplay.projection.ProjectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock CarLink adapter and backend lifecycle: discover, connect, stream, disconnect. */
class MockCarLinkBackendTest {

    private fun touchEvent() = ProjectionTouchEvent(
        ProjectionTouchAction.DOWN,
        listOf(ProjectionTouchPointer(0, 10f, 20f, down = true)),
        ProjectionDisplayGeometry(1920, 990, ProjectionRect(0f, 0f, 1920f, 990f)),
    )

    private fun keyEvent() = ProjectionKeyEvent(ProjectionKeyAction.DOWN, ProjectionKeyCode.VOICE_ASSISTANT)

    private fun backendOf(adapter: CarLinkProtocolAdapter): CarLinkProjectionBackend =
        CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(
                com.shilapi.xcertplay.projection.ProjectionVideoSink.NOOP,
                com.shilapi.xcertplay.projection.ProjectionAudioSink.NOOP,
            ),
        )

    @Test
    fun mockAdapterListsEcosystemDevices() {
        val adapter = MockCarLinkProtocolAdapter()
        adapter.initialize()
        val found = mutableListOf<CarLinkDevice>()
        adapter.setListener(object : CarLinkProtocolListener {
            override fun onDeviceFound(device: CarLinkDevice) {
                found.add(device)
            }
        })
        adapter.startDiscovery()
        assertEquals(3, found.size)
        assertEquals(setOf("xiaomi", "vivo", "oppo"), found.map { it.vendorHint }.toSet())
    }

    @Test
    fun backendDiscoveryPublishesDiscoveringThenDevices() {
        val backend = backendOf(MockCarLinkProtocolAdapter())
        val states = mutableListOf<ProjectionState>()
        backend.addStateListener { _, state -> states.add(state) }
        backend.initialize()
        backend.start()
        assertTrue(states.contains(ProjectionState.Discovering))
        assertEquals(3, backend.discoveredDevices().size)
    }

    @Test
    fun connectStreamsFakeStateAndReachesConnected() {
        val adapter = MockCarLinkProtocolAdapter()
        val backend = backendOf(adapter)
        val states = mutableListOf<ProjectionState>()
        var metadata: ProjectionMetadata? = null
        backend.initialize()
        backend.start()
        backend.carLink.onMetadata { metadata = it }
        backend.addStateListener(ProjectionStateListener { _, state -> states.add(state) })
        backend.connect(null)
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(states.contains(ProjectionState.Connected))
        assertNotNull("mock should stream a fake metadata packet", metadata)
        assertEquals("Mock track", metadata?.title)
    }

    @Test
    fun disconnectReturnsToReadyAndEndsSession() {
        val backend = backendOf(MockCarLinkProtocolAdapter())
        backend.initialize()
        backend.start()
        backend.connect(null)
        assertEquals(ProjectionState.Connected, backend.state)
        backend.disconnect()
        assertEquals(ProjectionState.Ready, backend.state)
        assertFalse(backend.carLink.sessionActive)
    }

    @Test
    fun touchAndKeyReachTheAdapterWhileConnected() {
        val adapter = MockCarLinkProtocolAdapter()
        val backend = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)
        assertTrue(backend.onTouchEvent(touchEvent()))
        assertTrue(backend.onKeyEvent(keyEvent()))
        assertEquals(1, adapter.touchLog.size)
        assertEquals(1, adapter.keyLog.size)
    }

    @Test
    fun inputIsRejectedWithoutASession() {
        val backend = backendOf(MockCarLinkProtocolAdapter())
        backend.initialize()
        backend.start()
        assertFalse(backend.onTouchEvent(touchEvent()))
        assertFalse(backend.onKeyEvent(keyEvent()))
    }

    @Test
    fun errorRecoveryReconnectsAfterFailure() {
        val adapter = MockCarLinkProtocolAdapter()
        val backend = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)
        adapter.simulateError("PROTO", "simulated protocol failure")
        val error = backend.state
        assertTrue(error is ProjectionState.Error)
        (error as ProjectionState.Error).let {
            assertEquals(ProjectionErrorCode.PROTOCOL_ERROR, it.code)
            assertEquals("carlink", it.backendId)
        }
        // Recovery: connecting again succeeds.
        backend.connect(null)
        assertEquals(ProjectionState.Connected, backend.state)
        assertEquals(2, adapter.connectCount)
    }

    @Test
    fun sessionEndFromPeerReturnsToReady() {
        val adapter = MockCarLinkProtocolAdapter()
        val backend = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)
        adapter.simulateSessionEnd("peer detached")
        assertEquals(ProjectionState.Ready, backend.state)
    }

    @Test
    fun unavailableProviderReportsProviderUnavailable() {
        val backend = backendOf(UnavailableCarLinkProtocolAdapter())
        backend.initialize()
        val error = backend.state
        assertTrue(error is ProjectionState.Error)
        (error as ProjectionState.Error).let {
            assertEquals(ProjectionErrorCode.PROVIDER_UNAVAILABLE, it.code)
            assertEquals("CarLink protocol provider unavailable", it.message)
        }
        // Connect keeps reporting the same honest failure.
        backend.connect(null)
        assertTrue(backend.state is ProjectionState.Error)
    }

    @Test
    fun closeDisposesTheAdapter() {
        val adapter = MockCarLinkProtocolAdapter()
        val backend = backendOf(adapter)
        backend.initialize()
        backend.start()
        backend.connect(null)
        backend.close()
        assertEquals(ProjectionState.Idle, backend.state)
        assertFalse(backend.carLink.sessionActive)
    }
}
