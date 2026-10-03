package com.shilapi.xcertplay.projection

/**
 * Lifecycle state shared by every projection backend.
 *
 * The states are deliberately protocol agnostic: Apple CarPlay, ICCOA CarLink and
 * every future backend report the same vocabulary so UI and diagnostics never need
 * backend-specific branches for basic status rendering.
 */
sealed class ProjectionState {
    /** Backend constructed but not initialized. */
    data object Idle : ProjectionState()

    /** Protocol resources are being acquired. */
    data object Initializing : ProjectionState()

    /** Backend is up and able to discover or accept a phone. */
    data object Ready : ProjectionState()

    /** Looking for a phone to project. */
    data object Discovering : ProjectionState()

    /** A phone was found and a session is being established. */
    data object Connecting : ProjectionState()

    /** Projection session is running. */
    data object Connected : ProjectionState()

    /** The session is being torn down. */
    data object Disconnecting : ProjectionState()

    /**
     * The backend failed. Keeps the error [code], a human readable [message], the
     * original [cause] when available and the [backendId] that produced it so
     * diagnostics can attribute failures without inspecting logs by hand.
     */
    data class Error(
        val code: ProjectionErrorCode,
        val message: String,
        val cause: Throwable? = null,
        val backendId: String,
    ) : ProjectionState()
}

/** Stable, greppable error categories for projection backends. */
enum class ProjectionErrorCode {
    /** No protocol provider/SDK is installed for this backend. */
    PROVIDER_UNAVAILABLE,
    DISCOVERY_FAILED,
    CONNECT_FAILED,
    TRANSPORT_ERROR,
    PROTOCOL_ERROR,
    AUTHENTICATION_FAILED,
    TIMEOUT,
    /** Another backend currently owns a required shared resource. */
    RESOURCE_CONFLICT,
    INTERNAL_ERROR,
}

/** Diagnostics name for a state transition, e.g. `DISCOVERING`, `CONNECTED`, `ERROR`. */
val ProjectionState.logName: String
    get() = when (this) {
        ProjectionState.Idle -> "IDLE"
        ProjectionState.Initializing -> "INITIALIZING"
        ProjectionState.Ready -> "READY"
        ProjectionState.Discovering -> "DISCOVERING"
        ProjectionState.Connecting -> "CONNECTING"
        ProjectionState.Connected -> "CONNECTED"
        ProjectionState.Disconnecting -> "DISCONNECTING"
        is ProjectionState.Error -> "ERROR"
    }

/** Notified on every backend state change. May be called on any thread. */
fun interface ProjectionStateListener {
    fun onStateChanged(backendId: String, state: ProjectionState)
}

/**
 * Thread-safe state holder for one backend: keeps the current state, drops
 * duplicate transitions and fans changes out to listeners with a
 * `backend=<id> state=<NAME>` diagnostic line.
 */
class ProjectionStateStore(
    private val backendId: String,
    private val logger: ProjectionLogger = ProjectionLogger.NONE,
) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ProjectionStateListener>()

    @Volatile
    private var current: ProjectionState = ProjectionState.Idle

    val state: ProjectionState
        get() = current

    fun addListener(listener: ProjectionStateListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: ProjectionStateListener) {
        listeners.remove(listener)
    }

    /** Publishes [next], skipping it when it equals the current state. */
    fun publish(next: ProjectionState) {
        synchronized(this) {
            if (next == current) return
            current = next
        }
        logger.log("backend=$backendId state=${next.logName}${next.detail()}")
        for (listener in listeners) {
            try {
                listener.onStateChanged(backendId, next)
            } catch (error: Exception) {
                logger.log("backend=$backendId state-listener-failed ${error.javaClass.simpleName}")
            }
        }
    }

    private fun ProjectionState.detail(): String = when (this) {
        is ProjectionState.Error -> " code=$code message=$message"
        else -> ""
    }
}
