// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.receiver.transport.instant.CarLifeWirelessBootstrap
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 9.2W-B1: REAL [CarLifeV2Provider] Bluetooth bootstrap seam.
 *
 * Properties under test (sections 14/15/20/27):
 *  - BT_HOTSPOT defers facade.connect() (RFCOMM first, TCP only to the
 *    PROTOCOL-PROVIDED phone IP)
 *  - bootstrap callbacks are permanently bound to their attempt's token: a
 *    late RFCOMM callback of session A can never mutate session B
 *  - connectWirelessToPhoneIp is token-fenced and runs once per real IP
 *  - the session-scoped bootstrap handle dies with its attempt
 *  - probe stages use the BT_* constants for BT attempts and stay WIFI_* for
 *    the legacy AP path
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeBtProviderFencingTest {

    private class ScriptedSdk : CarLifeReceiverFacade {
        val calls = mutableListOf<String>()
        var probe: WirlessTransportProbe? = null
        var bootstrapListener: CarLifeWirelessBootstrap.Listener? = null
        var bootstrapClosed = false

        override fun addConnectionListener(listener: ConnectionChangeListener) {}
        override fun removeConnectionListener(listener: ConnectionChangeListener) {}
        override fun addProgressListener(listener: ConnectProgressListener) {}
        override fun removeProgressListener(listener: ConnectProgressListener) {}
        override fun connect() {
            calls.add("connect")
        }

        override fun stopConnect() {}
        override fun shutdown() {}
        override fun connectionState(): Int = 0
        override fun protocolVersion(): Int = 4
        override fun carlifeVersion(): Int = 0
        override fun usbDeviceSummaries(): List<String> = emptyList()
        override fun configureConnectTypeWithoutStarting(type: Int) {
            calls.add("configure:$type")
        }

        override fun setTransportProbeListener(listener: WirlessTransportProbe?) {
            probe = listener
        }

        override fun listBondedBluetoothNames(): List<String> = listOf("vivo X", "Mi Phone")
        override fun hasBluetoothConnectPermission(): Boolean = true

        override fun startBluetoothBootstrap(
            targetName: String,
            listener: CarLifeWirelessBootstrap.Listener,
        ): AutoCloseable {
            calls.add("bt-start:$targetName")
            bootstrapListener = listener
            return AutoCloseable {
                bootstrapClosed = true
                calls.add("bt-closed")
            }
        }

        override fun connectWirelessToPhoneIp(ip: String) {
            calls.add("wireless-ip:$ip")
        }
    }

    private class Harness {
        val sdk = ScriptedSdk()
        val provider = CarLifeV2Provider { _, _ -> sdk }

        init {
            provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        }
    }

    @Test
    fun btHotspotDefersTransportConnectUntilTheProtocolIpArrives() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.BT_HOTSPOT) { }
        // RFCOMM bootstrap first - the transport NEVER connects speculatively.
        assertFalse(h.sdk.calls.contains("connect"))
        assertTrue(h.sdk.calls.contains("configure:${CarLifeContext.CONNECTION_TYPE_HOTSPOT}"))

        // The protocol-provided IP continues to the existing TCP channel set.
        assertTrue(h.provider.connectWirelessToPhoneIp(CarLifeSessionToken(1), "192.168.43.5"))
        assertEquals(listOf("wireless-ip:192.168.43.5"), h.sdk.calls.filter { it.startsWith("wireless-ip") })
    }

    @Test
    fun connectWirelessToPhoneIpRejectsStaleToken() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.BT_HOTSPOT) { }
        assertFalse(h.provider.connectWirelessToPhoneIp(CarLifeSessionToken(999), "10.0.0.1"))
        assertFalse(h.sdk.calls.any { it.startsWith("wireless-ip") })
    }

    @Test
    fun bootstrapCallbacksAreBoundToTheirAttemptToken() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val eventsA = mutableListOf<CarLifeBootstrapEvent>()
        h.provider.startConnection(tokenA, CarLifeTransport.BT_HOTSPOT) { }
        h.provider.startBluetoothBootstrap(tokenA, "vivo X") { eventsA.add(it) }
        val listenerA = h.sdk.bootstrapListener!!

        listenerA.onBluetoothRfcommConnected("vivo X")
        assertEquals(tokenA, (eventsA.single() as CarLifeBootstrapEvent.RfcommConnected).session)

        // A dies, B starts: A's bootstrap listener must be dead.
        h.provider.stopConnection(tokenA)
        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeBootstrapEvent>()
        h.provider.startConnection(tokenB, CarLifeTransport.BT_HOTSPOT) { }
        h.provider.startBluetoothBootstrap(tokenB, "vivo X") { eventsB.add(it) }
        val listenerB = h.sdk.bootstrapListener!!

        listenerA.onBluetoothRfcommConnected("vivo X") // late A RFCOMM callback
        listenerA.onWirelessIp("192.168.43.99")
        assertTrue(eventsB.isEmpty())
        assertEquals(1, eventsA.size)

        listenerB.onWirelessIp("192.168.43.5") // B's own session
        val event = eventsB.single() as CarLifeBootstrapEvent.WirelessIp
        assertEquals(tokenB, event.session)
        assertEquals("192.168.43.5", event.ip)
    }

    @Test
    fun stoppingTheAttemptClosesTheBootstrapHandle() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token, CarLifeTransport.BT_HOTSPOT) { }
        h.provider.startBluetoothBootstrap(token, "vivo X") { }
        assertFalse(h.sdk.bootstrapClosed)
        h.provider.stopConnection(token)
        assertTrue("RFCOMM bootstrap must die with its attempt", h.sdk.bootstrapClosed)
    }

    @Test
    fun bootstrapOfStaleTokenIsRejected() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.BT_HOTSPOT) { }
        h.provider.startBluetoothBootstrap(CarLifeSessionToken(999), "vivo X") { }
        assertFalse(h.sdk.calls.any { it.startsWith("bt-start:") })
    }

    @Test
    fun btProbeStagesUseBtConstantsAndLegacyApStaysWifi() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val eventsA = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenA, CarLifeTransport.BT_HOTSPOT) { eventsA.add(it) }
        h.sdk.probe!!.onTcpConnecting("192.168.43.5")
        h.sdk.probe!!.onTransportAttached("192.168.43.5")
        assertEquals(
            listOf(CarLifeProbeState.BT_TCP_CONNECTING, CarLifeProbeState.BT_TRANSPORT_ATTACHED),
            eventsA.filterIsInstance<CarLifeConnectionEvent.WirelessStage>().map { it.stage },
        )

        // Legacy AP path keeps its WIFI_* stages (regression).
        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenB, CarLifeTransport.WIFI_AP) { eventsB.add(it) }
        h.sdk.probe!!.onTcpConnecting("192.168.43.5")
        h.sdk.probe!!.onTransportAttached("192.168.43.5")
        assertEquals(
            listOf(CarLifeProbeState.WIFI_TCP_CONNECTING, CarLifeProbeState.WIFI_TRANSPORT_ATTACHED),
            eventsB.filterIsInstance<CarLifeConnectionEvent.WirelessStage>().map { it.stage },
        )
    }

    @Test
    fun targetInfoMessageRecordsWifiDirectRequired() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        val events = mutableListOf<CarLifeBootstrapEvent>()
        h.provider.startConnection(token, CarLifeTransport.BT_HOTSPOT) { }
        h.provider.startBluetoothBootstrap(token, "vivo X") { events.add(it) }
        h.sdk.bootstrapListener!!.onBootstrapMessage("target-info-request")
        assertTrue(events.any { it is CarLifeBootstrapEvent.Message && it.kind == "target-info-request" })
        assertEquals("WIFI_DIRECT_REQUIRED", h.provider.diagnostics().modernWirelessPath)
        assertEquals(true, h.provider.diagnostics().btTargetInfoRequest)
    }

    @Test
    fun diagnosticsExposeNamesOnly() {
        val h = Harness()
        assertEquals(listOf("vivo X", "Mi Phone"), h.provider.listBondedBluetoothNames())
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token, CarLifeTransport.BT_HOTSPOT) { }
        h.provider.startBluetoothBootstrap(token, "vivo X") { }
        val diag = h.provider.diagnostics()
        assertEquals("BT_HOTSPOT", diag.transport)
        assertEquals("vivo X", diag.btTarget)
        assertEquals("idle", diag.btRfcomm) // idle until real events
        assertFalse("MSG_WIRELESS_REQUEST_IP is never sent in B1", diag.btRequestIpSent)
    }
}
