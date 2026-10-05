// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.receiver.transport.GroupedProtocolTransport
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 9.2W-A.1 REAL integration test for the production probe path - the
 * exact ordering the 9.2W-A tests missed:
 *
 * [CarLifeV2Provider] → REAL [GroupedProtocolTransport] → REAL
 * [com.baidu.carlife.sdk.receiver.transport.wirless.WirlessAPProtocolTransport]
 * → REAL UDP 7999 bind.
 *
 * The probe is NEVER invoked manually: every callback must arrive through the
 * production binding order (probe bound before transport configuration, armed
 * before connect). With the old ordering the configured AP transport kept a
 * null probe and these events were lost.
 */
/**
 * Feature-aware fake CarLifeContext (dynamic proxy): stores the feature map
 * GroupedProtocolTransport reads FEATURE_CONFIG_CONNECT_TYPE from.
 */
private fun featureContext(features: MutableMap<String, Int>): CarLifeContext =
    Proxy.newProxyInstance(
        CarLifeContext::class.java.classLoader,
        arrayOf(CarLifeContext::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getFeature" -> features[args!![0] as String] ?: (args[1] as Int)
            "setFeature" -> {
                features[args!![0] as String] = args[1] as Int
                null
            }
            "setFeatures" -> {
                features.clear()
                features.putAll((args!![0] as Map<*, *>).entries.associate { it.key as String to it.value as Int })
                null
            }
            "getConnectionState" -> 0
            else -> when (method.returnType) {
                Integer::class.javaPrimitiveType, Integer::class.java -> 0
                java.lang.Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                else -> null
            }
        }
    } as CarLifeContext

@RunWith(RobolectricTestRunner::class)
class CarLifeWirelessRealProbeTest {

    /**
     * Realistic facade: delegates transport lifecycle to a REAL
     * GroupedProtocolTransport (which builds the REAL AP transport), while the
     * connection-listener surface stays scripted (protocol events are not the
     * subject of this test).
     */
    private class GroupedTransportFacade(private val context: CarLifeContext) : CarLifeReceiverFacade {
        val transport = GroupedProtocolTransport(context)

        override fun addConnectionListener(listener: ConnectionChangeListener) {}
        override fun removeConnectionListener(listener: ConnectionChangeListener) {}
        override fun addProgressListener(listener: ConnectProgressListener) {}
        override fun removeProgressListener(listener: ConnectProgressListener) {}
        override fun connect() = transport.connect()
        override fun stopConnect() = transport.stopConnect()
        override fun shutdown() = transport.shutdown()
        override fun connectionState(): Int = 0
        override fun protocolVersion(): Int = 4
        override fun carlifeVersion(): Int = 0
        override fun usbDeviceSummaries(): List<String> = emptyList()

        override fun configureConnectTypeWithoutStarting(type: Int) {
            context.setFeature(Configs.FEATURE_CONFIG_CONNECT_TYPE, type)
            transport.configConnectType()
        }

        override fun setTransportProbeListener(listener: WirlessTransportProbe?) {
            transport.transportProbe = listener
        }
    }

    private class Harness {
        val events = CopyOnWriteArrayList<CarLifeConnectionEvent>()
        val context: CarLifeContext
        val facade: GroupedTransportFacade
        val provider: CarLifeV2Provider

        init {
            // Pre-select HOTSPOT so the real GroupedProtocolTransport builds
            // the AP transport (the AOA scanner needs a real UsbManager and is
            // irrelevant here).
            val features = mutableMapOf(
                Configs.FEATURE_CONFIG_CONNECT_TYPE to CarLifeContext.CONNECTION_TYPE_HOTSPOT,
            )
            context = featureContext(features)
            facade = GroupedTransportFacade(context)
            provider = CarLifeV2Provider { _, _ -> facade }
            provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        }
    }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun sendDiscoveryDatagram() {
        val socket = DatagramSocket()
        try {
            socket.send(
                DatagramPacket(ByteArray(4), 4, InetAddress.getByName("127.0.0.1"), 7999),
            )
        } finally {
            socket.close()
        }
    }

    // ---- Section 7: production ordering delivers REAL transport events ----

    @Test
    fun realStartConnectionBindsUdpAndDeliversListeningEvent() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token, CarLifeTransport.WIFI_AP) { h.events.add(it) }
        try {
            // WITHOUT ever invoking the probe manually:
            await("real UDP bind + diagnostics") {
                h.provider.diagnostics().udpListening && h.provider.diagnostics().udpPort == 7999
            }
            assertTrue(
                "WIFI_UDP_LISTENING must arrive through the production path",
                h.events.any {
                    it is CarLifeConnectionEvent.WirelessStage &&
                        it.stage == CarLifeProbeState.WIFI_UDP_LISTENING &&
                        it.session == token
                },
            )
        } finally {
            h.provider.stopConnection(token)
        }
    }

    @Test
    fun realDiscoveryDatagramPopulatesPhoneIpAndStage() {
        val h = Harness()
        val token = CarLifeSessionToken(1)
        h.provider.startConnection(token, CarLifeTransport.WIFI_AP) { h.events.add(it) }
        try {
            await("UDP listening") { h.provider.diagnostics().udpListening }
            sendDiscoveryDatagram()
            await("discovery processed") {
                val diag = h.provider.diagnostics()
                diag.udpPacketsReceived >= 1 && diag.phoneIp == "127.0.0.1"
            }
            assertTrue(
                h.events.any {
                    it is CarLifeConnectionEvent.WirelessStage &&
                        it.stage == CarLifeProbeState.WIFI_PHONE_DISCOVERED &&
                        it.session == token
                },
            )
        } finally {
            h.provider.stopConnection(token)
        }
    }

    // ---- Section 5: UDP bind failure is immediate, not a fake timeout ----

    @Test
    fun udpBindFailureReportsImmediateTransportError() {
        val squatter = DatagramSocket(7999) // occupy the discovery port
        try {
            val h = Harness()
            val token = CarLifeSessionToken(1)
            h.provider.startConnection(token, CarLifeTransport.WIFI_AP) { h.events.add(it) }
            // The real transport fails the bind - the failure must surface as
            // an immediate Failed event (well before any 90s timeout).
            await("immediate UDP_BIND_FAILED") {
                h.events.any {
                    it is CarLifeConnectionEvent.Failed &&
                        it.message.startsWith("UDP_BIND_FAILED")
                }
            }
            assertFalse(h.provider.diagnostics().udpListening)
            h.provider.stopConnection(token)
        } finally {
            squatter.close()
        }
    }

    @Test
    fun stopClosesTheRealDiscoverySocketForTheNextSession() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        h.provider.startConnection(tokenA, CarLifeTransport.WIFI_AP) { h.events.add(it) }
        await("UDP listening") { h.provider.diagnostics().udpListening }
        h.provider.stopConnection(tokenA)

        // Session B binds 7999 again - no "Address already in use".
        val tokenB = CarLifeSessionToken(2)
        h.provider.startConnection(tokenB, CarLifeTransport.WIFI_AP) { h.events.add(it) }
        try {
            await("UDP listening for B") {
                h.events.count {
                    it is CarLifeConnectionEvent.WirelessStage &&
                        it.stage == CarLifeProbeState.WIFI_UDP_LISTENING
                } >= 2
            }
        } finally {
            h.provider.stopConnection(tokenB)
        }
    }
}
