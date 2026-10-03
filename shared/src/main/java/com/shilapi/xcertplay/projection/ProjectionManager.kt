package com.shilapi.xcertplay.projection

/** Which backend a user (or auto-selection) picked. */
enum class ProjectionMode {
    /** Prefer whichever backend reports a device first. */
    AUTO,
    CARPLAY,
    CARLINK,
}

/** Notified when the active backend or mode changes. Any thread. */
fun interface ProjectionSelectionListener {
    fun onActiveBackendChanged(backendId: String?, mode: ProjectionMode)
}

/**
 * Front door of the projection host: registers backends, arbitrates shared
 * resources, selects and switches the active backend, and mirrors backend state
 * to listeners. It holds no protocol logic — everything protocol specific stays
 * inside the backend.
 *
 * Usage:
 * ```
 * projectionManager.register(carPlayBackend)
 * projectionManager.register(carLinkBackend)
 * projectionManager.select(ProjectionMode.CARLINK)
 * projectionManager.connect(device = null)
 * ```
 */
class ProjectionManager(
    private val logger: ProjectionLogger = ProjectionLogger.NONE,
    private val coordinator: ProjectionResourceCoordinator = ProjectionResourceCoordinator(),
) {
    private val lock = Any()
    private val backends = linkedMapOf<String, ProjectionBackend>()
    private val selectionListeners = java.util.concurrent.CopyOnWriteArrayList<ProjectionSelectionListener>()
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<ProjectionStateListener>()

    /** Bridges backend state changes to manager-level listeners. */
    private val backendStateBridge = ProjectionStateListener { backendId, state ->
        logger.log("manager backend=$backendId state=${state.logName}")
        for (listener in stateListeners) {
            try {
                listener.onStateChanged(backendId, state)
            } catch (error: Exception) {
                logger.log("manager state-listener-failed ${error.javaClass.simpleName}")
            }
        }
    }

    @Volatile
    var mode: ProjectionMode = ProjectionMode.AUTO
        private set

    @Volatile
    private var activeBackendId: String? = null

    /** The backend currently allowed to own the shared resources, if any. */
    val activeBackend: ProjectionBackend?
        get() = activeBackendId?.let { backend(it) }

    val registeredBackends: List<ProjectionBackend>
        get() = synchronized(lock) { backends.values.toList() }

    /** Registers [backend]; replaces any previous backend with the same id. */
    fun register(backend: ProjectionBackend) {
        val previous: ProjectionBackend?
        synchronized(lock) {
            previous = backends.put(backend.id, backend)
        }
        previous?.let { old ->
            old.removeStateListener(backendStateBridge)
            if (activeBackendId == old.id) activeBackendId = null
            // A replaced backend must never keep hardware claims alive.
            deactivateLocked(old)
        }
        backend.addStateListener(backendStateBridge)
        logger.log("manager registered backend=${backend.id} name=${backend.displayName}")
    }

    fun unregister(backendId: String) {
        val removed: ProjectionBackend?
        synchronized(lock) {
            removed = backends.remove(backendId)
        }
        removed?.let {
            it.removeStateListener(backendStateBridge)
            if (activeBackendId == it.id) activeBackendId = null
            deactivateLocked(it)
        }
    }

    fun backend(id: String): ProjectionBackend? = synchronized(lock) { backends[id] }

    /**
     * Chooses the active backend. Explicit modes pick that backend when it is
     * registered; AUTO follows device detection (a backend with a live session
     * wins) and otherwise prefers CarPlay, then CarLink.
     */
    fun select(mode: ProjectionMode) {
        val chosen: ProjectionBackend? = synchronized(lock) {
            this.mode = mode
            when (mode) {
                ProjectionMode.AUTO -> pickAuto()
                ProjectionMode.CARPLAY -> backends[CARPLAY_ID]
                ProjectionMode.CARLINK -> backends[CARLINK_ID]
            }
        }
        switchActive(chosen)
    }

    /**
     * Connects the active backend. In AUTO mode a backend that reports a
     * discovered [device]'s id wins; passing null connects the active backend to
     * its first discovered device.
     *
     * Shared resources are acquired *before* switching so a live session in
     * another backend raises [ProjectionResourceConflictException] instead of
     * being silently torn down.
     */
    fun connect(device: ProjectionDevice?) {
        val target = resolveTarget(device)
            ?: throw IllegalStateException("no projection backend is registered for connect()")
        val resources = target.requiredResources
        if (resources.isNotEmpty()) {
            try {
                coordinator.acquire(target.id, resources)
            } catch (conflict: ProjectionResourceConflictException) {
                logger.log(
                    "manager connect-rejected backend=${target.id} " +
                        "resource=${conflict.resource} owner=${conflict.ownerBackendId}",
                )
                throw conflict
            }
        }
        switchActive(target)
        logger.log("manager connect backend=${target.id} device=${device?.id ?: "first"}")
        target.connect(device)
    }

    /**
     * Claims [backendId]'s shared resources without starting it, e.g. when a
     * host activity brings a session up outside [connect]. Throws on conflict.
     */
    fun claim(backendId: String) {
        val target = backend(backendId)
            ?: throw IllegalStateException("backend=$backendId is not registered")
        val resources = target.requiredResources
        if (resources.isNotEmpty()) coordinator.acquire(target.id, resources)
    }

    /** Disconnects the active backend and releases every resource it held. */
    fun disconnect() {
        val target = synchronized(lock) { backends[activeBackendId] ?: return }
        disconnectBackend(target.id)
    }

    /** Disconnects [backendId] specifically and releases its shared resources. */
    fun disconnectBackend(backendId: String) {
        val target = backend(backendId) ?: return
        logger.log("manager disconnect backend=$backendId")
        try {
            target.disconnect()
        } finally {
            coordinator.release(backendId)
        }
    }

    /**
     * Releases all resources and stops [backendId] — used by recovery paths and
     * when another backend takes over the shared hardware (e.g. USB CarPlay
     * starting while a CarLink session is up).
     */
    fun releaseBackend(backendId: String) {
        val target = backend(backendId) ?: return
        logger.log("manager release backend=$backendId")
        try {
            target.disconnect()
            target.stop()
        } finally {
            coordinator.release(backendId)
        }
    }

    fun addSelectionListener(listener: ProjectionSelectionListener) {
        selectionListeners.addIfAbsent(listener)
    }

    fun removeSelectionListener(listener: ProjectionSelectionListener) {
        selectionListeners.remove(listener)
    }

    fun addStateListener(listener: ProjectionStateListener) {
        stateListeners.addIfAbsent(listener)
    }

    fun removeStateListener(listener: ProjectionStateListener) {
        stateListeners.remove(listener)
    }

    /** Resources currently held per backend id; for diagnostics and tests. */
    fun resourcesHeldBy(backendId: String): Set<ProjectionResource> = coordinator.heldBy(backendId)

    private fun resolveTarget(device: ProjectionDevice?): ProjectionBackend? = synchronized(lock) {
        if (device != null) {
            backends[device.backendId]?.let { return it }
        }
        activeBackendId?.let { backends[it] } ?: pickAuto()
    }

    private fun pickAuto(): ProjectionBackend? {
        // Device detection first: a backend with a live session wins. Then the
        // fixed preference order CarPlay > CarLink for starting something new.
        val all = backends.values
        return all.firstOrNull { it.state == ProjectionState.Connected }
            ?: backends[CARPLAY_ID]
            ?: backends[CARLINK_ID]
            ?: all.firstOrNull()
    }

    private fun switchActive(chosen: ProjectionBackend?) {
        val previous: ProjectionBackend?
        synchronized(lock) {
            val currentId = activeBackendId
            val chosenId = chosen?.id
            if (currentId == chosenId) return
            previous = currentId?.let { backends[it] }
            activeBackendId = chosenId
        }
        previous?.let { deactivateLocked(it) }
        logger.log("manager active backend=${chosen?.id ?: "none"} mode=$mode")
        for (listener in selectionListeners) {
            try {
                listener.onActiveBackendChanged(chosen?.id, mode)
            } catch (error: Exception) {
                logger.log("manager selection-listener-failed ${error.javaClass.simpleName}")
            }
        }
    }

    private fun deactivateLocked(backend: ProjectionBackend) {
        try {
            backend.disconnect()
        } catch (error: Exception) {
            logger.log("manager deactivate-failed backend=${backend.id} ${error.javaClass.simpleName}")
        }
        coordinator.release(backend.id)
    }

    companion object {
        const val CARPLAY_ID = "carplay"
        const val CARLINK_ID = "carlink"
    }
}
