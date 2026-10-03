package com.shilapi.xcertplay.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for the resource-arbitration lifecycle:
 *
 * ```
 *   select()  ──► preference only          (no release, no stop)
 *   connect() ──► refused while another session is live (RESOURCE_CONFLICT)
 *   takeover()──► stop old → wait actual stop → release → activate new
 *   disconnect() ► release ONLY after actual session teardown
 * ```
 */
class ProjectionResourceLifecycleTest {

    /**
     * A backend whose session is externally owned (like the wrapped CarPlay
     * controller): disconnect() merely requests the stop and keeps reporting an
     * active session until [simulateActualStop] runs.
     */
    private class ExternallyOwnedBackend(
        override val id: String,
        override val requiredResources: Set<ProjectionResource>,
    ) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        val stateStore = ProjectionStateStore(id)
        override val state: ProjectionState get() = stateStore.state

        @Volatile
        var sessionActive: Boolean = true

        @Volatile
        var stopRequested: Boolean = false

        var connectCount = 0

        override val isSessionActive: Boolean
            get() = sessionActive

        override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() {}

        override fun connect(device: ProjectionDevice?) {
            connectCount += 1
            sessionActive = true
            stateStore.publish(ProjectionState.Connected)
        }

        override fun disconnect(): Boolean {
            // Host-owned session: request only; the real stop happens later.
            stopRequested = true
            return !sessionActive
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}

        /** The host confirms its controller really closed. */
        fun simulateActualStop() {
            sessionActive = false
            stateStore.publish(ProjectionState.Idle)
        }
    }

    /** Backend that stops synchronously (like the mock CarLink adapter). */
    private class SyncBackend(
        override val id: String,
        override val requiredResources: Set<ProjectionResource> = emptySet(),
    ) : ProjectionBackend {
        override val displayName: String = id
        override val capabilities: ProjectionCapabilities = ProjectionCapabilities()
        val stateStore = ProjectionStateStore(id)
        override val state: ProjectionState get() = stateStore.state

        @Volatile
        var sessionActive = false

        var connectCount = 0

        override val isSessionActive: Boolean
            get() = sessionActive

        override fun addStateListener(listener: ProjectionStateListener) = stateStore.addListener(listener)
        override fun removeStateListener(listener: ProjectionStateListener) = stateStore.removeListener(listener)
        override fun initialize() {}
        override fun start() {}
        override fun stop() {}
        override fun connect(device: ProjectionDevice?) {
            connectCount += 1
            sessionActive = true
            stateStore.publish(ProjectionState.Connected)
        }

        override fun disconnect(): Boolean {
            sessionActive = false
            stateStore.publish(ProjectionState.Idle)
            return true
        }

        override fun onTouchEvent(event: ProjectionTouchEvent) = true
        override fun onKeyEvent(event: ProjectionKeyEvent) = true
        override fun close() {}
    }

    private fun carPlayResources(): Set<ProjectionResource> = setOf(
        ProjectionResource.USB,
        ProjectionResource.AUDIO,
        ProjectionResource.MICROPHONE,
    )

    @Test
    fun wrappedLiveCarPlayCannotHaveResourcesReleasedBySelectingCarLink() {
        val manager = ProjectionManager()
        val carPlay = ExternallyOwnedBackend("carplay", carPlayResources())
        val carLink = SyncBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(carPlay)
        manager.register(carLink)
        manager.claim("carplay")
        assertEquals(carPlayResources(), manager.resourcesHeldBy("carplay"))

        // Selecting another protocol must not fake-release a live session.
        manager.select(ProjectionMode.CARLINK)
        assertTrue(carPlay.sessionActive)
        assertFalse(carPlay.stopRequested)
        assertEquals(carPlayResources(), manager.resourcesHeldBy("carplay"))
    }

    @Test
    fun carLinkConnectIsRejectedWhileRealCarPlaySessionOwnsResources() {
        val manager = ProjectionManager()
        val carPlay = ExternallyOwnedBackend("carplay", carPlayResources())
        val carLink = SyncBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(carPlay)
        manager.register(carLink)
        manager.claim("carplay")
        manager.select(ProjectionMode.CARLINK)

        try {
            manager.connect(null)
            fail("expected ProjectionResourceConflictException")
        } catch (conflict: ProjectionResourceConflictException) {
            assertEquals("carplay", conflict.ownerBackendId)
            assertEquals("carlink", conflict.requestingBackendId)
        }
        assertFalse(carLink.sessionActive)
        assertEquals(0, carLink.connectCount)
    }

    @Test
    fun afterCarPlayActualShutdownCarLinkMayAcquireResources() {
        val manager = ProjectionManager()
        val carPlay = ExternallyOwnedBackend("carplay", carPlayResources())
        val carLink = SyncBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(carPlay)
        manager.register(carLink)
        manager.claim("carplay")

        // The host really stops the CarPlay controller and confirms it.
        carPlay.simulateActualStop()
        manager.onBackendSessionStopped("carplay")

        assertTrue(manager.resourcesHeldBy("carplay").isEmpty())
        manager.select(ProjectionMode.CARLINK)
        manager.connect(null)
        assertTrue(carLink.sessionActive)
        assertEquals(setOf(ProjectionResource.USB, ProjectionResource.AUDIO), manager.resourcesHeldBy("carlink"))
    }

    @Test
    fun disconnectOnlyReleasesResourcesAfterActualSessionTeardown() {
        val manager = ProjectionManager()
        val carPlay = ExternallyOwnedBackend("carplay", carPlayResources())
        manager.register(carPlay)
        manager.claim("carplay")

        // The stop is asynchronous: resources must NOT be released yet.
        assertFalse(manager.disconnectBackend("carplay"))
        assertTrue(carPlay.stopRequested)
        assertEquals(carPlayResources(), manager.resourcesHeldBy("carplay"))

        // The host confirms the real stop: only now the resources are freed.
        carPlay.simulateActualStop()
        manager.onBackendSessionStopped("carplay")
        assertTrue(manager.resourcesHeldBy("carplay").isEmpty())
    }

    @Test
    fun takeoverStopsOldSessionBeforeActivatingTheNewOne() {
        val manager = ProjectionManager()
        val carPlay = ExternallyOwnedBackend("carplay", carPlayResources())
        val carLink = SyncBackend("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        manager.register(carPlay)
        manager.register(carLink)
        manager.claim("carplay")

        // First takeover attempt: the old session cannot stop instantly.
        val outcome = manager.takeover("carlink", null)
        assertTrue("expected AwaitingStop, got $outcome", outcome is ProjectionTakeoverOutcome.AwaitingStop)
        assertTrue(carPlay.stopRequested)
        assertEquals(0, carLink.connectCount)
        // Resources still belong to the live session.
        assertEquals(carPlayResources(), manager.resourcesHeldBy("carplay"))

        // The host confirms the actual stop: the takeover completes.
        carPlay.simulateActualStop()
        manager.onBackendSessionStopped("carplay")
        assertTrue(carLink.sessionActive)
        assertTrue(manager.resourcesHeldBy("carplay").isEmpty())
        assertEquals(setOf(ProjectionResource.USB, ProjectionResource.AUDIO), manager.resourcesHeldBy("carlink"))
    }

    @Test
    fun takeoverWithSynchronousStopActivatesImmediately() {
        val manager = ProjectionManager()
        val old = SyncBackend("carlink", setOf(ProjectionResource.AUDIO))
        val target = SyncBackend("carplay", carPlayResources())
        manager.register(old)
        manager.register(target)
        manager.connect(ProjectionDevice("d1", "phone", "carlink"))
        assertTrue(old.sessionActive)

        val outcome = manager.takeover("carplay", null)
        assertTrue("expected Activated, got $outcome", outcome is ProjectionTakeoverOutcome.Activated)
        assertFalse(old.sessionActive)
        assertTrue(target.sessionActive)
        assertTrue(manager.resourcesHeldBy("carlink").isEmpty())
    }

    @Test
    fun unregisterWithLiveSessionDefersResourceRelease() {
        val manager = ProjectionManager()
        val carPlay = ExternallyOwnedBackend("carplay", carPlayResources())
        manager.register(carPlay)
        manager.claim("carplay")

        manager.unregister("carplay")
        // Session still running: hardware stays claimed even after unregister.
        assertEquals(carPlayResources(), manager.resourcesHeldBy("carplay"))

        carPlay.simulateActualStop()
        manager.onBackendSessionStopped("carplay")
        assertTrue(manager.resourcesHeldBy("carplay").isEmpty())
    }

}
