package com.shilapi.xcertplay.projection

/** Shared hardware that only one projection backend may own at a time. */
enum class ProjectionResource {
    USB,
    BLUETOOTH,
    WIFI,
    AUDIO,
    MICROPHONE,
}

class ProjectionResourceConflictException(
    message: String,
    /** First conflicting resource, or null when the conflict is session-level. */
    val resource: ProjectionResource?,
    val ownerBackendId: String,
    val requestingBackendId: String,
    /** Every resource involved in the conflict. */
    val resources: Set<ProjectionResource> = resource?.let { setOf(it) } ?: emptySet(),
) : IllegalStateException(message)

/**
 * Arbitrates shared resources between projection backends.
 *
 * Only one backend may hold a given resource; a conflicting acquisition throws
 * [ProjectionResourceConflictException]. Claims are tracked per backend and all
 * of a backend's claims are released together when it disconnects.
 */
class ProjectionResourceCoordinator {
    private val lock = Any()
    private val owners = mutableMapOf<ProjectionResource, String>()
    private val claims = mutableMapOf<String, MutableSet<ProjectionResource>>()

    /** Acquires [resources] for [backendId]; throws when another backend owns one. */
    fun acquire(backendId: String, resources: Set<ProjectionResource>) {
        synchronized(lock) {
            val conflicts = resources.filter { owners[it] != null && owners[it] != backendId }
            conflicts.firstOrNull()?.let { held ->
                throw ProjectionResourceConflictException(
                    "resource=$held is owned by backend=${owners[held]} " +
                        "and cannot be acquired by backend=$backendId",
                    resource = held,
                    ownerBackendId = owners[held] ?: "unknown",
                    requestingBackendId = backendId,
                )
            }
            for (resource in resources) {
                owners[resource] = backendId
            }
            claims.getOrPut(backendId) { mutableSetOf() }.addAll(resources)
        }
    }

    /** Releases every resource held by [backendId]. Safe to call repeatedly. */
    fun release(backendId: String) {
        synchronized(lock) {
            val held = claims.remove(backendId) ?: return
            for (resource in held) {
                if (owners[resource] == backendId) owners.remove(resource)
            }
        }
    }

    /** Who currently owns [resource], or null. */
    fun ownerOf(resource: ProjectionResource): String? = synchronized(lock) { owners[resource] }

    /** Resources currently held by [backendId]. */
    fun heldBy(backendId: String): Set<ProjectionResource> =
        synchronized(lock) { claims[backendId]?.toSet() ?: emptySet() }
}
