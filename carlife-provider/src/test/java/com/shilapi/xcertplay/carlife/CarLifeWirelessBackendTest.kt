// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import android.content.Context
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9.2W-A: CarLife wireless AP connect flow at the backend boundary.
 *
 * Properties under test:
 *  - transport-scoped resources: USB_AOA -> {USB, AUDIO}, WIFI_AP ->
 *    {WIFI, AUDIO} (no USB for wireless, no BLUETOOTH for WIFI_AP)
 *  - deterministic transport from the connect REQUEST (never stale, never a
 *    silent USB <-> wireless conversion); WIFI_DIRECT rejected as disabled
 *  - exclusive CarLife transport (no concurrent AOA + AP attempts)
 *  - wireless probe stages are diagnostic ONLY; CONNECTION_ESTABLISHED stays
 *    the only success criterion
 *  - precise wireless timeout blockers (NO_NETWORK / NO_DISCOVERY_PACKET /
 *    PHONE_DISCOVERED_TCP_FAILED)
 *  - session fencing: a stale wireless stage of session A never mutates B
 *  - USB regression: the wired path keeps its Phase 9.1 behavior
 */
class CarLifeWirelessBackendTest {

    private class FakeProvider : CarLifeProvider {
        override val isAvailable: Boolean get() = available
        var available = true
        var throwOnStart: Exception? = null
        val started = mutableListOf<Pair<CarLifeSessionToken, CarLifeTransport>>()
        val stopped = mutableListOf<CarLifeSessionToken>()
        var diag = CarLifeProviderDiagnostics()
        private var listener: ((CarLifeConnectionEvent) -> Unit)? = null

        override fun initialize(context: Context, config: CarLifeProviderConfig) {}
        override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) =
            startConnection(token, CarLifeTransport.USB_AOA, listener)

        override fun startConnection(
            token: CarLifeSessionToken,
            transport: CarLifeTransport,
            listener: (CarLifeConnectionEvent) -> Unit,
        ) {
            throwOnStart?.let { throw it }
            started.add(token to transport)
            this.listener = listener
            // Realistic LAN diagnostics for this attempt (tests override).
            diag = CarLifeProviderDiagnostics(transport = transport.name, localIp = "192.168.43.1")
        }

        override fun stopConnection(token: CarLifeSessionToken) {
            stopped.add(token)
        }

        override fun diagnostics(): CarLifeProviderDiagnostics = diag
        override fun dispose() {}

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)
    }

    private class CapturingScheduler : CarLifeProbeTimeoutScheduler {
        var pending: (() -> Unit)? = null
        var lastTimeoutMillis: Long = 0

        override fun schedule(timeoutMillis: Long, onTimeout: () -> Unit): AutoCloseable {
            lastTimeoutMillis = timeoutMillis
            pending = onTimeout
            return AutoCloseable { pending = null }
        }

        fun fire() {
            val action = pending ?: return
            pending = null
            action()
        }
    }

    private class Harness {
        val provider = FakeProvider()
        val scheduler = CapturingScheduler()
        val backend = CarLifeProjectionBackend(provider, scheduler, 15_000)

        fun connect(transport: ProjectionTransport?): CarLifeSessionToken {
            val device = transport?.let {
                ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID, transport = it)
            }
            backend.connect(device)
            return provider.started.last().first
        }
    }

    private val wifiDevice = ProjectionDevice(
        "carlife-wifi", "Android phone (wireless)", CarLifeProjectionBackend.ID,
        transport = ProjectionTransport.WIFI,
    )

    // ---- Transport-scoped resources (section 25) ----

    @Test
    fun usbAoaRequestClaimsUsbAndAudio() {
        val h = Harness()
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            h.backend.requiredResourcesFor(null),
        )
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            h.backend.requiredResourcesFor(
                ProjectionDevice("d", "p", CarLifeProjectionBackend.ID, transport = ProjectionTransport.USB),
            ),
        )
    }

    @Test
    fun wifiApRequestClaimsWifiAndAudioNeverUsbOrBluetooth() {
        val h = Harness()
        val resources = h.backend.requiredResourcesFor(wifiDevice)
        assertEquals(setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO), resources)
        assertFalse(resources.contains(ProjectionResource.USB))
        assertFalse(resources.contains(ProjectionResource.BLUETOOTH))
    }

    // ---- Deterministic transport requests (section 8) ----

    @Test
    fun wirelessRequestStartsWifiApAttempt() {
        val h = Harness()
        h.backend.connect(wifiDevice)
        assertEquals(CarLifeTransport.WIFI_AP, h.provider.started.single().second)
        assertEquals("WIFI_AP", h.backend.probeReport().transport)
        assertEquals(CarLifeProbeState.WIFI_WAITING_NETWORK, h.backend.probeReport().state)
        assertEquals(CarLifeBlocker.NO_DISCOVERY_PACKET, h.backend.probeReport().blocker)
    }

    @Test
    fun legacyRequestStillStartsUsbAoaAttempt() {
        val h = Harness()
        h.backend.connect(null)
        assertEquals(CarLifeTransport.USB_AOA, h.provider.started.single().second)
        // Phase 9.1 state mapping unchanged: AOA switch requested after start.
        assertEquals(CarLifeProbeState.AOA_SWITCH_REQUESTED, h.backend.probeReport().state)
    }

    @Test
    fun wifiDirectRequestIsRejectedAsDisabled() {
        val h = Harness()
        val device = ProjectionDevice(
            "d", "p", CarLifeProjectionBackend.ID, transport = ProjectionTransport.WIFI_DIRECT,
        )
        h.backend.connect(device)
        assertTrue(h.provider.started.isEmpty())
        val state = h.backend.state
        assertTrue(state is ProjectionState.Error)
        val error = state as ProjectionState.Error
        assertEquals(ProjectionErrorCode.PROVIDER_UNAVAILABLE, error.code)
        assertTrue(error.message.contains("EXPERIMENTAL_DISABLED"))
    }

    @Test
    fun bluetoothRequestIsRejected() {
        val h = Harness()
        val device = ProjectionDevice(
            "d", "p", CarLifeProjectionBackend.ID, transport = ProjectionTransport.BLUETOOTH,
        )
        h.backend.connect(device)
        assertTrue(h.provider.started.isEmpty())
        assertTrue(h.backend.state is ProjectionState.Error)
        assertTrue(h.backend.requiredResourcesFor(device).isEmpty())
    }

    // ---- Exclusive CarLife transport (section 23) ----

    @Test
    fun mixedTransportsAreRejectedWhileAnAttemptIsLive() {
        val h = Harness()
        h.backend.connect(wifiDevice)
        assertEquals(1, h.provider.started.size)

        // USB start while the wireless attempt is live: rejected, no second attempt.
        h.backend.connect(
            ProjectionDevice("d", "p", CarLifeProjectionBackend.ID, transport = ProjectionTransport.USB),
        )
        assertEquals(1, h.provider.started.size)
        assertEquals("transport-busy:WIFI_AP", h.backend.probeReport().lastError)
    }

    // ---- Wireless stages are diagnostic only (sections 17/18) ----

    @Test
    fun wirelessStagesProgressButEstablishedStaysTheOnlySuccess() {
        val h = Harness()
        val token = h.connect(ProjectionTransport.WIFI)

        h.provider.emit(CarLifeConnectionEvent.WirelessStage(token, CarLifeProbeState.WIFI_UDP_LISTENING))
        assertEquals(CarLifeProbeState.WIFI_UDP_LISTENING, h.backend.probeReport().state)
        assertEquals(ProjectionState.Connecting, h.backend.state) // NOT success

        h.provider.emit(CarLifeConnectionEvent.WirelessStage(token, CarLifeProbeState.WIFI_PHONE_DISCOVERED))
        h.provider.emit(CarLifeConnectionEvent.WirelessStage(token, CarLifeProbeState.WIFI_TCP_CONNECTING))
        h.provider.emit(CarLifeConnectionEvent.WirelessStage(token, CarLifeProbeState.WIFI_TRANSPORT_ATTACHED))
        assertEquals(CarLifeProbeState.WIFI_TRANSPORT_ATTACHED, h.backend.probeReport().state)
        assertEquals(ProjectionState.Connecting, h.backend.state) // still NOT success

        h.provider.emit(CarLifeConnectionEvent.Established(token))
        assertEquals(ProjectionState.Connected, h.backend.state)
        assertTrue(h.backend.isSessionActive)
    }

    // ---- Timeout classification (section 21) ----

    @Test
    fun wirelessTimeoutWithoutDiscoveryIsNoDiscoveryPacket() {
        val h = Harness()
        h.connect(ProjectionTransport.WIFI)
        h.scheduler.fire()
        assertEquals(CarLifeBlocker.NO_DISCOVERY_PACKET, h.backend.probeReport().blocker)
        assertEquals(ProjectionErrorCode.TIMEOUT, (h.backend.state as ProjectionState.Error).code)
    }

    @Test
    fun wirelessTimeoutWithoutLocalIpIsNoNetwork() {
        val h = Harness()
        h.connect(ProjectionTransport.WIFI)
        h.provider.diag = CarLifeProviderDiagnostics(transport = "WIFI_AP", localIp = null)
        h.scheduler.fire()
        assertEquals(CarLifeBlocker.NO_NETWORK, h.backend.probeReport().blocker)
    }

    @Test
    fun wirelessTimeoutAfterDiscoveryIsTcpFailed() {
        val h = Harness()
        h.connect(ProjectionTransport.WIFI)
        h.provider.diag = CarLifeProviderDiagnostics(
            transport = "WIFI_AP",
            localIp = "192.168.43.1",
            phoneIp = "192.168.43.2",
            udpPacketsReceived = 3,
        )
        h.provider.emit(CarLifeConnectionEvent.WirelessStage(h.provider.started.single().first, CarLifeProbeState.WIFI_PHONE_DISCOVERED))
        h.scheduler.fire()
        assertEquals(CarLifeBlocker.PHONE_DISCOVERED_TCP_FAILED, h.backend.probeReport().blocker)
    }

    @Test
    fun wirelessProbeUsesTheLongerDiscoveryTimeout() {
        val h = Harness()
        h.connect(ProjectionTransport.WIFI)
        assertEquals(CarLifeProjectionBackend.WIRELESS_PROBE_TIMEOUT_MILLIS, h.scheduler.lastTimeoutMillis)

        val h2 = Harness()
        h2.connect(ProjectionTransport.USB)
        assertEquals(CarLifeProjectionBackend.DEFAULT_PROBE_TIMEOUT_MILLIS, h2.scheduler.lastTimeoutMillis)
    }

    @Test
    fun usbTimeoutClassificationIsUnchanged() {
        val h = Harness()
        h.connect(ProjectionTransport.USB)
        h.scheduler.fire()
        assertEquals(CarLifeBlocker.NO_REAL_DEVICE, h.backend.probeReport().blocker)
    }

    // ---- Wireless session fencing (section 27) ----

    @Test
    fun staleWirelessStageOfSessionACannotMutateSessionB() {
        val h = Harness()
        val tokenA = h.connect(ProjectionTransport.WIFI)
        h.provider.emit(CarLifeConnectionEvent.WirelessStage(tokenA, CarLifeProbeState.WIFI_UDP_LISTENING))
        h.provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        val tokenB = h.connect(ProjectionTransport.WIFI)
        // Late stage of dead session A must not touch B's state.
        h.provider.emit(CarLifeConnectionEvent.WirelessStage(tokenA, CarLifeProbeState.WIFI_PHONE_DISCOVERED))
        assertEquals(CarLifeProbeState.WIFI_WAITING_NETWORK, h.backend.probeReport().state)

        h.provider.emit(CarLifeConnectionEvent.WirelessStage(tokenB, CarLifeProbeState.WIFI_UDP_LISTENING))
        assertEquals(CarLifeProbeState.WIFI_UDP_LISTENING, h.backend.probeReport().state)
    }

    @Test
    fun wirelessSessionEndStopsTheProviderAttempt() {
        // TRANSPORT OWNERSHIP: network sockets of a wireless attempt are
        // session-scoped and must not survive into the next session.
        val h = Harness()
        val token = h.connect(ProjectionTransport.WIFI)
        h.provider.emit(CarLifeConnectionEvent.Established(token))
        h.provider.emit(CarLifeConnectionEvent.Detached(token))
        assertEquals(listOf(token), h.provider.stopped)
    }

    // ---- USB regression (section 26: no mixed transports, USB unchanged) ----

    @Test
    fun usbSessionEndKeepsItsPhase91Lifecycle() {
        val h = Harness()
        val token = h.connect(ProjectionTransport.USB)
        h.provider.emit(CarLifeConnectionEvent.Established(token))
        h.provider.emit(CarLifeConnectionEvent.Detached(token))
        // Phase 9.1 behavior preserved: the backend does NOT stop the wired
        // attempt on detach (the next attempt fences it).
        assertTrue(h.provider.stopped.isEmpty())
        assertEquals(ProjectionState.Ready, h.backend.state)
    }

    @Test
    fun transportSwitchAcrossSessionsUsesFreshRequests() {
        val h = Harness()
        val tokenA = h.connect(ProjectionTransport.USB)
        h.provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        // WIFI_AP after USB: the new attempt's transport comes from THIS
        // request - never the previous session's.
        h.backend.connect(wifiDevice)
        assertEquals(CarLifeTransport.WIFI_AP, h.provider.started.last().second)
        assertEquals(CarLifeTransport.USB_AOA, h.provider.started.first().second)

        val tokenB = h.provider.started.last().first
        h.provider.emit(CarLifeConnectionEvent.Established(tokenB))
        assertEquals(ProjectionState.Connected, h.backend.state)
    }

    @Test
    fun wirelessConnectIsRejectedWhenProviderUnavailable() {
        val h = Harness()
        h.provider.available = false
        h.backend.connect(wifiDevice)
        assertTrue(h.provider.started.isEmpty())
        assertTrue(h.backend.state is ProjectionState.Error)
    }

    // ---- Startup failure releases manager resources (9.2W-A.1 section 4) ----

    @Test
    fun startupFailureReleasesManagerLeasesImmediately() {
        val manager = com.shilapi.xcertplay.projection.ProjectionManager()
        val provider = FakeProvider()
        provider.throwOnStart = IllegalStateException("configure failed")
        val backend = CarLifeProjectionBackend(provider, CapturingScheduler(), 15_000)
        manager.register(backend)

        // ProjectionManager acquires WIFI+AUDIO BEFORE backend.connect()...
        manager.connect(wifiDevice)
        // ...the synchronous transport failure must publish Error AND confirm
        // the session stopped so the leases are released immediately.
        assertTrue(backend.state is ProjectionState.Error)
        assertEquals(ProjectionErrorCode.CONNECT_FAILED, (backend.state as ProjectionState.Error).code)
        assertTrue("manager leases must not leak", manager.resourcesHeldBy(CarLifeProjectionBackend.ID).isEmpty())
        assertFalse(backend.isSessionActive)
    }

    @Test
    fun startupFailureFencesTheHalfCreatedAttempt() {
        val h = Harness()
        h.provider.throwOnStart = IllegalStateException("connect failed")
        h.backend.connect(wifiDevice)
        // The minted token is fenced through the provider even though the
        // attempt never completed (idempotent for fakes and real provider).
        assertTrue(h.backend.probeReport().lastError!!.isNotEmpty())
        assertTrue(h.backend.state is ProjectionState.Error)
        // A later attempt works normally.
        h.provider.throwOnStart = null
        h.backend.connect(wifiDevice)
        assertEquals(ProjectionState.Connecting, h.backend.state)
    }

    // ---- Transport-neutral attach semantics (9.2W-A.1 section 6) ----

    @Test
    fun wirelessAttachedReportsTransportNeutralState() {
        val h = Harness()
        val token = h.connect(ProjectionTransport.WIFI)
        h.provider.emit(CarLifeConnectionEvent.Attached(token))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.WIFI_TRANSPORT_ATTACHED, report.state)
        // Do NOT report AOA for a wireless transport.
        assertEquals("idle", report.aoaState)
    }

    @Test
    fun usbAttachedStillReportsAoaAttached() {
        val h = Harness()
        val token = h.connect(ProjectionTransport.USB)
        h.provider.emit(CarLifeConnectionEvent.Attached(token))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.AOA_ATTACHED, report.state)
        assertEquals("attached", report.aoaState)
    }

    @Test
    fun wirelessReattachedStaysTransportNeutral() {
        val h = Harness()
        val token = h.connect(ProjectionTransport.WIFI)
        h.provider.emit(CarLifeConnectionEvent.Reattached(token))
        assertEquals(CarLifeProbeState.WIFI_TRANSPORT_ATTACHED, h.backend.probeReport().state)
        assertEquals("idle", h.backend.probeReport().aoaState)
    }

    // ---- UDP bind failure is immediate and diagnostic (section 5) ----

    @Test
    fun udpBindFailureTerminatesImmediatelyWithoutTimeout() {
        val h = Harness()
        val token = h.connect(ProjectionTransport.WIFI)
        // The real transport reports the bind failure as an immediate Failed
        // event - the backend must terminate NOW, not fake a 90s discovery
        // timeout.
        h.provider.emit(CarLifeConnectionEvent.Failed(token, "UDP_BIND_FAILED:SocketException"))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.ERROR, report.state)
        assertEquals("UDP_BIND_FAILED:SocketException", report.lastError)
        assertEquals(ProjectionErrorCode.TRANSPORT_ERROR, (h.backend.state as ProjectionState.Error).code)
        assertFalse(h.backend.isSessionActive)
        // The watchdog was cancelled by the immediate terminal state - the
        // failure never waits for (or is misclassified by) the timeout.
        assertNull(h.scheduler.pending)
    }
}
