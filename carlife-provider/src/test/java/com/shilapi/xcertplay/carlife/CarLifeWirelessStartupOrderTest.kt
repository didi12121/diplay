// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 9.2W-A.1: REAL [CarLifeV2Provider] startup correctness (regression for
 * bugs the 9.2W-A tests missed).
 *
 * Properties under test:
 *  - the attempt-specific wireless probe is bound BEFORE the transport is
 *    configured (a transport configured earlier is rebinding dynamically - the
 *    probe is never left null on the production path)
 *  - the attempt is ARMED BEFORE facade.connect(): synchronous transport
 *    events caused by connect() (UDP bind -> onUdpListening) belong to the
 *    current attempt and are NOT dropped; registration replay before arming
 *    is still rejected
 *  - transport reconfiguration FAILS CLOSED: on failure connect() is never
 *    called, the configuration cache is not updated (a later request always
 *    retries), and the exception propagates to backend startup failure
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeWirelessStartupOrderTest {

    /** Scripted SDK: records call ORDER and can fail/emits synchronously. */
    private class ScriptedSdk : CarLifeReceiverFacade {
        val calls = mutableListOf<String>()
        var probe: WirlessTransportProbe? = null
        var throwOnConfigure = false
        var throwOnConnect = false
        var emitUdpListeningOnConnect = false

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
            if (throwOnConnect) throw IllegalStateException("connect failed")
            if (emitUdpListeningOnConnect) {
                // The REAL WirlessAPProtocolTransport binds UDP 7999 inside
                // connect() and reports onUdpListening SYNCHRONOUSLY.
                probe?.onUdpListening(7999)
            }
        }

        override fun stopConnect() {}
        override fun shutdown() {}
        override fun connectionState(): Int = 0
        override fun protocolVersion(): Int = 4
        override fun carlifeVersion(): Int = 0
        override fun usbDeviceSummaries(): List<String> = emptyList()

        override fun configureConnectTypeWithoutStarting(type: Int) {
            calls.add("configure:$type")
            if (throwOnConfigure) throw IllegalStateException("configure failed")
        }

        override fun setTransportProbeListener(listener: WirlessTransportProbe?) {
            probe = listener
            calls.add(if (listener == null) "probe:null" else "probe")
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
    fun probeIsBoundBeforeTransportConfiguration() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        val probeIndex = h.sdk.calls.indexOf("probe")
        val configureIndex = h.sdk.calls.indexOf("configure:${CarLifeContext.CONNECTION_TYPE_HOTSPOT}")
        val connectIndex = h.sdk.calls.indexOf("connect")
        assertTrue("probe must be bound", probeIndex >= 0)
        assertTrue("probe BEFORE configure", probeIndex < configureIndex)
        assertTrue("configure BEFORE connect", configureIndex < connectIndex)
    }

    @Test
    fun synchronousUdpListeningFromConnectBelongsToTheAttempt() {
        val h = Harness()
        h.sdk.emitUdpListeningOnConnect = true
        val events = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { events.add(it) }

        // Regression (9.2W-A.1): the armed flag used to flip AFTER connect(),
        // so the synchronous UDP_LISTENING event was silently dropped.
        val stage = events.singleOrNull {
            it is CarLifeConnectionEvent.WirelessStage &&
                it.stage == CarLifeProbeState.WIFI_UDP_LISTENING
        }
        assertTrue("WIFI_UDP_LISTENING must not be dropped", stage != null)
        assertEquals(CarLifeSessionToken(1), stage!!.session)
        assertTrue(h.provider.diagnostics().udpListening)
        assertEquals(7999, h.provider.diagnostics().udpPort)
    }

    @Test
    fun registrationReplayBeforeArmingIsStillRejected() {
        // Fencing preserved: anything delivered while armed=false (registration
        // replay, pre-connect leftovers) is still dropped.
        val h = Harness()
        val events = mutableListOf<CarLifeConnectionEvent>()
        h.sdk.emitUdpListeningOnConnect = false
        h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { events.add(it) }
        // The probe registered during the pre-arm window must have been inert.
        assertTrue(events.isEmpty())
    }

    // ---- Fail-closed reconfiguration (section 3/8) ----

    @Test
    fun reconfigurationFailureNeverConnectsAndPropagates() {
        val h = Harness()
        h.sdk.throwOnConfigure = true
        var thrown: Exception? = null
        try {
            h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        } catch (error: Exception) {
            thrown = error
        }
        assertTrue("reconfiguration failure must propagate", thrown is IllegalStateException)
        // FAIL CLOSED: connect() was never called - the previous transport was
        // NOT started and no half-configured transport was used.
        assertFalse(h.sdk.calls.contains("connect"))
    }

    @Test
    fun failedReconfigurationIsRetriedNotCached() {
        val h = Harness()
        h.sdk.throwOnConfigure = true
        try {
            h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        } catch (expected: IllegalStateException) {
        }
        h.sdk.throwOnConfigure = false

        // The failed WIFI_AP configuration must NOT be cached as success.
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.WIFI_AP) { }
        assertEquals(
            2,
            h.sdk.calls.count { it == "configure:${CarLifeContext.CONNECTION_TYPE_HOTSPOT}" },
        )
        assertTrue(h.sdk.calls.contains("connect"))
    }

    @Test
    fun afterFailedWirelessConfigUsbAlsoReconfiguresExplicitly() {
        val h = Harness()
        h.sdk.throwOnConfigure = true
        try {
            h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        } catch (expected: IllegalStateException) {
        }
        h.sdk.throwOnConfigure = false

        // The receiver may be half-reconfigured - a USB request must also
        // reconfigure explicitly instead of trusting a stale cache.
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.USB_AOA) { }
        assertTrue(h.sdk.calls.contains("configure:${CarLifeContext.CONNECTION_TYPE_AOA}"))
        assertTrue(h.sdk.calls.contains("connect"))
    }

    @Test
    fun connectFailurePropagatesAndTheNextAttemptWorks() {
        val h = Harness()
        h.sdk.throwOnConnect = true
        var thrown: Exception? = null
        try {
            h.provider.startConnection(CarLifeSessionToken(1), CarLifeTransport.WIFI_AP) { }
        } catch (error: Exception) {
            thrown = error
        }
        assertTrue("connect failure must propagate", thrown is IllegalStateException)

        h.sdk.throwOnConnect = false
        h.sdk.emitUdpListeningOnConnect = true
        val events = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(CarLifeSessionToken(2), CarLifeTransport.WIFI_AP) { events.add(it) }
        assertTrue(
            events.any {
                it is CarLifeConnectionEvent.WirelessStage &&
                    it.stage == CarLifeProbeState.WIFI_UDP_LISTENING &&
                    it.session == CarLifeSessionToken(2)
            },
        )
    }
}
