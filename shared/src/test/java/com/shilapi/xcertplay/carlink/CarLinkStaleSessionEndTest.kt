package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stale session-callback isolation by REAL session identity: a late callback
 * of an old, fatally-torn-down session is recognized by its
 * [CarLinkSessionToken] and must never overwrite a newer session's state,
 * tear it down, release its resources, close its media sinks or feed media to
 * its decoders — regardless of arrival order or how many callbacks interleave.
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

    /** Token of the n-th connect attempt (0-based). */
    private fun MockCarLinkProtocolAdapter.token(index: Int) = connectTokens[index]

    @Test
    fun lateSessionEndAfterFatalDoesNotResetNewAttemptToReady() {
        // autoConnect=false keeps the second attempt in Connecting state so the
        // "late end must not paint Ready over it" window is observable.
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false, autoConnect = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        val deviceA = backend.discoveredDevices().first()
        manager.connect(deviceA) // attempt A
        val tokenA = adapter.token(0)
        adapter.simulateSessionStarted(
            CarLinkDevice("mock-xiaomi", "Xiaomi", "xiaomi",
                com.shilapi.xcertplay.projection.ProjectionTransport.USB),
            session = tokenA,
        )
        assertTrue(backend.isSessionActive)

        // A dies fatally.
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)
        assertTrue(backend.state is ProjectionState.Error)

        // The user retries: attempt B in flight (still Connecting).
        backend.stop()
        backend.initialize()
        backend.start()
        val deviceB = backend.discoveredDevices().first()
        backend.connect(deviceB) // attempt B, token minted
        val before = backend.state
        assertTrue("expected Connecting window, got $before", before is ProjectionState.Connecting)

        // …and only NOW session A's trailing end arrives — with A's token.
        adapter.simulateSessionEnd("late end of dead session A", session = tokenA)

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
        val tokenA = adapter.token(0)
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())

        // Session B comes up and holds resources.
        backend.initialize()
        backend.start()
        manager.connect(null)
        val tokenB = adapter.token(1)
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isNotEmpty())
        val closesAfterB = closes

        // The late end of dead session A arrives while B is live.
        adapter.simulateSessionEnd("late end of dead session A", session = tokenA)

        // B must be untouched: still connected, session active, resources held,
        // media sinks NOT closed by the stale callback.
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isNotEmpty())
        assertEquals("stale end must not close B's media sinks", closesAfterB, closes)
        // And B's own end still works afterwards (identity, not counting).
        adapter.simulateSessionEnd("B really ends", session = tokenB)
        assertEquals(ProjectionState.Ready, backend.state)
    }

    @Test
    fun normalFatalOrderStaysLatchedUntilRecovery() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null)
        val tokenA = adapter.token(0)

        // Normal fatal order: FATAL, then the session end, no retry yet.
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)
        adapter.simulateSessionEnd("session A gone", session = tokenA)

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
        val tokenA = adapter.token(0)
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)

        backend.initialize()
        backend.start()
        manager.connect(null) // B
        val tokenB = adapter.token(1)
        adapter.simulateError("LINK_LOST", "again", CarLinkErrorSeverity.FATAL, session = tokenB)

        backend.initialize()
        backend.start()
        manager.connect(null) // C
        val tokenC = adapter.token(2)
        val heldBefore = manager.resourcesHeldBy(CarLinkProjectionBackend.ID)

        // Two dead sessions each owe a trailing end; none may hurt C.
        adapter.simulateSessionEnd("late end A", session = tokenA)
        adapter.simulateSessionEnd("late end B", session = tokenB)

        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertEquals(heldBefore, manager.resourcesHeldBy(CarLinkProjectionBackend.ID))

        // C's own end is processed normally — proving no counting leftover.
        adapter.simulateSessionEnd("C ends", session = tokenC)
        assertEquals(ProjectionState.Ready, backend.state)
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
        val tokenA = adapter.token(0)
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)

        // Explicit reconnect to the Wi-Fi phone after the fatal error.
        backend.initialize()
        backend.start()
        val wifiPhone = backend.discoveredDevices().first {
            it.transport == com.shilapi.xcertplay.projection.ProjectionTransport.WIFI
        }
        manager.connect(wifiPhone)

        // WIFI+AUDIO claimed, and a stale end cannot change any of that.
        adapter.simulateSessionEnd("late end A", session = tokenA)
        assertEquals(
            setOf(
                com.shilapi.xcertplay.projection.ProjectionResource.WIFI,
                com.shilapi.xcertplay.projection.ProjectionResource.AUDIO,
            ),
            manager.resourcesHeldBy(CarLinkProjectionBackend.ID),
        )
        assertEquals(ProjectionState.Connected, backend.state)
    }

    // ---- Test A: B's normal end works even while A's end is still owed ----

    @Test
    fun testBNormalEndWorksWithoutAEnd() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null) // A
        val tokenA = adapter.token(0)
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)

        backend.initialize()
        backend.start()
        manager.connect(null) // B
        val tokenB = adapter.token(1)
        assertEquals(ProjectionState.Connected, backend.state)

        // B ends normally while A's late end has NOT arrived yet.
        adapter.simulateSessionEnd("B ends normally", session = tokenB)

        assertEquals(ProjectionState.Ready, backend.state)
        assertFalse(backend.isSessionActive)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
    }

    // ---- Test B: A FATAL → B connected → B explicit disconnect → C → late A end ----

    @Test
    fun testLateAEndAfterCConnectsLeavesCAlone() {
        var closes = 0
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter, onClose = { closes++ })
        backend.initialize()
        backend.start()
        manager.connect(null) // A
        val tokenA = adapter.token(0)
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL, session = tokenA)

        backend.initialize()
        backend.start()
        manager.connect(null) // B
        val tokenB = adapter.token(1)
        manager.disconnectBackend(CarLinkProjectionBackend.ID) // B explicit disconnect
        assertEquals(ProjectionState.Ready, backend.state)

        backend.initialize()
        backend.start()
        manager.connect(null) // C
        val closesAfterC = closes
        val held = manager.resourcesHeldBy(CarLinkProjectionBackend.ID)

        // A's late end finally arrives after C connected.
        adapter.simulateSessionEnd("late end A", session = tokenA)

        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
        assertEquals(held, manager.resourcesHeldBy(CarLinkProjectionBackend.ID))
        assertEquals("C's media must stay open", closesAfterC, closes)
    }
}
