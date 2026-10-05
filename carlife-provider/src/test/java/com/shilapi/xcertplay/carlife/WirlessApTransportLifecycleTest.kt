// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessAPProtocolTransport
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessConnector
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 9.2W-A: REAL socket lifecycle of the upstream wireless AP transport
 * (sections 12-14) — no sleeps as fencing, deterministic thread exit.
 *
 * Properties under test:
 *  - UDP 7999 binds exactly ONCE per connect; double connect() never binds
 *    twice
 *  - terminate() deterministically ends the discovery thread and closes the
 *    socket; start/stop/start never hits "Address already in use"
 *  - discovery datagrams reach the probe with the phone IP (source address)
 *  - repeated discovery never corrupts connector state (eager TCP channel
 *    set is restarted cleanly)
 *  - a partial TCP channel failure closes everything it opened and reports
 *    per-channel states truthfully
 */
@RunWith(RobolectricTestRunner::class)
class WirlessApTransportLifecycleTest {

    private class RecordingProbe : WirlessTransportProbe {
        val listened = CopyOnWriteArrayList<Int>()
        val stopped = CopyOnWriteArrayList<Unit>()
        val packets = CopyOnWriteArrayList<Pair<String, Int>>()
        val channelStates = CopyOnWriteArrayList<Triple<Int, String, String?>>()
        val connecting = CopyOnWriteArrayList<String>()
        val attached = CopyOnWriteArrayList<String>()

        override fun onUdpListening(port: Int) {
            listened.add(port)
        }

        override fun onUdpStopped() {
            stopped.add(Unit)
        }

        override fun onUdpPacketReceived(fromIp: String, total: Int) {
            packets.add(fromIp to total)
        }

        override fun onTcpConnecting(host: String) {
            connecting.add(host)
        }

        override fun onTcpChannelState(channel: Int, port: Int, state: String, error: String?) {
            channelStates.add(Triple(channel, state, error))
        }

        override fun onTransportAttached(host: String) {
            attached.add(host)
        }
    }

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

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for: $what")
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    /**
     * Binds the seven upstream channel ports on loopback for the connector to
     * connect to. Never leaks partially bound sockets; returns null when
     * another process owns one of the protocol-fixed ports (the test is then
     * skipped honestly - the ports are wire constants and cannot be moved).
     */
    private fun bindChannelServers(): List<ServerSocket>? {
        val bound = mutableListOf<ServerSocket>()
        for (port in listOf(7240, 8240, 9240, 9241, 9242, 9340, 9440)) {
            try {
                bound.add(ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")))
            } catch (e: Exception) {
                bound.forEach { it.close() }
                return null
            }
        }
        return bound
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

    // ---- UDP 7999 lifecycle (sections 12/13) ----

    @Test
    fun udpDiscoveryBindsExactlyOncePerConnect() {
        val probe = RecordingProbe()
        val transport = WirlessAPProtocolTransport(fakeContext(), null, probe)
        try {
            transport.connect()
            await("UDP listening") { probe.listened.size == 1 }
            // Second connect() is a no-op: no second bind, no second thread.
            transport.connect()
            transport.connect()
            Thread.sleep(200)
            assertEquals(listOf(7999), probe.listened)
        } finally {
            transport.terminate()
        }
    }

    @Test
    fun terminateStopsTheDiscoveryThreadDeterministically() {
        val probe = RecordingProbe()
        val transport = WirlessAPProtocolTransport(fakeContext(), null, probe)
        transport.connect()
        await("UDP listening") { probe.listened.size == 1 }
        transport.terminate()
        // No sleep-based fencing: the thread MUST exit promptly after stop.
        await("discovery thread exit") { probe.stopped.size == 1 }
    }

    @Test
    fun startStopStartNeverHitsAddressInUse() {
        val probe = RecordingProbe()
        val transport = WirlessAPProtocolTransport(fakeContext(), null, probe)
        try {
            transport.connect()
            await("first listening") { probe.listened.size == 1 }
            transport.terminate()
            await("first thread exit") { probe.stopped.size == 1 }

            // Restart on the SAME instance: the old socket must be gone.
            transport.connect()
            await("second listening") { probe.listened.size == 2 }
        } finally {
            transport.terminate()
        }
    }

    @Test
    fun discoveryDatagramsCarryThePhoneIpAndSurviveRepeats() {
        val probe = RecordingProbe()
        val transport = WirlessAPProtocolTransport(fakeContext(), null, probe)
        try {
            transport.connect()
            await("UDP listening") { probe.listened.size == 1 }
            sendDiscoveryDatagram()
            await("first datagram") { probe.packets.size == 1 }
            sendDiscoveryDatagram()
            await("second datagram") { probe.packets.size == 2 }

            // Upstream semantics: the datagram SOURCE is the phone IP and a
            // failed TCP attach retries on the NEXT datagram.
            assertEquals("127.0.0.1", probe.packets.first().first)
            assertTrue(probe.connecting.size >= 2)
            assertTrue(probe.attached.isEmpty()) // no TCP listeners -> no attach
        } finally {
            transport.terminate()
        }
    }

    // ---- TCP channel set (WirlessConnector, section 14/20) ----

    @Test
    fun partialTcpFailureCleansUpAndReportsTruthfully() {
        val probe = RecordingProbe()
        val connector = WirlessConnector(probe)
        // Nothing listens on the channel ports -> the FIRST eager connect
        // fails (audited: SocketCommunicator connects immediately).
        val connected = connector.startConnect("127.0.0.1")
        assertFalse("channel set must fail cleanly", connected)
        // Diagnostics reflect the REAL eager behavior: at least one channel
        // attempted and failed - never a fake "all connected".
        assertTrue(probe.channelStates.any { it.second == "connecting" })
        assertTrue(probe.channelStates.any { it.second == "failed" })
        assertTrue(probe.attached.isEmpty())
        connector.terminate()
    }

    @Test
    fun fullChannelSetConnectsEagerlyAndRestartsCleanly() {
        val servers = bindChannelServers()
        org.junit.Assume.assumeTrue("channel ports owned by another process", servers != null)
        if (servers == null) return
        val probe = RecordingProbe()
        val connector = WirlessConnector(probe)
        try {
            assertTrue(connector.startConnect("127.0.0.1"))
            // Audited eager behavior: ALL seven channel sockets connect
            // immediately (7240/8240/9240/9241/9242/9340/9440).
            assertEquals(7, probe.channelStates.count { it.second == "connected" })

            // Session end closes ALL channel sockets; a fresh session gets a
            // fresh communicator set (restart is clean, no duplicates).
            connector.terminate()
            probe.channelStates.clear()
            assertTrue(connector.startConnect("127.0.0.1"))
            assertEquals(7, probe.channelStates.count { it.second == "connected" })
            connector.terminate()
        } finally {
            servers.forEach { it.close() }
        }
    }

    @Test
    fun repeatedStartConnectIsIdempotent() {
        val servers = bindChannelServers()
        org.junit.Assume.assumeTrue("channel ports owned by another process", servers != null)
        if (servers == null) return
        val probe = RecordingProbe()
        val connector = WirlessConnector(probe)
        try {
            assertTrue(connector.startConnect("127.0.0.1"))
            // Duplicate discovery: the previous channel set is closed and a
            // fresh one opened - never a leak, never duplicate delivery.
            assertTrue(connector.startConnect("127.0.0.1"))
            assertEquals(14, probe.channelStates.count { it.second == "connected" })
            connector.terminate()
        } finally {
            servers.forEach { it.close() }
        }
    }
}
