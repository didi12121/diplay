// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.projection.ProjectionBackend
import com.shilapi.xcertplay.projection.ProjectionCapabilities
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionResourceConflictException
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionStateStore
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLife wired Compatibility Probe — Phase 9.1 regression suite:
 * state mapping, USB resources, connect/disconnect lifecycle, precise failure
 * classification, single Connected report, stale-callback isolation and
 * bidirectional USB conflicts with CarPlay.
 */
class CarLifeBackendTest {

    /** Deterministic fake provider; delivers events under explicit tokens. */
    private class FakeProvider : CarLifeProvider {
        override val isAvailable = true
        val started = mutableListOf<CarLifeSessionToken>()
        val stopped = mutableListOf<CarLifeSessionToken>()
        private var listener: ((CarLifeConnectionEvent) -> Unit)? = null

        override fun initialize(context: android.content.Context, config: CarLifeProviderConfig) {}
        override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
            started.add(token)
            this.listener = listener
        }

        override fun stopConnection(token: CarLifeSessionToken) {
            stopped.add(token)
        }

        override fun dispose() {}

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)
    }

    /** Stand-in for the CarPlay backend during conflict tests. */
    private class FakeCarPlay : ProjectionBackend {
        override val id = "carplay"
        override val displayName = "Apple CarPlay"
        override val capabilities = ProjectionCapabilities()
        private val store = ProjectionStateStore("carplay")

        @Volatile
        var sessionActive = false

        override val state: ProjectionState get() = store.state
        override val isSessionActive: Boolean get() = sessionActive
        override val requiredResources: Set<ProjectionResource> =
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO)
        override fun addStateListener(listener: ProjectionStateListener) = store.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = store.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() {}
        override fun connect(device: ProjectionDevice?) {
            sessionActive = true
            store.publish(ProjectionState.Connected)
        }

        override fun disconnect(): Boolean {
            sessionActive = false
            store.publish(ProjectionState.Ready)
            return true
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = false
        override fun onKeyEvent(event: ProjectionKeyEvent) = false
        override fun close() {}
    }

    private fun backendWith(provider: FakeProvider = FakeProvider()): Pair<FakeProvider, CarLifeProjectionBackend> =
        provider to CarLifeProjectionBackend(provider)

    // ---- State mapping ----

    @Test
    fun stateMappingIsHonestAcrossTheProbe() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        assertEquals(ProjectionState.Connecting, backend.state)

        provider.emit(CarLifeConnectionEvent.Attached(provider.started.single()))
        assertEquals(ProjectionState.Connecting, backend.state)

        provider.emit(CarLifeConnectionEvent.Established(provider.started.single()))
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
    }

    @Test
    fun establishedReportsConnectedOnlyOnce() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        val token = provider.started.single()
        provider.emit(CarLifeConnectionEvent.Established(token))
        provider.emit(CarLifeConnectionEvent.Established(token))
        assertEquals(ProjectionState.Connected, backend.state)
    }

    @Test
    fun versionRejectedIsProtocolVersionBlocker() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        provider.emit(CarLifeConnectionEvent.VersionNotSupported(provider.started.single()))

        val state = backend.state
        assertTrue(state is ProjectionState.Error)
        assertEquals(ProjectionErrorCode.PROTOCOL_ERROR, (state as ProjectionState.Error).code)
        assertEquals(CarLifeProbeState.VERSION_REJECTED, backend.probeReport().state)
        assertEquals(CarLifeBlocker.PROTOCOL_VERSION, backend.probeReport().blocker)
    }

    @Test
    fun authFailedIsChannelOrAuthBlocker() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        provider.emit(CarLifeConnectionEvent.AuthFailed(provider.started.single()))

        val state = backend.state
        assertTrue(state is ProjectionState.Error)
        assertEquals(ProjectionErrorCode.AUTHENTICATION_FAILED, (state as ProjectionState.Error).code)
        assertEquals(CarLifeBlocker.CHANNEL_OR_AUTH, backend.probeReport().blocker)
    }

    @Test
    fun aoaAttachedAloneIsNotConnected() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        provider.emit(CarLifeConnectionEvent.Attached(provider.started.single()))
        // AOA attached is NOT success — only CONNECTION_ESTABLISHED is.
        assertFalse(backend.isSessionActive)
        assertTrue(backend.state is ProjectionState.Connecting)
        assertEquals(CarLifeProbeState.AOA_ATTACHED, backend.probeReport().state)
    }

    @Test
    fun detachedReturnsToReady() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        provider.emit(CarLifeConnectionEvent.Established(provider.started.single()))
        provider.emit(CarLifeConnectionEvent.Detached(provider.started.single()))
        assertEquals(ProjectionState.Ready, backend.state)
        assertFalse(backend.isSessionActive)
    }

    // ---- USB resources ----

    @Test
    fun carLifeDeclaresUsbAndAudio() {
        val (_, backend) = backendWith()
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            backend.requiredResources,
        )
    }

    @Test
    fun disconnectReleasesUsbAndAudio() {
        val manager = ProjectionManager()
        val provider = FakeProvider()
        val backend = CarLifeProjectionBackend(provider)
        manager.register(backend)
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.Established(provider.started.single()))
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            manager.resourcesHeldBy(CarLifeProjectionBackend.ID),
        )

        assertTrue(manager.disconnectBackend(CarLifeProjectionBackend.ID))
        assertTrue(manager.resourcesHeldBy(CarLifeProjectionBackend.ID).isEmpty())
    }

    // ---- Lifecycle ----

    @Test
    fun connectDisconnectLifecycleIsClean() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        val token = provider.started.single()
        provider.emit(CarLifeConnectionEvent.Established(token))
        assertTrue(backend.isSessionActive)

        assertTrue(backend.disconnect())
        assertEquals(listOf(token), provider.stopped)
        assertFalse(backend.isSessionActive)
        assertEquals(ProjectionState.Ready, backend.state)
    }

    // ---- Stale callback isolation ----

    @Test
    fun staleCallbacksOfOldSessionCannotPolluteNewSession() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        val tokenA = provider.started.single()
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))
        assertEquals(ProjectionState.Ready, backend.state)

        // New attempt B.
        backend.connect(null)
        val tokenB = provider.started.single { it != tokenA }

        // Late callbacks of dead session A.
        provider.emit(CarLifeConnectionEvent.Failed(tokenA, "late A failure"))
        provider.emit(CarLifeConnectionEvent.Detached(tokenA))
        provider.emit(CarLifeConnectionEvent.Established(tokenA))

        // B must be untouched (still Connecting, not error, not "connected by A").
        assertEquals(ProjectionState.Connecting, backend.state)
        assertFalse(backend.isSessionActive)

        // And B's own events still work.
        provider.emit(CarLifeConnectionEvent.Established(tokenB))
        assertEquals(ProjectionState.Connected, backend.state)
    }

    @Test
    fun lateEstablishedOfOldSessionDoesNotMarkConnected() {
        val (provider, backend) = backendWith()
        backend.connect(null)
        val tokenA = provider.started.single()
        provider.emit(CarLifeConnectionEvent.VersionNotSupported(tokenA))
        assertTrue(backend.state is ProjectionState.Error)

        backend.connect(null)
        val tokenB = provider.started.single { it != tokenA }

        // Late Established of dead attempt A must be ignored (identity check).
        provider.emit(CarLifeConnectionEvent.Established(tokenA))
        assertTrue(backend.state is ProjectionState.Connecting)

        // B's own Established is accepted — connected exactly once.
        provider.emit(CarLifeConnectionEvent.Established(tokenB))
        assertEquals(ProjectionState.Connected, backend.state)
    }

    // ---- USB conflicts with CarPlay (both directions) ----

    @Test
    fun carPlayLiveUsbRejectsCarLifeConnect() {
        val manager = ProjectionManager()
        val carPlay = FakeCarPlay()
        val provider = FakeProvider()
        val carLife = CarLifeProjectionBackend(provider)
        manager.register(carPlay)
        manager.register(carLife)
        manager.connect(ProjectionDevice("d", "iphone", "carplay"))
        assertTrue(carPlay.sessionActive)

        try {
            manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
            throw AssertionError("expected conflict")
        } catch (expected: ProjectionResourceConflictException) {
            // Expected: CarPlay owns USB.
        }
        assertTrue(provider.started.isEmpty())
    }

    @Test
    fun carLifeLiveUsbRejectsCarPlayConnect() {
        val manager = ProjectionManager()
        val carPlay = FakeCarPlay()
        val provider = FakeProvider()
        val carLife = CarLifeProjectionBackend(provider)
        manager.register(carPlay)
        manager.register(carLife)
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.Established(provider.started.single()))
        assertTrue(carLife.isSessionActive)

        try {
            manager.connect(ProjectionDevice("d", "iphone", "carplay"))
            throw AssertionError("expected conflict")
        } catch (expected: ProjectionResourceConflictException) {
            // Expected: CarLife owns USB.
        }
        assertFalse(carPlay.sessionActive)
    }

    // ---- Probe report sanity ----

    @Test
    fun probeReportStartsWithNoRealDevice() {
        val (_, backend) = backendWith()
        val report = backend.probeReport()
        assertEquals(CarLifeBlocker.NO_REAL_DEVICE, report.blocker)
        assertEquals(0, report.connectionState)
    }
}
