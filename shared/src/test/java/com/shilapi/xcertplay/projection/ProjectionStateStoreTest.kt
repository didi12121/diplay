package com.shilapi.xcertplay.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProjectionStateStore: dedup, listener fan-out, error payload retention. */
class ProjectionStateStoreTest {

    @Test
    fun startsIdleAndDeduplicatesEqualStates() {
        val store = ProjectionStateStore("carlink")
        assertEquals(ProjectionState.Idle, store.state)
        var calls = 0
        store.addListener { _, _ -> calls += 1 }
        store.publish(ProjectionState.Ready)
        store.publish(ProjectionState.Ready)
        assertEquals(1, calls)
        assertEquals(ProjectionState.Ready, store.state)
    }

    @Test
    fun listenersReceiveBackendId() {
        val store = ProjectionStateStore("carplay")
        val ids = mutableListOf<String>()
        store.addListener { backendId, _ -> ids.add(backendId) }
        store.publish(ProjectionState.Connected)
        assertEquals(listOf("carplay"), ids)
    }

    @Test
    fun errorStateKeepsCodeMessageCauseAndBackend() {
        val store = ProjectionStateStore("carlink")
        val cause = IllegalStateException("boom")
        store.publish(
            ProjectionState.Error(
                code = ProjectionErrorCode.CONNECT_FAILED,
                message = "no device",
                cause = cause,
                backendId = "carlink",
            ),
        )
        val error = store.state as ProjectionState.Error
        assertEquals(ProjectionErrorCode.CONNECT_FAILED, error.code)
        assertEquals("no device", error.message)
        assertEquals(cause, error.cause)
        assertEquals("carlink", error.backendId)
    }

    @Test
    fun listenerFailureDoesNotBreakOtherListeners() {
        val store = ProjectionStateStore("carlink")
        var reached = false
        store.addListener { _, _ -> throw IllegalStateException("bad listener") }
        store.addListener { _, _ -> reached = true }
        store.publish(ProjectionState.Discovering)
        assertTrue(reached)
    }

    @Test
    fun removeListenerStopsDelivery() {
        val store = ProjectionStateStore("carlink")
        var calls = 0
        val listener = ProjectionStateListener { _, _ -> calls += 1 }
        store.addListener(listener)
        store.publish(ProjectionState.Discovering)
        store.removeListener(listener)
        store.publish(ProjectionState.Connecting)
        assertEquals(1, calls)
    }

    @Test
    fun logNamesAreStableForDiagnostics() {
        assertEquals("DISCOVERING", ProjectionState.Discovering.logName)
        assertEquals("CONNECTING", ProjectionState.Connecting.logName)
        assertEquals("CONNECTED", ProjectionState.Connected.logName)
        assertEquals("DISCONNECTING", ProjectionState.Disconnecting.logName)
        assertEquals(
            "ERROR",
            ProjectionState.Error(ProjectionErrorCode.TIMEOUT, "t", null, "carlink").logName,
        )
    }
}
