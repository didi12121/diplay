// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.protobuf.CarlifeWirlessInfoProto
import com.baidu.carlife.protobuf.CarlifeWirlessIpProto
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.receiver.transport.instant.CarLifeWirelessBootstrap
import com.baidu.carlife.sdk.receiver.transport.instant.CarLifeWirelessBootstrapSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 9.2W-B1: REAL bootstrap message handling over the public V2 framing
 * (CarLifeMessage / ServiceTypes / protos) - the exact sequencing question the
 * real vivo test must answer is exercised here against the source-grounded
 * handlers:
 *  - MSG_WIRELESS_INFO_REQUEST  -> exactly ONE MSG_WIRELESS_INFO_RESPONSE
 *    advertising TYPE_WIFI (hotspot) + FREQUENCY_2_4G, NEVER TYPE_ALL
 *  - MSG_WIRELESS_TARGET_INFO_REQUEST -> OBSERVED ONLY: no response, no fake
 *    target data, no Wi-Fi Direct machinery
 *  - MSG_WIRELESS_RESPONSE_IP   -> CarlifeWirlessIp.wirlessip parsed +
 *    validated (invalid/empty rejected)
 *  - MSG_WIRELESS_REQUEST_IP is never sent
 *  - terminate() closes the RFCOMM communicator
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeWirelessBootstrapSessionTest {

    /** Scripted RFCOMM communicator (real CarLifeMessage framing semantics). */
    private class FakeRfcomm : Communicator {
        val inbox: BlockingQueue<CarLifeMessage> = LinkedBlockingQueue()
        val writtenTypes = CopyOnWriteArrayList<Int>()
        val writtenInfos = CopyOnWriteArrayList<Pair<Int, Int>>() // type, frequency
        val terminated = AtomicBoolean(false)

        override fun write(message: CarLifeMessage) {
            writtenTypes.add(message.serviceType)
            (message.protoPayload as? CarlifeWirlessInfoProto.CarlifeWirlessInfo)?.let {
                writtenInfos.add(it.wirlessType to it.wifiFrequency)
            }
        }

        override fun read(): CarLifeMessage {
            while (!terminated.get()) {
                val message = inbox.poll(50, TimeUnit.MILLISECONDS)
                if (message != null) return message
            }
            throw IOException("closed")
        }

        override fun terminate() {
            terminated.set(true)
        }
    }

    private class RecordingListener : CarLifeWirelessBootstrap.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val ips = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()

        override fun onBootstrapMessage(kind: String) {
            messages.add(kind)
        }

        override fun onWirelessIp(ip: String) {
            ips.add(ip)
        }

        override fun onBootstrapFailed(reason: String) {
            failures.add(reason)
        }
    }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun incoming(serviceType: Int, payload: com.google.protobuf.MessageLite? = null): CarLifeMessage =
        CarLifeMessage.obtain(Constants.MSG_CHANNEL_CMD, serviceType).also {
            if (payload != null) it.payload(payload)
        }

    @Test
    fun infoRequestGetsExactlyOneInfoResponseAdvertisedAsHotspot() {
        val rfcomm = FakeRfcomm()
        val listener = RecordingListener()
        val session = CarLifeWirelessBootstrapSession(rfcomm, listener)
        session.start()
        try {
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_INFO_REQUEST))
            await("info response") { rfcomm.writtenTypes.isNotEmpty() }

            assertEquals(listOf(ServiceTypes.MSG_WIRELESS_INFO_RESPONSE), rfcomm.writtenTypes.toList())
            // Section 16: advertise TYPE_WIFI (1) + FREQUENCY_2_4G (0); NEVER
            // TYPE_ALL (3) because Wi-Fi Direct is not implemented.
            assertEquals(listOf(1 to 0), rfcomm.writtenInfos.toList())
            assertTrue(listener.messages.contains("info-request"))
            assertTrue(listener.messages.contains("info-response-sent"))
        } finally {
            session.terminate()
        }
    }

    @Test
    fun repeatedInfoRequestsGetOneResponseEach() {
        val rfcomm = FakeRfcomm()
        val listener = RecordingListener()
        val session = CarLifeWirelessBootstrapSession(rfcomm, listener)
        session.start()
        try {
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_INFO_REQUEST))
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_INFO_REQUEST))
            await("two responses") { rfcomm.writtenTypes.size == 2 }
            // Exactly one response per request.
            assertEquals(2, rfcomm.writtenTypes.count { it == ServiceTypes.MSG_WIRELESS_INFO_RESPONSE })
        } finally {
            session.terminate()
        }
    }

    @Test
    fun targetInfoRequestIsObservedOnlyNeverAnswered() {
        val rfcomm = FakeRfcomm()
        val listener = RecordingListener()
        val session = CarLifeWirelessBootstrapSession(rfcomm, listener)
        session.start()
        try {
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_TARGET_INFO_REQUEST))
            await("observed") { listener.messages.contains("target-info-request") }
            Thread.sleep(100)
            // NO response, NO fake Wi-Fi Direct target, NO P2P machinery.
            assertTrue(rfcomm.writtenTypes.isEmpty())
        } finally {
            session.terminate()
        }
    }

    @Test
    fun validResponseIpIsReportedExactlyOnce() {
        val rfcomm = FakeRfcomm()
        val listener = RecordingListener()
        val session = CarLifeWirelessBootstrapSession(rfcomm, listener)
        session.start()
        try {
            val payload = CarlifeWirlessIpProto.CarlifeWirlessIp.newBuilder()
                .setWirlessip("192.168.43.123")
                .build()
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_RESPONSE_IP, payload))
            await("ip") { listener.ips.isNotEmpty() }
            assertEquals(listOf("192.168.43.123"), listener.ips.toList())
            assertTrue(listener.failures.isEmpty())
            assertTrue(listener.messages.contains("response-ip"))
        } finally {
            session.terminate()
        }
    }

    @Test
    fun invalidOrEmptyResponseIpIsRejected() {
        val rfcomm = FakeRfcomm()
        val listener = RecordingListener()
        val session = CarLifeWirelessBootstrapSession(rfcomm, listener)
        session.start()
        try {
            val empty = CarlifeWirlessIpProto.CarlifeWirlessIp.newBuilder().setWirlessip("").build()
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_RESPONSE_IP, empty))
            val bogus = CarlifeWirlessIpProto.CarlifeWirlessIp.newBuilder().setWirlessip("not-an-ip").build()
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_RESPONSE_IP, bogus))
            await("two rejections") { listener.failures.size == 2 }
            assertTrue(listener.ips.isEmpty())
            assertEquals(listOf("PHONE_IP_INVALID", "PHONE_IP_INVALID"), listener.failures.toList())
        } finally {
            session.terminate()
        }
    }

    @Test
    fun mdStatusIsObservedAndRequestIpIsNeverSent() {
        val rfcomm = FakeRfcomm()
        val listener = RecordingListener()
        val session = CarLifeWirelessBootstrapSession(rfcomm, listener)
        session.start()
        try {
            rfcomm.inbox.add(incoming(ServiceTypes.MSG_WIRELESS_MD_STATUS))
            await("md status") { listener.messages.contains("md-status") }
            // Section 20: MSG_WIRELESS_REQUEST_IP is NEVER sent speculatively.
            assertFalse(rfcomm.writtenTypes.contains(ServiceTypes.MSG_WIRELESS_REQUEST_IP))
        } finally {
            session.terminate()
        }
    }

    @Test
    fun terminateClosesTheRfommCommunicator() {
        val rfcomm = FakeRfcomm()
        val session = CarLifeWirelessBootstrapSession(rfcomm, RecordingListener())
        session.start()
        session.terminate()
        assertTrue(rfcomm.terminated.get())
        session.terminate() // idempotent
        assertTrue(rfcomm.terminated.get())
    }
}
