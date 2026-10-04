// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import android.content.Context
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy

private fun RuntimeEnvironment.getApplication(): Context =
    Proxy.newProxyInstance(
        Context::class.java.classLoader,
        arrayOf(Context::class.java),
    ) { _, _, _ -> null } as Context

private fun fakeContext(): CarLifeContext =
    Proxy.newProxyInstance(
        CarLifeContext::class.java.classLoader,
        arrayOf(CarLifeContext::class.java),
    ) { _, _, _ -> null } as CarLifeContext

/**
 * REAL CarLifeV2Provider boundary tests (not FakeProvider): the provider's
 * session fencing is driven against a scripted SDK facade that can replay
 * connection state on registration and deliver LATE callbacks through old
 * listener instances — exactly what a real SDK may do.
 *
 * The property under test: an old SDK callback can NEVER be re-labelled as
 * the new session's token. Events carry the token fixed at listener creation.
 */
@RunWith(RobolectricTestRunner::class)
class CarLifeV2ProviderFencingTest {

    /** Scripted SDK: records calls, replays state on registration on demand. */
    private class ScriptedSdk : CarLifeReceiverFacade {
        val calls = mutableListOf<String>()
        val connectionListeners = mutableListOf<ConnectionChangeListener>()
        val progressListeners = mutableListOf<ConnectProgressListener>()
        var replayEstablishedOnRegister = false

        override fun addConnectionListener(listener: ConnectionChangeListener) {
            calls.add("register-connection")
            connectionListeners.add(listener)
            if (replayEstablishedOnRegister) {
                // The real SDK replays connection state to new listeners while
                // connectionState != DETACHED (CarLifeContextImpl behavior).
                listener.onConnectionEstablished(fakeContext())
            }
        }

        override fun removeConnectionListener(listener: ConnectionChangeListener) {
            calls.add("unregister-connection")
            connectionListeners.remove(listener)
        }

        override fun addProgressListener(listener: ConnectProgressListener) {
            calls.add("register-progress")
            progressListeners.add(listener)
        }

        override fun removeProgressListener(listener: ConnectProgressListener) {
            calls.add("unregister-progress")
            progressListeners.remove(listener)
        }

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
        override fun usbDeviceSummaries(): List<String> = listOf("VID:PID 18d1:4ee1")
    }

    private class Harness {
        val sdk = ScriptedSdk()
        val provider = CarLifeV2Provider { _, _ -> sdk }
        val backend = CarLifeProjectionBackend(provider, { _, _ -> AutoCloseable { } }, 60_000)

        init {
            provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        }
    }

    // ---- #1 initialize does not start AOA / connect ----

    @Test
    fun initializeDoesNotStartAoaOrConnect() {
        val h = Harness()
        // initialize only built the facade: no register/connect/scan calls.
        assertTrue(h.sdk.calls.isEmpty())
    }

    @Test
    fun initializeIsIdempotentOneFacadeOnly() {
        var factoryCalls = 0
        val sdk = ScriptedSdk()
        val provider = CarLifeV2Provider { _, _ -> factoryCalls++; sdk }
        provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        provider.initialize(RuntimeEnvironment.getApplication(), CarLifeProviderConfig())
        // One process -> one CarLife.init -> one captured receiver.
        assertEquals(1, factoryCalls)
    }

    // ---- #3 Real provider session fencing (Tests A-D) ----

    @Test
    fun testALateEstablishedOfAKeepsBConnecting() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val eventsA = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenA) { eventsA.add(it) }
        val listenerA = h.sdk.connectionListeners.single()

        // Attempt A stopped; attempt B starts.
        h.provider.stopConnection(tokenA)
        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenB) { eventsB.add(it) }

        // Fence ordering: A's listener unregistered and its transport fenced
        // BEFORE B was bound.
        val fenceIndex = h.sdk.calls.indexOf("shutdown")
        val bRegisterIndex = h.sdk.calls.indexOfLast { it == "register-connection" }
        assertTrue(h.sdk.calls.indexOf("unregister-connection") in 0 until bRegisterIndex)
        assertTrue(fenceIndex in 0 until bRegisterIndex)

        // LATE Established of A arrives through A's old listener instance
        // (a leaked in-flight callback). It must carry A's token and must not
        // reach B's sink.
        listenerA.onConnectionEstablished(fakeContext())
        assertTrue(eventsB.isEmpty())
        assertEquals(tokenA, eventsA.last().session)
        assertTrue(eventsA.last() is CarLifeConnectionEvent.Established)
    }

    @Test
    fun testBLateAuthFailedOfAKeepsBUnchanged() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val eventsA = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenA) { eventsA.add(it) }
        val listenerA = h.sdk.connectionListeners.single()
        h.provider.stopConnection(tokenA)

        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenB) { eventsB.add(it) }

        listenerA.onConnectionAuthenFailed(fakeContext())
        assertTrue(eventsB.isEmpty())
        assertTrue(eventsA.last() is CarLifeConnectionEvent.AuthFailed)
        assertEquals(tokenA, eventsA.last().session)
    }

    @Test
    fun testCLateDetachedOfAKeepsBSessionUnchanged() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        val eventsA = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenA) { eventsA.add(it) }
        val listenerA = h.sdk.connectionListeners.single()
        h.provider.stopConnection(tokenA)

        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenB) { eventsB.add(it) }

        listenerA.onConnectionDetached(fakeContext())
        assertTrue(eventsB.isEmpty())
        assertEquals(tokenA, eventsA.last().session)
    }

    @Test
    fun testDBCallbacksOperateNormally() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        h.provider.startConnection(tokenA) { }
        h.provider.stopConnection(tokenA)

        val tokenB = CarLifeSessionToken(2)
        val eventsB = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(tokenB) { eventsB.add(it) }
        val listenerB = h.sdk.connectionListeners.single()

        listenerB.onConnectionAttached(fakeContext())
        listenerB.onConnectionEstablished(fakeContext())
        assertEquals(2, eventsB.size)
        assertTrue(eventsB.all { it.session == tokenB })
        assertTrue(eventsB[1] is CarLifeConnectionEvent.Established)
    }

    @Test
    fun registrationReplayCannotMasqueradeAsCurrentSession() {
        val h = Harness()
        // The SDK replays ESTABLISHED at registration (stale connection state
        // of a previous attempt). The attempt is not armed yet -> dropped.
        h.sdk.replayEstablishedOnRegister = true
        val events = mutableListOf<CarLifeConnectionEvent>()
        h.provider.startConnection(CarLifeSessionToken(1)) { events.add(it) }
        assertTrue("registration replay must be dropped", events.isEmpty())

        // Real events after connect() flow normally.
        val listener = h.sdk.connectionListeners.single()
        listener.onConnectionEstablished(fakeContext())
        assertEquals(1, events.size)
    }

    @Test
    fun doubleProbeDoesNotOrphanOldReceiverOrListener() {
        val h = Harness()
        h.provider.startConnection(CarLifeSessionToken(1)) { }
        h.provider.startConnection(CarLifeSessionToken(2)) { }
        h.provider.startConnection(CarLifeSessionToken(3)) { }
        // One facade total (captured receiver), one live listener at a time.
        assertEquals(1, h.sdk.connectionListeners.size)
        // Each superseded attempt was unregistered AND fenced.
        assertEquals(2, h.sdk.calls.count { it == "shutdown" })
        assertEquals(2, h.sdk.calls.count { it == "unregister-connection" })
    }

    @Test
    fun stopConnectionOnlyStopsItsOwnToken() {
        val h = Harness()
        val tokenA = CarLifeSessionToken(1)
        h.provider.startConnection(tokenA) { }
        // Wrong token: no-op (no unregister/shutdown storm).
        h.provider.stopConnection(CarLifeSessionToken(999))
        assertTrue(h.sdk.calls.none { it == "shutdown" })
        h.provider.stopConnection(tokenA)
        assertEquals(1, h.sdk.calls.count { it == "shutdown" })
    }
}
