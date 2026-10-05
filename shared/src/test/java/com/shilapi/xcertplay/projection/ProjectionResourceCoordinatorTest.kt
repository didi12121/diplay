package com.shilapi.xcertplay.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Shared hardware arbitration between projection backends. */
class ProjectionResourceCoordinatorTest {

    @Test
    fun oneBackendOwnsEachResource() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire("carplay", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        assertEquals("carplay", coordinator.ownerOf(ProjectionResource.USB))
        assertEquals("carplay", coordinator.ownerOf(ProjectionResource.AUDIO))
        assertNull(coordinator.ownerOf(ProjectionResource.WIFI))
    }

    @Test
    fun conflictingAcquisitionThrowsWithAttribution() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire("carplay", setOf(ProjectionResource.USB))
        try {
            coordinator.acquire("carlink", setOf(ProjectionResource.USB))
            fail("expected ProjectionResourceConflictException")
        } catch (conflict: ProjectionResourceConflictException) {
            assertEquals(ProjectionResource.USB, conflict.resource)
            assertEquals("carplay", conflict.ownerBackendId)
            assertEquals("carlink", conflict.requestingBackendId)
        }
    }

    @Test
    fun sameBackendMayReacquireItsResources() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire("carplay", setOf(ProjectionResource.USB))
        coordinator.acquire("carplay", setOf(ProjectionResource.USB))
        assertEquals("carplay", coordinator.ownerOf(ProjectionResource.USB))
    }

    @Test
    fun releaseFreesEveryHeldResource() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire(
            "carlink",
            setOf(ProjectionResource.USB, ProjectionResource.WIFI, ProjectionResource.AUDIO),
        )
        coordinator.release("carlink")
        assertTrue(coordinator.heldBy("carlink").isEmpty())
        assertNull(coordinator.ownerOf(ProjectionResource.USB))
        assertNull(coordinator.ownerOf(ProjectionResource.WIFI))
    }

    @Test
    fun releaseIsIdempotent() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire("carplay", setOf(ProjectionResource.MICROPHONE))
        coordinator.release("carplay")
        coordinator.release("carplay")
        assertNull(coordinator.ownerOf(ProjectionResource.MICROPHONE))
    }

    @Test
    fun releaseOfOneBackendDoesNotStealAnotherOwnersResource() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire("carplay", setOf(ProjectionResource.AUDIO))
        // A stale release for a backend that never owned AUDIO must be a no-op.
        coordinator.release("carlink")
        assertEquals("carplay", coordinator.ownerOf(ProjectionResource.AUDIO))
    }

    @Test
    fun heldByReportsExactResources() {
        val coordinator = ProjectionResourceCoordinator()
        coordinator.acquire("carlink", setOf(ProjectionResource.USB, ProjectionResource.AUDIO))
        assertEquals(setOf(ProjectionResource.USB, ProjectionResource.AUDIO), coordinator.heldBy("carlink"))
    }
}
