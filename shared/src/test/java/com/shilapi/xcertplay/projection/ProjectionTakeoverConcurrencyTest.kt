package com.shilapi.xcertplay.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Takeover concurrency policy: a second takeover while one is awaiting an old
 * session's stop is refused with a clear TAKEOVER_IN_PROGRESS reason — the
 * pending takeover and its listener are never silently replaced.
 */
class ProjectionTakeoverConcurrencyTest {

    private class HostOwnedBackend(
        override val id: String,
        override val requiredResources: Set<ProjectionResource>,
    ) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        val stateStore = ProjectionStateStore(id)

        @Volatile
        var sessionActive = true

        override val state: ProjectionState get() = stateStore.state
        override val isSessionActive: Boolean get() = sessionActive

        override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() {}
        override fun connect(device: ProjectionDevice?) {}

        override fun disconnect(): Boolean = !sessionActive
        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}
    }

    private class SessionBackend(
        override val id: String,
        override val requiredResources: Set<ProjectionResource>,
    ) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        val stateStore = ProjectionStateStore(id)

        @Volatile
        var sessionActive = false

        var startCount = 0

        override val state: ProjectionState get() = stateStore.state
        override val isSessionActive: Boolean get() = sessionActive

        override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() {}
        override fun connect(device: ProjectionDevice?) {
            startCount += 1
            sessionActive = true
            stateStore.publish(ProjectionState.Connected)
        }

        override fun disconnect(): Boolean {
            sessionActive = false
            return true
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}
    }

    private class RecordingListener : ProjectionTakeoverListener {
        val activated = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        override fun onTakeoverActivated(targetId: String) {
            activated.add(targetId)
        }

        override fun onTakeoverFailed(targetId: String, message: String) {
            failed.add(targetId to message)
        }
    }

    @Test
    fun secondTakeoverWhilePendingIsBlockedNotReplaced() {
        val manager = ProjectionManager()
        val old = HostOwnedBackend("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        val carlink = SessionBackend("carlink", setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO))
        val other = SessionBackend("other", setOf(ProjectionResource.USB))
        manager.register(old)
        manager.register(carlink)
        manager.register(other)
        manager.claim("carplay")

        val first = RecordingListener()
        val outcome1 = manager.takeover("carlink", null, first)
        assertTrue(outcome1 is ProjectionTakeoverOutcome.AwaitingStop)

        // Second takeover while the first is pending: refused with a clear
        // reason and its listener informed — never a silent replacement.
        val second = RecordingListener()
        val outcome2 = manager.takeover("other", null, second)
        assertTrue(outcome2 is ProjectionTakeoverOutcome.Blocked)
        assertEquals(1, second.failed.size)
        assertTrue(
            "expected TAKEOVER_IN_PROGRESS, got: ${second.failed.single().second}",
            second.failed.single().second.contains("TAKEOVER_IN_PROGRESS"),
        )

        // The FIRST request still completes normally when the old session stops.
        old.sessionActive = false
        manager.onBackendSessionStopped("carplay")
        assertEquals(listOf("carlink"), first.activated)
        assertEquals(1, carlink.startCount)
        assertEquals(0, other.startCount)
    }

    @Test
    fun pendingTakeoverListenerIsNeverLost() {
        val manager = ProjectionManager()
        val old = HostOwnedBackend("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        val carlink = SessionBackend("carlink", setOf(ProjectionResource.WIFI, ProjectionResource.AUDIO))
        manager.register(old)
        manager.register(carlink)
        manager.claim("carplay")

        val first = RecordingListener()
        assertTrue(manager.takeover("carlink", null, first) is ProjectionTakeoverOutcome.AwaitingStop)

        // Spam a few more takeovers: all refused, first listener intact.
        repeat(3) {
            val extra = RecordingListener()
            assertTrue(manager.takeover("other", null, extra) is ProjectionTakeoverOutcome.Blocked)
        }

        old.sessionActive = false
        manager.onBackendSessionStopped("carplay")
        assertEquals(listOf("carlink"), first.activated)
    }
}
