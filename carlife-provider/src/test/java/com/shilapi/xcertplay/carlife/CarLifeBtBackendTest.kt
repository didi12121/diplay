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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9.2W-B1: modern wireless (BT_HOTSPOT) at the backend boundary.
 *
 * Properties under test (sections 7/11/25/35/36):
 *  - resources {WIFI, BLUETOOTH, AUDIO}; RFCOMM target = the request's device
 *    NAME, bound to the attempt; missing target -> BT_TARGET_NOT_SELECTED
 *  - bootstrap stage progression is diagnostic ONLY (ESTABLISHED = success)
 *  - INFO negotiation records advertisedType=HOTSPOT; TARGET_INFO_REQUEST
 *    records WIFI_DIRECT_REQUIRED and NEVER triggers a transport connect
 *  - a protocol-provided IP continues to the TCP channel set exactly once
 *  - precise timeout classification (BT_* / WIRELESS_INFO_* / PHONE_IP_* /
 *    WIFI_DIRECT_REQUIRED / TCP_CONNECT_FAILED) - never collapsed into OTHER
 *  - late bootstrap callbacks of a dead session never mutate the new one
 */
class CarLifeBtBackendTest {

    private class FakeProvider : CarLifeProvider {
        override val isAvailable = true
        val started = mutableListOf<Pair<CarLifeSessionToken, CarLifeTransport>>()
        val stopped = mutableListOf<CarLifeSessionToken>()
        val bootstraps = mutableListOf<Pair<CarLifeSessionToken, String>>()
        val wirelessIpCalls = mutableListOf<Pair<CarLifeSessionToken, String>>()
        var diag = CarLifeProviderDiagnostics()
        var connectWirelessResult = true
        private var listener: ((CarLifeConnectionEvent) -> Unit)? = null
        private var bootstrapListener: ((CarLifeBootstrapEvent) -> Unit)? = null

        override fun initialize(context: Context, config: CarLifeProviderConfig) {}
        override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) =
            startConnection(token, CarLifeTransport.USB_AOA, listener)

        override fun startConnection(
            token: CarLifeSessionToken,
            transport: CarLifeTransport,
            listener: (CarLifeConnectionEvent) -> Unit,
        ) {
            started.add(token to transport)
            this.listener = listener
            diag = CarLifeProviderDiagnostics(
                transport = transport.name,
                localIp = "192.168.43.10",
                btTarget = "vivo X",
                btBonded = "yes",
            )
        }

        override fun startBluetoothBootstrap(
            token: CarLifeSessionToken,
            targetBluetoothName: String,
            listener: (CarLifeBootstrapEvent) -> Unit,
        ) {
            bootstraps.add(token to targetBluetoothName)
            this.bootstrapListener = listener
        }

        override fun stopBluetoothBootstrap(token: CarLifeSessionToken) {}
        override fun stopConnection(token: CarLifeSessionToken) {
            stopped.add(token)
        }

        override fun connectWirelessToPhoneIp(token: CarLifeSessionToken, ip: String): Boolean {
            wirelessIpCalls.add(token to ip)
            return connectWirelessResult
        }

        override fun diagnostics(): CarLifeProviderDiagnostics = diag
        override fun dispose() {}

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)

        fun emitBootstrap(event: CarLifeBootstrapEvent) {
            // Mirror the real provider: bootstrap events evolve the attempt's
            // diagnostics (which the backend syncs from).
            diag = when (event) {
                is CarLifeBootstrapEvent.TargetFound -> diag.copy(btBonded = "yes")
                is CarLifeBootstrapEvent.RfcommConnecting -> diag.copy(btRfcomm = "connecting")
                is CarLifeBootstrapEvent.RfcommConnected -> diag.copy(btRfcomm = "connected", btBonded = "yes")
                is CarLifeBootstrapEvent.Message -> when (event.kind) {
                    "info-request" -> diag.copy(btInfoRequest = true)
                    "info-response-sent" -> diag.copy(btInfoResponse = true, btAdvertisedType = "HOTSPOT")
                    "target-info-request" -> diag.copy(
                        btTargetInfoRequest = true,
                        modernWirelessPath = "WIFI_DIRECT_REQUIRED",
                    )
                    else -> diag
                }
                is CarLifeBootstrapEvent.WirelessIp -> diag.copy(btResponseIpReceived = true, phoneIp = event.ip)
                is CarLifeBootstrapEvent.Failed -> diag.copy(
                    btRfcomm = if (event.reason.startsWith("BT_RFCOMM")) "error" else diag.btRfcomm,
                    btBonded = if (event.reason == "BT_TARGET_NOT_BONDED") "no" else diag.btBonded,
                )
                else -> diag
            }
            bootstrapListener?.invoke(event)
        }
    }

    private class CapturingScheduler : CarLifeProbeTimeoutScheduler {
        var pending: (() -> Unit)? = null

        override fun schedule(timeoutMillis: Long, onTimeout: () -> Unit): AutoCloseable {
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

        fun connectBt(targetName: String = "vivo X"): CarLifeSessionToken {
            backend.connect(
                ProjectionDevice(
                    "carlife-bt", targetName, CarLifeProjectionBackend.ID,
                    transport = ProjectionTransport.BLUETOOTH,
                ),
            )
            return provider.started.lastOrNull()?.first ?: CarLifeSessionToken(-1)
        }
    }

    // ---- Resources + request (sections 7/31) ----

    @Test
    fun btRequestClaimsWifiBluetoothAndAudio() {
        val h = Harness()
        val device = ProjectionDevice(
            "d", "vivo X", CarLifeProjectionBackend.ID, transport = ProjectionTransport.BLUETOOTH,
        )
        val resources = h.backend.requiredResourcesFor(device)
        assertEquals(setOf(ProjectionResource.WIFI, ProjectionResource.BLUETOOTH, ProjectionResource.AUDIO), resources)
        assertFalse(resources.contains(ProjectionResource.USB))
    }

    @Test
    fun btConnectBindsTheDeviceNameAsRfcommTarget() {
        val h = Harness()
        val token = h.connectBt("vivo X")
        assertEquals(listOf(token to "vivo X"), h.provider.bootstraps)
        assertEquals(CarLifeTransport.BT_HOTSPOT, h.provider.started.single().second)
        assertEquals(CarLifeProbeState.BT_TARGET_REQUIRED, h.backend.probeReport().state)
    }

    @Test
    fun missingTargetNameFailsWithBtTargetNotSelected() {
        val h = Harness()
        h.backend.connect(
            ProjectionDevice("d", "  ", CarLifeProjectionBackend.ID, transport = ProjectionTransport.BLUETOOTH),
        )
        assertTrue(h.provider.bootstraps.isEmpty())
        assertTrue(h.backend.state is ProjectionState.Error)
        assertEquals(CarLifeBlocker.BT_TARGET_NOT_SELECTED, h.backend.probeReport().blocker)
    }

    // ---- Stage progression (section 25) ----

    @Test
    fun bootstrapStagesProgressButEstablishedStaysTheOnlySuccess() {
        val h = Harness()
        val token = h.connectBt()

        h.provider.emitBootstrap(CarLifeBootstrapEvent.Searching(token, "vivo X"))
        assertEquals(CarLifeProbeState.BT_TARGET_REQUIRED, h.backend.probeReport().state)
        h.provider.emitBootstrap(CarLifeBootstrapEvent.TargetFound(token, "vivo X"))
        assertEquals(CarLifeProbeState.BT_TARGET_FOUND, h.backend.probeReport().state)
        h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnecting(token, "vivo X"))
        assertEquals(CarLifeProbeState.BT_RFCOMM_CONNECTING, h.backend.probeReport().state)
        h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(token, "vivo X"))
        assertEquals(CarLifeProbeState.BT_RFCOMM_CONNECTED, h.backend.probeReport().state)
        assertEquals("connected", h.backend.probeReport().btRfcomm)
        assertEquals(ProjectionState.Connecting, h.backend.state) // NOT success

        h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-request"))
        assertEquals(CarLifeProbeState.BT_WAITING_WIRELESS_INFO, h.backend.probeReport().state)
        assertTrue(h.backend.probeReport().btInfoRequest)
        h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-response-sent"))
        assertEquals(CarLifeProbeState.BT_WIRELESS_INFO_NEGOTIATED, h.backend.probeReport().state)
        assertEquals("HOTSPOT", h.backend.probeReport().btAdvertisedType)
        assertTrue(h.backend.probeReport().btInfoResponse)

        h.provider.emit(CarLifeConnectionEvent.Established(token))
        assertEquals(ProjectionState.Connected, h.backend.state)
        assertTrue(h.backend.isSessionActive)
    }

    @Test
    fun targetInfoRequestRecordsWifiDirectRequiredWithoutTransportConnect() {
        val h = Harness()
        val token = h.connectBt()
        h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "target-info-request"))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.BT_TARGET_INFO_REQUESTED, report.state)
        assertEquals("WIFI_DIRECT_REQUIRED", report.modernWirelessPath)
        assertTrue(report.btTargetInfoRequest)
        // Never faked target data, never started a transport / P2P.
        assertTrue(h.provider.wirelessIpCalls.isEmpty())
    }

    @Test
    fun protocolProvidedIpContinuesToTcpExactlyOnce() {
        val h = Harness()
        val token = h.connectBt()
        h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "response-ip"))
        assertEquals(CarLifeProbeState.BT_WAITING_PHONE_IP, h.backend.probeReport().state)
        h.provider.emitBootstrap(CarLifeBootstrapEvent.WirelessIp(token, "192.168.43.5"))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.BT_PHONE_IP_RECEIVED, report.state)
        assertEquals("192.168.43.5", report.phoneIp)
        assertTrue(report.btResponseIpReceived)
        // Exactly one WirlessConnector continuation, with the PROTOCOL IP.
        assertEquals(listOf(token to "192.168.43.5"), h.provider.wirelessIpCalls)
    }

    // ---- Failure classification (section 35) ----

    @Test
    fun bootstrapFailureIsClassifiedPrecisely() {
        val h = Harness()
        val token = h.connectBt()
        h.provider.emitBootstrap(CarLifeBootstrapEvent.Failed(token, "BT_RFCOMM_CONNECT_FAILED"))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.ERROR, report.state)
        assertEquals(CarLifeBlocker.BT_RFCOMM_CONNECT_FAILED, report.blocker)
        assertEquals("BT_RFCOMM_CONNECT_FAILED", report.lastError)
        assertEquals(ProjectionErrorCode.TRANSPORT_ERROR, (h.backend.state as ProjectionState.Error).code)
    }

    @Test
    fun staleBootstrapCallbackCannotMutateTheNewSession() {
        val h = Harness()
        val tokenA = h.connectBt()
        h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(tokenA, "vivo X"))
        h.provider.emit(CarLifeConnectionEvent.Detached(tokenA))

        val tokenB = h.connectBt()
        // Late RFCOMM callback of dead session A.
        h.provider.emitBootstrap(CarLifeBootstrapEvent.Failed(tokenA, "BT_RFCOMM_CONNECT_FAILED"))
        assertEquals(CarLifeProbeState.BT_TARGET_REQUIRED, h.backend.probeReport().state)
        assertTrue(h.backend.probeReport().lastError!!.startsWith("stale-bt-callback-ignored"))

        h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(tokenB, "vivo X"))
        assertEquals(CarLifeProbeState.BT_RFCOMM_CONNECTED, h.backend.probeReport().state)
    }

    @Test
    fun btAttachedReportsBtTransportAttached() {
        val h = Harness()
        val token = h.connectBt()
        h.provider.emit(CarLifeConnectionEvent.Attached(token))
        val report = h.backend.probeReport()
        assertEquals(CarLifeProbeState.BT_TRANSPORT_ATTACHED, report.state)
        assertEquals("idle", report.aoaState) // never AOA for a wireless transport
    }

    // ---- Timeout classification ----

    private fun classifyAfterTimeout(
        configure: (Harness, CarLifeSessionToken) -> Unit,
    ): CarLifeBlocker {
        val h = Harness()
        val token = h.connectBt()
        configure(h, token)
        h.scheduler.fire()
        return h.backend.probeReport().blocker
    }

    @Test
    fun timeoutWithoutRfcommIsRfcommConnectFailed() {
        assertEquals(
            CarLifeBlocker.BT_RFCOMM_CONNECT_FAILED,
            classifyAfterTimeout { _, _ -> },
        )
    }

    @Test
    fun timeoutWithSilentRfcommIsBootstrapSilent() {
        assertEquals(
            CarLifeBlocker.BT_BOOTSTRAP_SILENT,
            classifyAfterTimeout { h, token ->
                h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(token, "vivo X"))
            },
        )
    }

    @Test
    fun timeoutWithoutInfoResponseIsNegotiationFailed() {
        assertEquals(
            CarLifeBlocker.WIRELESS_INFO_NEGOTIATION_FAILED,
            classifyAfterTimeout { h, token ->
                h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(token, "vivo X"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-request"))
            },
        )
    }

    @Test
    fun timeoutWithoutPhoneIpIsPhoneIpNotProvided() {
        assertEquals(
            CarLifeBlocker.PHONE_IP_NOT_PROVIDED,
            classifyAfterTimeout { h, token ->
                h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(token, "vivo X"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-request"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-response-sent"))
            },
        )
    }

    @Test
    fun timeoutWithWifiDirectPathIsWifiDirectRequired() {
        assertEquals(
            CarLifeBlocker.WIFI_DIRECT_REQUIRED,
            classifyAfterTimeout { h, token ->
                h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(token, "vivo X"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "target-info-request"))
            },
        )
    }

    @Test
    fun timeoutAfterPhoneIpIsTcpConnectFailed() {
        assertEquals(
            CarLifeBlocker.TCP_CONNECT_FAILED,
            classifyAfterTimeout { h, token ->
                h.provider.emitBootstrap(CarLifeBootstrapEvent.RfcommConnected(token, "vivo X"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-request"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.Message(token, "info-response-sent"))
                h.provider.emitBootstrap(CarLifeBootstrapEvent.WirelessIp(token, "192.168.43.5"))
            },
        )
    }

    @Test
    fun timeoutWithoutPermissionIsBtPermission() {
        val h = Harness()
        val token = h.connectBt()
        h.provider.diag = h.provider.diag.copy(btPermission = false)
        h.scheduler.fire()
        assertEquals(CarLifeBlocker.BT_PERMISSION, h.backend.probeReport().blocker)
    }
}
