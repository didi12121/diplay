package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter exception boundary and state-machine integrity: every adapter throw
 * becomes ProjectionState.Error with cause, and no healthy state may overwrite
 * it (the old Initializing → Error → Ready bug).
 */
class CarLinkStateAndLifecycleTest {

    private class ThrowingAdapter(
        private val throwOn: Set<String>,
        private val delegate: MockCarLinkProtocolAdapter = MockCarLinkProtocolAdapter(),
    ) : CarLinkProtocolAdapter by delegate {
        override fun initialize() {
            if ("initialize" in throwOn) throw IllegalStateException("sdk init exploded")
            delegate.initialize()
        }

        override fun startDiscovery() {
            if ("startDiscovery" in throwOn) throw IllegalArgumentException("sdk discovery exploded")
            delegate.startDiscovery()
        }

        override fun connect(device: CarLinkDevice) {
            if ("connect" in throwOn) throw IllegalStateException("sdk connect exploded")
            delegate.connect(device)
        }

        override fun disconnect() {
            if ("disconnect" in throwOn) throw IllegalStateException("sdk disconnect exploded")
            delegate.disconnect()
        }
    }

    private fun backendOf(adapter: CarLinkProtocolAdapter): CarLinkProjectionBackend =
        CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )

    @Test
    fun initializeThrowingAdapterReportsErrorNotReady() {
        val backend = backendOf(ThrowingAdapter(setOf("initialize")))
        backend.initialize()

        val state = backend.state
        assertTrue("expected Error, got $state", state is ProjectionState.Error)
        (state as ProjectionState.Error).let {
            assertEquals(ProjectionErrorCode.PROTOCOL_ERROR, it.code)
            assertEquals("carlink", it.backendId)
            assertNotNull(it.cause)
        }
        // Regression: the Error must NOT be overwritten by Ready.
        assertTrue(backend.state is ProjectionState.Error)
    }

    @Test
    fun initializeSuccessReachesReady() {
        val backend = backendOf(ThrowingAdapter(emptySet()))
        backend.initialize()
        assertEquals(ProjectionState.Ready, backend.state)
    }

    @Test
    fun startDiscoveryThrowingAdapterReportsError() {
        val backend = backendOf(ThrowingAdapter(setOf("startDiscovery")))
        backend.initialize()
        backend.start()
        val state = backend.state
        assertTrue(state is ProjectionState.Error)
        assertNotNull((state as ProjectionState.Error).cause)
    }

    @Test
    fun connectThrowingAdapterReportsError() {
        val backend = backendOf(ThrowingAdapter(setOf("connect")))
        backend.initialize()
        backend.start()
        backend.connect(null)
        val state = backend.state
        assertTrue(state is ProjectionState.Error)
        assertNotNull((state as ProjectionState.Error).cause)
    }

    @Test
    fun disconnectThrowingAdapterTearsDownAndReleasesResources() {
        val manager = ProjectionManager()
        val adapter = ThrowingAdapter(setOf("disconnect"))
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        manager.connect(null)
        // Session is up and holds its transport resources.
        assertTrue(backend.isSessionActive)

        // adapter.disconnect throws: the controller's exception boundary tears
        // the session down, so the manager must release the hardware lease —
        // an Error state must never leak AUDIO/USB claims.
        val released = manager.disconnectBackend(CarLinkProjectionBackend.ID)
        assertFalse(backend.isSessionActive)
        assertTrue("resources must be released after a failing disconnect", released)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
        assertTrue(backend.state is ProjectionState.Error)
    }

    @Test
    fun successfulSessionHoldsAndReleasesResources() {
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = MockCarLinkProtocolAdapter(),
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        val usbPhone = com.shilapi.xcertplay.projection.ProjectionDevice(
            id = "d1",
            name = "phone",
            backendId = CarLinkProjectionBackend.ID,
            transport = com.shilapi.xcertplay.projection.ProjectionTransport.USB,
        )
        manager.connect(usbPhone)
        // Transport-aware claims: a USB session owns USB + AUDIO.
        assertEquals(
            setOf(ProjectionResource.AUDIO, ProjectionResource.USB),
            manager.resourcesHeldBy(CarLinkProjectionBackend.ID),
        )
        assertTrue(manager.disconnectBackend(CarLinkProjectionBackend.ID))
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
    }
}
