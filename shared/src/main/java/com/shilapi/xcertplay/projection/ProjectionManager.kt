package com.shilapi.xcertplay.projection

/** Which backend a user (or auto-selection) picked. */
enum class ProjectionMode {
    /** Prefer whichever backend reports a device first. */
    AUTO,
    CARPLAY,
    CARLINK,
}

/** Notified when the selected/active backend or mode changes. Any thread. */
fun interface ProjectionSelectionListener {
    fun onActiveBackendChanged(backendId: String?, mode: ProjectionMode)
}

/**
 * Result of an explicit, controlled [ProjectionManager.takeover].
 */
sealed class ProjectionTakeoverOutcome {
    /** The target backend is active and connected. */
    data class Activated(val backendId: String) : ProjectionTakeoverOutcome()

    /** An old session is stopping asynchronously; the owner must confirm via
     *  [ProjectionManager.onBackendSessionStopped], which completes the takeover. */
    data class AwaitingStop(val blockingBackendId: String) : ProjectionTakeoverOutcome()

    /** The takeover was refused; resources are untouched. */
    data class Blocked(val conflict: ProjectionResourceConflictException) : ProjectionTakeoverOutcome()
}

/**
 * Completion events of a [ProjectionManager.takeover]. AwaitingStop takeovers
 * complete asynchronously — exactly one of these callbacks fires then, and
 * [onTakeoverActivated] means the target backend is now really active and the
 * caller may start its session (e.g. `CarPlayController.start()`).
 */
interface ProjectionTakeoverListener {
    /** The target backend completed takeover and owns the shared resources. */
    fun onTakeoverActivated(targetId: String)

    /** The takeover failed; the target was NOT started and owns nothing. */
    fun onTakeoverFailed(targetId: String, message: String)
}

/**
 * Front door of the projection host: registers backends, arbitrates shared
 * resources, selects and switches the active backend, and mirrors backend state
 * to listeners. It holds no protocol logic — everything protocol specific stays
 * inside the backend.
 *
 * **Lifecycle model** (the resource-arbitration invariant):
 *
 *  - *selection* ([select]) is a user preference only. It never stops a session
 *    and never releases shared resources — a running protocol is unaffected.
 *  - *connection* ([connect]) claims resources and is refused with
 *    [ProjectionResourceConflictException] while another backend's real session
 *    is still running (`ProjectionBackend.isSessionActive`).
 *  - *takeover* ([takeover]) is the only controlled way to switch a live
 *    session off: request stop → **wait until actually stopped** → release
 *    resources → activate the target. A wrapped, externally-owned session
 *    (e.g. CarPlay) answers "still stopping" and its owner confirms later with
 *    [onBackendSessionStopped]; resources stay claimed until then.
 *  - *deactivation* ([disconnectBackend]) releases resources only after the
 *    underlying session is confirmed stopped — never merely because a
 *    disconnect was requested.
 */
class ProjectionManager(
    private val logger: ProjectionLogger = ProjectionLogger.NONE,
    private val coordinator: ProjectionResourceCoordinator = ProjectionResourceCoordinator(),
) {
    private val lock = Any()
    private val backends = linkedMapOf<String, ProjectionBackend>()
    private val selectionListeners = java.util.concurrent.CopyOnWriteArrayList<ProjectionSelectionListener>()
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<ProjectionStateListener>()

    /** Target of a takeover waiting for an old session to actually stop. */
    private data class PendingTakeover(
        val targetId: String,
        val device: ProjectionDevice?,
        val listener: ProjectionTakeoverListener?,
    )

    @Volatile
    private var pendingTakeover: PendingTakeover? = null

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

    /** The backend currently selected / allowed to own the shared resources. */
    val activeBackend: ProjectionBackend?
        get() = activeBackendId?.let { backend(it) }

    val registeredBackends: List<ProjectionBackend>
        get() = synchronized(lock) { backends.values.toList() }

    /**
     * Registers [backend]; replaces any previous registration with the same id.
     * Registration never touches live sessions: a replacement (e.g. a new
     * wrapper around the same externally-owned session) keeps the id's resource
     * claims intact.
     */
    fun register(backend: ProjectionBackend) {
        val previous: ProjectionBackend?
        synchronized(lock) {
            previous = backends.put(backend.id, backend)
        }
        previous?.let { old ->
            old.removeStateListener(backendStateBridge)
            old.setSessionStoppedListener(null)
            logger.log("manager replaced backend=${backend.id} sessionActive=${old.isSessionActive}")
        }
        backend.addStateListener(backendStateBridge)
        // Backends with asynchronous teardown report their real stop here.
        backend.setSessionStoppedListener { onBackendSessionStopped(backend.id) }
        logger.log("manager registered backend=${backend.id} name=${backend.displayName}")
    }

    /**
     * Removes [backendId] from the registry. Shared resources are released only
     * when the backend's session is confirmed stopped; a still-running session
     * keeps its claims until [onBackendSessionStopped] — the session-stopped
     * listener is deliberately kept so an unregistered backend can still
     * confirm its real stop and free its lease.
     */
    fun unregister(backendId: String) {
        val removed: ProjectionBackend?
        synchronized(lock) {
            removed = backends.remove(backendId)
        }
        removed?.let {
            it.removeStateListener(backendStateBridge)
            if (activeBackendId == it.id) activeBackendId = null
            if (!it.isSessionActive) {
                coordinator.release(backendId)
            } else {
                logger.log("manager unregister-deferred backend=$backendId session-still-active")
            }
        }
    }

    fun backend(id: String): ProjectionBackend? = synchronized(lock) { backends[id] }

    /**
     * Records the user's backend preference. This is **selection only**: it
     * never disconnects a live session and never releases shared resources.
     * Switching a running protocol off requires [takeover] or an explicit
     * disconnect.
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
        synchronized(lock) { activeBackendId = chosen?.id }
        logger.log("manager selected backend=${chosen?.id ?: "none"} mode=$mode (no session change)")
        notifySelection(chosen?.id)
    }

    /**
     * Connects the resolved backend. Throws [ProjectionResourceConflictException]
     * while another backend's session is actually running — the caller must use
     * [takeover] for a controlled switch. Stale claims of confirmed-stopped
     * backends are cleaned up first.
     */
    fun connect(device: ProjectionDevice?) {
        val target = resolveTarget(device)
            ?: throw IllegalStateException("no projection backend is registered for connect()")
        connectInternal(target, device)
    }

    /**
     * Claims [backendId]'s shared resources without starting it, e.g. when a
     * host activity brings a session up outside [connect]. Throws on conflict.
     */
    fun claim(backendId: String) {
        val target = backend(backendId)
            ?: throw IllegalStateException("backend=$backendId is not registered")
        cleanupStoppedClaimsExcept(target.id)
        val resources = target.requiredResourcesFor(null)
        if (resources.isNotEmpty()) coordinator.acquire(target.id, resources)
    }

    /**
     * Explicit, controlled switch to [targetId]: requests every live session to
     * stop, releases resources only after the sessions are confirmed stopped,
     * then activates the target. Returns [ProjectionTakeoverOutcome.AwaitingStop]
     * when an externally-owned session is still tearing down; the pending
     * takeover completes automatically on [onBackendSessionStopped] and fires
     * [listener] — the caller must NOT start the target session before
     * [ProjectionTakeoverListener.onTakeoverActivated].
     */
    fun takeover(
        targetId: String,
        device: ProjectionDevice? = null,
        listener: ProjectionTakeoverListener? = null,
    ): ProjectionTakeoverOutcome {
        // Explicit policy: one takeover at a time. A second request while one
        // is awaiting an old session's stop is refused with a clear reason —
        // it must never silently replace the pending takeover (that would drop
        // the first listener and could activate the wrong target).
        val pendingNow = synchronized(lock) { pendingTakeover }
        if (pendingNow != null) {
            val message = "TAKEOVER_IN_PROGRESS: takeover to ${pendingNow.targetId} is awaiting a stop"
            logger.log("manager takeover-rejected $message")
            listener?.onTakeoverFailed(targetId, message)
            return ProjectionTakeoverOutcome.Blocked(
                ProjectionResourceConflictException(
                    message,
                    resource = null,
                    ownerBackendId = pendingNow.targetId,
                    requestingBackendId = targetId,
                ),
            )
        }
        val target = backend(targetId)
            ?: return ProjectionTakeoverOutcome.Blocked(
                ProjectionResourceConflictException(
                    "backend=$targetId is not registered",
                    resource = null,
                    ownerBackendId = "none",
                    requestingBackendId = targetId,
                ),
            ).also { listener?.onTakeoverFailed(targetId, "backend=$targetId is not registered") }
        val blockers = registeredBackends.filter { it.id != targetId && it.isSessionActive }
        for (blocker in blockers) {
            val stopped = requestStop(blocker)
            if (stopped) coordinator.release(blocker.id)
        }
        val remaining = blockers.firstOrNull { it.isSessionActive }
        if (remaining != null) {
            val claimed = synchronized(lock) {
                if (pendingTakeover != null) {
                    false
                } else {
                    pendingTakeover = PendingTakeover(targetId, device, listener)
                    true
                }
            }
            if (!claimed) {
                val message = "TAKEOVER_IN_PROGRESS: another takeover became pending concurrently"
                listener?.onTakeoverFailed(targetId, message)
                return ProjectionTakeoverOutcome.Blocked(
                    ProjectionResourceConflictException(
                        message,
                        resource = null,
                        ownerBackendId = remaining.id,
                        requestingBackendId = targetId,
                    ),
                )
            }
            logger.log("manager takeover-awaiting backend=${remaining.id} target=$targetId")
            return ProjectionTakeoverOutcome.AwaitingStop(remaining.id)
        }
        return try {
            connectInternal(target, device)
            listener?.onTakeoverActivated(targetId)
            ProjectionTakeoverOutcome.Activated(targetId)
        } catch (conflict: ProjectionResourceConflictException) {
            listener?.onTakeoverFailed(targetId, conflict.message ?: "resource conflict")
            ProjectionTakeoverOutcome.Blocked(conflict)
        }
    }

    /**
     * Owner callback: the underlying session of [backendId] has **actually**
     * stopped. Releases its shared resources and completes any pending
     * takeover. Ignored when a new session is already running (rapid restart).
     *
     * Works even after [unregister]: backend registration lifetime and
     * resource-lease lifetime are deliberately decoupled — a lease is only
     * dropped on this confirmation (or an explicit force release).
     */
    fun onBackendSessionStopped(backendId: String) {
        val backend = backend(backendId)
        if (backend != null && backend.isSessionActive) {
            logger.log("manager session-stop-ignored backend=$backendId new-session-active")
            return
        }
        logger.log("manager session-confirmed-stopped backend=$backendId")
        coordinator.release(backendId)
        val pending = synchronized(lock) {
            pendingTakeover.also { pendingTakeover = null }
        } ?: return
        val target = backend(pending.targetId)
        if (target == null) {
            pending.listener?.onTakeoverFailed(pending.targetId, "target backend vanished")
            return
        }
        try {
            connectInternal(target, pending.device)
            pending.listener?.onTakeoverActivated(pending.targetId)
        } catch (conflict: ProjectionResourceConflictException) {
            logger.log("manager takeover-completed-with-conflict ${conflict.message}")
            pending.listener?.onTakeoverFailed(pending.targetId, conflict.message ?: "resource conflict")
        }
    }

    /** Disconnects the active backend. */
    fun disconnect(): Boolean {
        val target = synchronized(lock) { backends[activeBackendId] ?: return true }
        return disconnectBackend(target.id)
    }

    /**
     * Disconnects [backendId]. Returns true when resources were released now
     * (session confirmed stopped); false when teardown is asynchronous — the
     * claims stay until [onBackendSessionStopped].
     *
     * A failing adapter still counts as stopped once its session state is torn
     * down (the controller reports `sessionActive == false` after its exception
     * boundary), so an Error state never leaks a hardware lease.
     */
    fun disconnectBackend(backendId: String): Boolean {
        val target = backend(backendId)
        if (target == null) {
            coordinator.release(backendId)
            return true
        }
        logger.log("manager disconnect backend=$backendId")
        requestStop(target)
        return if (!target.isSessionActive) {
            coordinator.release(backendId)
            true
        } else {
            logger.log("manager disconnect-deferred backend=$backendId session-still-active")
            false
        }
    }

    /**
     * Force-releases [backendId]'s resources. Only for recovery paths where the
     * owner is gone or a dead registration would otherwise leak hardware — it
     * deliberately does NOT stop any session.
     */
    fun releaseBackend(backendId: String) {
        logger.log("manager force-release backend=$backendId")
        coordinator.release(backendId)
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

    /** Asks [backend] to stop; returns whether the session is confirmed stopped. */
    private fun requestStop(backend: ProjectionBackend): Boolean = try {
        backend.disconnect()
    } catch (error: Exception) {
        logger.log("manager stop-failed backend=${backend.id} ${error.javaClass.simpleName}")
        false
    }

    private fun connectInternal(target: ProjectionBackend, device: ProjectionDevice?) {
        // 0. Resolve the actual connect target FIRST: the device resources are
        //    claimed for must be the very device the backend connects — never
        //    "manager assumed UNKNOWN, backend picked a USB phone".
        val resolved = target.resolveConnectDevice(device)
        // 1. A live session in any other backend is an absolute conflict: the
        //    caller must use takeover() for a controlled switch.
        val live = registeredBackends.firstOrNull { it.id != target.id && it.isSessionActive }
        if (live != null) {
            val conflict = ProjectionResourceConflictException(
                "backend=${live.id} still has a live session; use takeover() to switch",
                resource = null,
                ownerBackendId = live.id,
                requestingBackendId = target.id,
                resources = target.requiredResourcesFor(resolved),
            )
            logger.log("manager connect-rejected backend=${target.id} owner=${live.id}")
            throw conflict
        }
        // 2. Confirmed-stopped backends must not keep hardware hostage.
        cleanupStoppedClaimsExcept(target.id)
        // 3. Claim the resources this session needs. The claim is computed for
        //    the RESOLVED device (same one step 4 connects), so transport-based
        //    claims (USB vs Wi-Fi CarLink) can never drift from reality. With
        //    no resolved target the backend's "unknown device" set applies —
        //    never a speculative USB/WIFI claim.
        val resources = target.requiredResourcesFor(resolved ?: device)
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
        synchronized(lock) { activeBackendId = target.id }
        notifySelection(target.id)
        logger.log("manager connect backend=${target.id} device=${resolved?.id ?: "unresolved"}")
        // 4. Connect the SAME resolved device the resources were claimed for.
        target.connect(resolved)
    }

    /** Releases claims left behind by backends whose sessions are confirmed gone. */
    private fun cleanupStoppedClaimsExcept(exceptId: String) {
        for (other in registeredBackends) {
            if (other.id == exceptId) continue
            if (!other.isSessionActive && coordinator.heldBy(other.id).isNotEmpty()) {
                logger.log("manager releasing stale claims backend=${other.id}")
                coordinator.release(other.id)
            }
        }
    }

    private fun resolveTarget(device: ProjectionDevice?): ProjectionBackend? {
        if (device != null) {
            backend(device.backendId)?.let { return it }
        }
        return synchronized(lock) {
            activeBackendId?.let { backends[it] } ?: pickAuto()
        }
    }

    private fun pickAuto(): ProjectionBackend? {
        // Device detection first: a backend with a live session wins. Then the
        // fixed preference order CarPlay > CarLink for starting something new.
        val all = registeredBackends
        return all.firstOrNull { it.isSessionActive }
            ?: backend(CARPLAY_ID)
            ?: backend(CARLINK_ID)
            ?: all.firstOrNull()
    }

    private fun notifySelection(backendId: String?) {
        for (listener in selectionListeners) {
            try {
                listener.onActiveBackendChanged(backendId, mode)
            } catch (error: Exception) {
                logger.log("manager selection-listener-failed ${error.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val CARPLAY_ID = "carplay"
        const val CARLINK_ID = "carlink"
    }
}
