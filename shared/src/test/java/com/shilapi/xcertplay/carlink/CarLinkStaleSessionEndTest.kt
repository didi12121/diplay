package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stale session-callback isolation (session epoch / trailing-end guard): the
 * late onSessionEnded of an old, fatally-torn-down session must never
 * overwrite a newer session's state (Ready over Connecting/Connected), tear a
 * newer session down, release its resources or close its media sinks.
 */
class CarLinkStaleSessionEndTest {

    private fun backendOf(
        adapter: MockCarLinkProtocolAdapter,
        onClose: () -> Unit = {},
    ): Pair<ProjectionManager, CarLinkProjectionBackend> {
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(
                ProjectionVideoSink.NOOP,
                ProjectionAudioSink.NOOP,
                onClose = onClose,
            ),
        )
        manager.register(backend)
        return manager to backend
    }

    @Test
    fun lateSessionEndAfterFatalDoesNotResetNewAttemptToReady() {
        // autoConnect=false keeps the second attempt in Connecting state so the
        // "late end must not paint Ready over it" window is observable.
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false, autoConnect = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        adapter.simulateSessionStarted(
            CarLinkDevice("mock-xiaomi", "Xiaomi", "xiaomi",
                com.shilapi.xcertplay.projection.ProjectionTransport.USB),
        ) // session A up
        assertTrue(backend.isSessionActive)

        // A dies fatally.
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)
        assertTrue(backend.state is ProjectionState.Error)

        // The user retries: a new attempt is in flight (still Connecting).
        backend.stop()
        backend.initialize()
        backend.start()
        val device = backend.discoveredDevices().first()
        backend.connect(device)
        val before = backend.state
        assertTrue("expected Connecting window, got $before", before is ProjectionState.Connecting)

        // …and only NOW the old session's trailing end arrives.
        adapter.simulateSessionEnd("late end of dead session A")

        // The stale end must not paint Ready over the new attempt's state.
        assertEquals(before, backend.state)
    }

    @Test
    fun lateSessionEndAfterFatalKeepsNewSessionConnectedAndResourced() {
        var closes = 0
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter, onClose = { closes++ })
        backend.initialize()
        backend.start()
        manager.connect(null) // session A
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())

        // Session B comes up and holds resources.
        backend.initialize()
        backend.start()
        manager.connect(null)
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isNotEmpty())
        val closesAfterB = closes

        // The late end of dead session A arrives while B is live.
        adapter.simulateSessionEnd("late end of dead session A")

        // B must be untouched: still connected, session active, resources held,
        // media sinks NOT closed by the stale callback.
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isNotEmpty())
        assertEquals("stale end must not close B's media sinks", closesAfterB, closes)
    }

    @Test
    fun normalFatalOrderStaysLatchedUntilRecovery() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null)

        // Normal fatal order: FATAL, then the session end, no retry yet.
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)
        adapter.simulateSessionEnd("session A gone")

        // Error stays latched, resources empty.
        assertTrue(backend.state is ProjectionState.Error)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())

        // Recovery is allowed afterwards.
        backend.initialize()
        assertEquals(ProjectionState.Ready, backend.state)
    }

    @Test
    fun staleEndCannotTearDownNewSessionNorReleaseItsLease() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null) // A
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)

        backend.initialize()
        backend.start()
        manager.connect(null) // B
        adapter.simulateError("LINK_LOST", "again", CarLinkErrorSeverity.FATAL)

        backend.initialize()
        backend.start()
        manager.connect(null) // C
        val heldBefore = manager.resourcesHeldBy(CarLinkProjectionBackend.ID)

        // Two dead sessions each owe a trailing end; none may hurt C.
        adapter.simulateSessionEnd("late end A")
        adapter.simulateSessionEnd("late end B")

        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertEquals(heldBefore, manager.resourcesHeldBy(CarLinkProjectionBackend.ID))
    }

    @Test
    fun explicitDisconnectOfCurrentSessionStillWorksNormally() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null)
        assertTrue(backend.isSessionActive)

        // A normal user disconnect is a REAL end — not swallowed as stale.
        assertTrue(manager.disconnectBackend(CarLinkProjectionBackend.ID))
        assertFalse(backend.isSessionActive)
        assertEquals(ProjectionState.Ready, backend.state)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
    }

    @Test
    fun connectToKnownDeviceWhileOldSessionLingersDoesNotDrift() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null) // A on the first discovered device (USB)
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)

        // Explicit reconnect to the Wi-Fi phone after the fatal error.
        backend.initialize()
        backend.start()
        val wifiPhone = backend.discoveredDevices().first {
            it.transport == com.shilapi.xcertplay.projection.ProjectionTransport.WIFI
        }
        manager.connect(wifiPhone)

        // WIFI+AUDIO claimed, and a stale end cannot change any of that.
        adapter.simulateSessionEnd("late end A")
        assertEquals(
            setOf(
                com.shilapi.xcertplay.projection.ProjectionResource.WIFI,
                com.shilapi.xcertplay.projection.ProjectionResource.AUDIO,
            ),
            manager.resourcesHeldBy(CarLinkProjectionBackend.ID),
        )
        assertEquals(ProjectionState.Connected, backend.state)
    }
}
