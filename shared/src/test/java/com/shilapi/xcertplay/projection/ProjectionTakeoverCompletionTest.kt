package com.shilapi.xcertplay.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Takeover completion lifecycle: the caller must NOT start the target session
 * before onTakeoverActivated, and exactly one activation happens per takeover.
 */
class ProjectionTakeoverCompletionTest {

    private class SessionBackend(
        override val id: String,
        override val requiredResources: Set<ProjectionResource> = emptySet(),
    ) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        val stateStore = ProjectionStateStore(id)

        @Volatile
        var sessionActive = false

        var startCount = 0
        var stopRequested = 0

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
            stopRequested += 1
            // Synchronous session: the stop completes inline.
            sessionActive = false
            stateStore.publish(ProjectionState.Idle)
            return true
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}
    }

    /** Host-owned session: stop is asynchronous. */
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

        override fun disconnect(): Boolean {
            // Host-owned: only requests; the real stop is confirmed later.
            return !sessionActive
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}
    }

    private class RecordingTakeoverListener : ProjectionTakeoverListener {
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
    fun awaitingStopDoesNotActivateUntilTheSessionReallyStops() {
        val manager = ProjectionManager()
        val old = HostOwnedBackend("carlink", setOf(ProjectionResource.AUDIO))
        val target = SessionBackend("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(old)
        manager.register(target)
        manager.claim("carlink")

        val listener = RecordingTakeoverListener()
        val outcome = manager.takeover("carplay", null, listener)
        assertTrue(outcome is ProjectionTakeoverOutcome.AwaitingStop)
        // The caller must NOT have started the target session yet.
        assertEquals(0, target.startCount)
        assertTrue(listener.activated.isEmpty())

        // The host confirms the real stop: takeover completes and the
        // activation fires exactly once.
        old.sessionActive = false
        manager.onBackendSessionStopped("carlink")
        assertEquals(listOf("carplay"), listener.activated)
        assertEquals(1, target.startCount)
    }

    @Test
    fun takeoverBlockedNeverActivatesTheTarget() {
        val manager = ProjectionManager()
        val old = HostOwnedBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        val target = SessionBackend("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(old)
        manager.register(target)
        manager.claim("carlink")

        val listener = RecordingTakeoverListener()
        assertTrue(manager.takeover("carplay", null, listener) is ProjectionTakeoverOutcome.AwaitingStop)
        // The host never confirms and a conflicting connect stays blocked.
        assertEquals(0, target.startCount)
        assertTrue(listener.failed.isEmpty())

        // A foreign live session blocks the completion as well.
        val foreign = SessionBackend("carlife", setOf(ProjectionResource.USB))
        manager.register(foreign)
        foreign.sessionActive = true
        old.sessionActive = false
        manager.onBackendSessionStopped("carlink")
        assertEquals(0, target.startCount)
        assertEquals(1, listener.failed.size)
        assertEquals("carplay", listener.failed.single().first)
    }

    @Test
    fun synchronousTakeoverActivatesExactlyOnce() {
        val manager = ProjectionManager()
        val old = SessionBackend("carlink", setOf(ProjectionResource.AUDIO))
        val target = SessionBackend("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(old)
        manager.register(target)
        manager.connect(ProjectionDevice("d", "p", "carlink"))

        val listener = RecordingTakeoverListener()
        assertTrue(manager.takeover("carplay", null, listener) is ProjectionTakeoverOutcome.Activated)
        assertEquals(listOf("carplay"), listener.activated)
        assertEquals(1, target.startCount)
    }

    @Test
    fun unregisterWhileActiveStillReleasesAfterTheRealStop() {
        val manager = ProjectionManager()
        val old = HostOwnedBackend("carlink", setOf(ProjectionResource.AUDIO))
        manager.register(old)
        manager.claim("carlink")
        manager.unregister("carlink")
        // Session still running: the lease stays.
        assertEquals(setOf(ProjectionResource.AUDIO), manager.resourcesHeldBy("carlink"))

        // The unregistered backend can still confirm its stop (listener kept).
        old.sessionActive = false
        manager.onBackendSessionStopped("carlink")
        assertTrue(manager.resourcesHeldBy("carlink").isEmpty())
    }

    @Test
    fun failedConnectNeverStartsTheSession() {
        val manager = ProjectionManager()
        val blocking = HostOwnedBackend("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        val target = SessionBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(blocking)
        manager.register(target)
        manager.claim("carplay")

        val listener = RecordingTakeoverListener()
        // Direct connect is refused while the old session is live.
        try {
            manager.connect(ProjectionDevice("d", "p", "carlink"))
            throw AssertionError("expected conflict")
        } catch (expected: ProjectionResourceConflictException) {
            // Expected.
        }
        assertEquals(0, target.startCount)
        assertFalse(target.sessionActive)
        assertTrue(listener.activated.isEmpty())
    }

    @Test
    fun takeoverBlockedByRegistrationErrorNeverStarts() {
        val manager = ProjectionManager()
        val target = SessionBackend("carplay", setOf(ProjectionResource.USB))
        manager.register(target)
        val listener = RecordingTakeoverListener()
        // Unregistered target: blocked and reported, never started.
        val outcome = manager.takeover("missing", null, listener)
        assertTrue(outcome is ProjectionTakeoverOutcome.Blocked)
        assertEquals(1, listener.failed.size)
        assertEquals("missing", listener.failed.single().first)
    }
}
