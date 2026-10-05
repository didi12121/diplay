// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 9.2W-A: REAL [CarLifeV2Provider] transport switching (not fakes).
 *
 * Properties under test (section 6/26):
 *  - transport reconfiguration uses the safe seam
 *    (`configureConnectTypeWithoutStarting`) — never the auto-connecting
 *    upstream `setConnectType()`
 *  - WIFI_AP configures CONNECTION_TYPE_HOTSPOT BEFORE connect; the AOA
 *    scanner is never started for a wireless attempt and vice versa
 *  - an unchanged transport is not reconfigured (the proven USB path keeps
 *    exactly its Phase 9.1 call sequence)
 *  - one process / one CarLife.init / one captured receiver is preserved
 *  - the wireless probe is session-fenced: a late callback of a dead attempt
 *    can never mutate the current attempt
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeV2ProviderTransportSwitchTest {

    /** Scripted SDK facade: records transport configuration + probe binding. */
    private class ScriptedSdk : CarLifeReceiverFacade {
        val calls = mutableListOf<String>()
        val probes = mutableListOf<WirlessTransportProbe?>()

        override fun addConnectionListener(listener: ConnectionChangeListener) {
            calls.add("register-connection")
        }

        override fun removeConnectionListener(listener: ConnectionChangeListener) {
            calls.add("unregister-connection")
        }

        override fun addProgressListener(listener: ConnectProgressListener) {}
        override fun removeProgressListener(listener: ConnectProgressListener) {}

        override fun connect() {
            calls.add("connect")
        }

        override fun stopConnect() {
            calls.add("stopConnect")
        }

        override fun shutdown() {
            calls.add("shutdown")
        }

        override fun connectionState(): Int = 0
        override fun protocolVersion(): Int = 4
        override fun carlifeVersion(): Int = 0
        override fun usbDeviceSummaries(): List<String> = emptyList()

        override fun configureConnectTypeWithoutStarting(type: Int) {
            calls.add("configure:$type")
        }

        override fun setTransportProbeListener(listener: WirlessTransportProbe?) {
            probes.add(listener)
        }
    }

    private class Harness {
        val sdk = ScriptedSdk()
        val provider = CarLifeV2Provider { _, _ -> sdk }

        init {
            provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        }
    }

    // ---- Safe transport reconfiguration (sections 5/6/26) ----

    @Test
    fun firstUsbAttemptDoesNotReconfigure() {
        // The captured receiver is already configured for AOA at CarLife.init:
        // the proven USB path keeps exactly its Phase 9.1 call sequence.
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.USB_AOA) { }
        assertTrue(h.sdk.calls.none { it.startsWith("configure:") })
        assertEquals("connect", h.sdk.calls.last())
    }

    @Test
    fun wirelessAttemptConfiguresHotspotBeforeConnect() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        // 0x0005 = upstream CONNECTION_TYPE_HOTSPOT (wire value verbatim).
        val configure = h.sdk.calls.indexOf("configure:${CarLifeContext.CONNECTION_TYPE_HOTSPOT}")
        assertTrue("transport must be configured", configure >= 0)
        assertTrue(
            "configuration must precede connect (no early network start)",
            configure < h.sdk.calls.indexOf("connect"),
        )
    }

    @Test
    fun switchingBackToUsbReconfiguresAoa() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        h.provider.stopConnection(CarLifeSessionToken(1))
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.USB_AOA) { }
        assertTrue(h.sdk.calls.contains("configure:${CarLifeContext.CONNECTION_TYPE_AOA}"))
    }

    @Test
    fun unchangedTransportIsNotReconfiguredPerAttempt() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        h.provider.stopConnection(CarLifeSessionToken(1))
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.WIFI_AP) { }
        assertEquals(
            1,
            h.sdk.calls.count { it == "configure:${CarLifeContext.CONNECTION_TYPE_HOTSPOT}" },
        )
        // Each attempt still gets exactly one connect.
        assertEquals(2, h.sdk.calls.count { it == "connect" })
    }

    @Test
    fun everySupersededAttemptIsFencedBeforeReconfiguration() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.USB_AOA) { }
        val shutdown = h.sdk.calls.indexOf("shutdown")
        val configure = h.sdk.calls.indexOf("configure:${CarLifeContext.CONNECTION_TYPE_AOA}")
        assertTrue("old transport must be fenced first", shutdown in 0 until configure)
    }

    // ---- One process / one init / one captured receiver (section 6) ----

    @Test
    fun transportSwitchingNeverReinitializes() {
        var factoryCalls = 0
        val sdk = ScriptedSdk()
        val provider = CarLifeV2Provider { _, _ -> factoryCalls++; sdk }
        provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.USB_AOA) { }
        provider.stopConnection(CarLifeSessionToken(1))
        provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.WIFI_AP) { }
        provider.stopConnection(CarLifeSessionToken(2))
        provider.startConnection(CarLifeSessionToken(3), CarLifeTransport.USB_AOA) { }
        assertEquals(1, factoryCalls)
    }

    // ---- Wireless probe session fencing (section 27) ----

    @Test
    fun probeIsBoundToTheArmedAttemptOnly() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val eventsA = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenA, CarLifeTransport.WIFI_AP) { eventsA.add(it) }
        val probeA = h.sdk.probes.last()

        // A's probe reports a stage -> tagged with A's token.
        probeA!!.onUdpListening(7999)
        val stage = eventsA.last() as CarLifeConnectionEvent.WirelessStage
        assertEquals(tokenA, stage.session)
        assertEquals(CarLifeProbeState.WIFI_UDP_LISTENING, stage.stage)

        // A dies, B starts: A's probe instance must be dead.
        h.provider.stopConnection(tokenA)
        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenB, CarLifeTransport.WIFI_AP) { eventsB.add(it) }
        val probeB = h.sdk.probes.last()

        probeA.onUdpPacketReceived("192.168.43.2", 1) // late A callback
        assertTrue(eventsB.isEmpty())
        assertTrue(eventsA.size == 1)

        probeB!!.onUdpPacketReceived("192.168.43.9", 1) // B's own callback
        assertEquals(tokenB, (eventsB.last() as CarLifeConnectionEvent.WirelessStage).session)
    }

    @Test
    fun probeListenerIsClearedWhenTheAttemptStops() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        h.provider.stopConnection(CarLifeSessionToken(1))
        assertNull(h.sdk.probes.last())
    }

    @Test
    fun wirelessDiagnosticsDieWithTheirAttempt() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        val probeA = h.sdk.probes.last()!!
        probeA.onUdpListening(7999)
        probeA.onUdpPacketReceived("192.168.43.2", 1)
        assertEquals("192.168.43.2", h.provider.diagnostics().phoneIp)

        h.provider.stopConnection(CarLifeSessionToken(1))
        // Attempt gone: its diagnostics go with it - never leak into the next.
        assertEquals("USB_AOA", h.provider.diagnostics().transport)
        assertFalse(h.provider.diagnostics().udpListening)
        assertNull(h.provider.diagnostics().phoneIp)
    }

    @Test
    fun diagnosticsReportWirelessAttemptTransport() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        assertEquals("WIFI_AP", h.provider.diagnostics().transport)
        val probe = h.sdk.probes.last()!!
        probe.onUdpListening(7999)
        probe.onUdpPacketReceived("192.168.43.2", 2)
        probe.onTcpChannelState(1, 7240, "connected", null)
        probe.onTransportAttached("192.168.43.2")
        val diag = h.provider.diagnostics()
        assertEquals("WIFI_AP", diag.transport)
        assertTrue(diag.udpListening)
        assertEquals(7999, diag.udpPort)
        assertEquals("192.168.43.2", diag.phoneIp)
        assertEquals(2, diag.udpPacketsReceived)
        assertEquals(mapOf("cmd" to "connected"), diag.tcpChannels)
        assertTrue(diag.transportAttached)
    }

    @Test
    fun probeOfSupersededAttemptCannotWriteDiagnostics() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        val probeA = h.sdk.probes.last()!!
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.WIFI_AP) { }
        val probeB = h.sdk.probes.last()!!
        assertSame(probeB, h.sdk.probes.last())

        probeA.onUdpPacketReceived("10.0.0.1", 99) // late A
        assertNull(h.provider.diagnostics().phoneIp)

        probeB.onUdpPacketReceived("10.0.0.2", 1) // B's own
        assertEquals("10.0.0.2", h.provider.diagnostics().phoneIp)
    }
}
