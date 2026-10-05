// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.transport.communicator.SocketCommunicator
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessAPProtocolTransport
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 9.2W-B1 TCP-side transport properties:
 *  - SocketCommunicator connects with an EXPLICIT bounded timeout
 *    (CONNECT_TIMEOUT_MS = 4000, documented) - never an unbounded block
 *  - the BT hotspot (protocol-IP) mode of the wirless transport NEVER binds
 *    UDP 7999 - legacy WIFI_AP keeps its discovery socket untouched
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeWirelessTcpTimeoutTest {

    private fun fakeContext(): CarLifeContext =
        Proxy.newProxyInstance(
            CarLifeContext::class.java.classLoader,
            arrayOf(CarLifeContext::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Integer::class.javaPrimitiveType, Integer::class.java -> 0
                java.lang.Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                else -> null
            }
        } as CarLifeContext

    @Test
    fun socketConnectTimeoutIsExplicitlyBounded() {
        assertEquals(4_000, SocketCommunicator.CONNECT_TIMEOUT_MS)
        val started = System.currentTimeMillis()
        var failed = false
        try {
            // 192.0.2.1 (TEST-NET-1) is never routable: the plain Socket(host,
            // port) constructor would block for the OS default (20s+).
            SocketCommunicator(1, "192.0.2.1", 7240)
        } catch (expected: Exception) {
            failed = true
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("connect must fail", failed)
        assertTrue("connect must respect the $elapsed ms bound (< 8s)", elapsed < 8_000)
    }

    @Test
    fun btHotspotModeNeverBindsUdp7999() {
        val probe = object : WirlessTransportProbe {
            val events = CopyOnWriteArrayList<String>()
            override fun onUdpListening(port: Int) {
                events.add("udp-listening:$port")
            }

            override fun onTcpConnecting(host: String) {
                events.add("tcp-connecting:$host")
            }

            override fun onTransportError(error: String) {
                events.add("error:$error")
            }
        }
        val transport = WirlessAPProtocolTransport(fakeContext(), null, probe)
        transport.phoneIp = "127.0.0.1" // protocol-provided IP mode (BT hotspot)
        transport.connect()
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && probe.events.none { it.startsWith("error:") }) {
                Thread.sleep(20)
            }
            // TCP-direct path was taken (channel set fails fast on loopback).
            assertTrue(probe.events.any { it == "tcp-connecting:127.0.0.1" })
            assertTrue(probe.events.any { it.startsWith("error:TCP_CONNECT_FAILED") })
            // The legacy discovery socket was NEVER bound in this mode.
            assertTrue(probe.events.none { it.startsWith("udp-listening:") })
            val squatter = DatagramSocket(7999) // bindable => transport did not hold it
            squatter.close()
        } finally {
            transport.terminate()
        }
    }
}
