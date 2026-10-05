package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionManager
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error severity semantics: a RECOVERABLE SDK error keeps the session and its
 * resources alive; a FATAL one tears everything down and latches Error so a
 * trailing onSessionEnded cannot overwrite the failure with a healthy state.
 */
class CarLinkErrorSeverityTest {

    private fun backendOf(
        adapter: MockCarLinkProtocolAdapter,
    ): Pair<ProjectionManager, CarLinkProjectionBackend> {
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        manager.register(backend)
        return manager to backend
    }

    private fun connectedSession(): Triple<MockCarLinkProtocolAdapter, ProjectionManager, CarLinkProjectionBackend> {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val (manager, backend) = backendOf(adapter)
        backend.initialize()
        backend.start()
        manager.connect(null)
        assertTrue(backend.isSessionActive)
        return Triple(adapter, manager, backend)
    }

    @Test
    fun recoverableErrorKeepsSessionAndResourcesAlive() {
        val (adapter, manager, backend) = connectedSession()
        val heldBefore = manager.resourcesHeldBy(CarLinkProjectionBackend.ID)
        assertTrue(heldBefore.isNotEmpty())

        adapter.simulateError("SOFT", "transient glitch", CarLinkErrorSeverity.RECOVERABLE)

        // Session survives: still active, still holding its resources.
        assertTrue(backend.isSessionActive)
        assertEquals(heldBefore, manager.resourcesHeldBy(CarLinkProjectionBackend.ID))
        // The state machine must not pretend the session is gone.
        assertTrue(backend.state is ProjectionState.Connected)
    }

    @Test
    fun fatalErrorTearsDownSessionClosesSinksAndReleasesResources() {
        var sinksClosed = 0
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(
                ProjectionVideoSink.NOOP,
                ProjectionAudioSink.NOOP,
                onClose = { sinksClosed++ },
            ),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        manager.connect(null)
        assertTrue(backend.isSessionActive)

        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)

        // Session torn down, media sinks closed, resources released, Error shown.
        assertTrue(!backend.isSessionActive)
        assertTrue("media sinks must close on fatal", sinksClosed >= 1)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
        val state = backend.state
        assertTrue("expected Error, got $state", state is ProjectionState.Error)
    }

    @Test
    fun fatalErrorIsNotOverwrittenByLaterSessionEnded() {
        val (adapter, _, backend) = connectedSession()

        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)
        // The SDK afterwards reports the session end of the SAME failing
        // session — this must NOT reset the state to Ready.
        adapter.simulateSessionEnd("cable pulled")

        val state = backend.state
        assertTrue("expected latched Error, got $state", state is ProjectionState.Error)
        assertNotNull((state as ProjectionState.Error).message)
    }

    @Test
    fun nextInitializeClearsTheFatalLatch() {
        val (adapter, _, backend) = connectedSession()
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)
        assertTrue(backend.state is ProjectionState.Error)

        // A new user-driven operation may leave the Error state.
        backend.initialize()
        assertEquals(ProjectionState.Ready, backend.state)
    }

    @Test
    fun nextConnectClearsTheFatalLatch() {
        val (adapter, manager, backend) = connectedSession()
        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)
        assertTrue(backend.state is ProjectionState.Error)

        manager.connect(null)
        assertEquals(ProjectionState.Connected, backend.state)
        assertTrue(backend.isSessionActive)
    }

    @Test
    fun fatalErrorFromAsyncSdKReleasesResourcesExactlyOnce() {
        val adapter = MockCarLinkProtocolAdapter(streamFakeMedia = false)
        val manager = ProjectionManager()
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP),
        )
        manager.register(backend)
        backend.initialize()
        backend.start()
        manager.connect(null)
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isNotEmpty())

        adapter.simulateError("LINK_LOST", "cable pulled", CarLinkErrorSeverity.FATAL)

        // The manager (the real session-stopped listener) released the lease.
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
        // A repeated end-of-session signal after the fatal error must not
        // corrupt anything (release is idempotent, state stays latched).
        adapter.simulateSessionEnd("already gone")
        assertTrue(manager.resourcesHeldBy(CarLinkProjectionBackend.ID).isEmpty())
        assertTrue(backend.state is ProjectionState.Error)
    }
}
