package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionLogger
import com.shilapi.xcertplay.projection.ProjectionMetadata

/**
 * Explicit result of a [CarLinkController] operation. Backends must only
 * advance their state machine (e.g. Initializing → Ready) on [Success]; a
 * [Failure] keeps the propagated `ProjectionState.Error` visible instead of
 * overwriting it with a healthy state.
 */
sealed class CarLinkOperationResult {
    data object Success : CarLinkOperationResult()
    data class Failure(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : CarLinkOperationResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * Session orchestrator between [CarLinkProtocolAdapter] and the shared media
 * layer. It owns discovery state, the discovered device list, the channels, and
 * the metadata fan-out — but no protocol details. Swap the adapter and the whole
 * controller keeps working.
 *
 * Media lifecycle: when a session starts, sinks are acquired from
 * [CarLinkMediaSinkProvider] and bound to the video/audio channels; when the
 * session ends (or fails), the channels are unbound and the sinks closed. A
 * session that never started never holds a decoder or AudioTrack.
 *
 * Exception boundary: every call into the adapter is guarded. An adapter that
 * throws never crashes the caller; the failure surfaces as
 * [CarLinkSessionEvent.Error] (→ `ProjectionState.Error` with cause retained)
 * and session/channel state is cleaned up (audio streams stopped, session
 * inactive, media sinks released).
 */
class CarLinkController(
    private val adapter: CarLinkProtocolAdapter,
    private val mediaSinks: CarLinkMediaSinkProvider,
    logger: ProjectionLogger = ProjectionLogger.NONE,
    private val backendId: String = CarLinkProjectionBackend.ID,
) {
    private val diagnostics = CarLinkDiagnostics(logger, backendId)

    val video = CarLinkVideoChannel(diagnostics)
    val audio = CarLinkAudioChannel(diagnostics)
    val input = CarLinkInputChannel(adapter, diagnostics)
    val metadata = CarLinkMetadataChannel()

    private val discovered = java.util.concurrent.CopyOnWriteArrayList<CarLinkDevice>()
    private val deviceListeners =
        java.util.concurrent.CopyOnWriteArrayList<(List<ProjectionDevice>) -> Unit>()
    private val sessionListeners =
        java.util.concurrent.CopyOnWriteArrayList<(CarLinkSessionEvent) -> Unit>()

    /** Sinks of the active session, closed when it ends. Owned by one token. */
    private var sessionMedia: CarLinkMediaSinks? = null

    /** The token whose session owns [sessionMedia]; teardown may only close its own. */
    private var sessionMediaToken: CarLinkSessionToken? = null

    @Volatile
    var currentDevice: CarLinkDevice? = null
        private set

    @Volatile
    var sessionActive: Boolean = false
        private set

    /**
     * Session identity: one [CarLinkSessionToken] per connect attempt, minted
     * here and echoed by every session callback. Stale callbacks are detected
     * by IDENTITY COMPARISON — never by arrival counting, order or timing.
     *
     * All session state ([pendingSessionToken], [activeSessionToken],
     * [sessionMedia] ownership, [currentDevice], [sessionActive]) is guarded by
     * [sessionLock] so SDK callbacks from different worker threads are safe.
     * Long-running work (media close, SDK calls) happens outside the lock.
     */
    private val sessionLock = Any()

    private val nextSessionToken = java.util.concurrent.atomic.AtomicLong()

    /** The in-flight connect attempt's token; consumed by its onSessionStarted. */
    private var pendingSessionToken: CarLinkSessionToken? = null

    /** The live session's token; all media/session callbacks must match it. */
    private var activeSessionToken: CarLinkSessionToken? = null

    init {
        adapter.setListener(Listener())
    }

    val available: Boolean
        get() = adapter.isAvailable

    val providerName: String
        get() = adapter.providerName

    fun discoveredDevices(): List<ProjectionDevice> =
        discovered.map { it.toProjectionDevice(backendId) }

    fun addDeviceListener(listener: (List<ProjectionDevice>) -> Unit) {
        deviceListeners.addIfAbsent(listener)
    }

    fun removeDeviceListener(listener: (List<ProjectionDevice>) -> Unit) {
        deviceListeners.remove(listener)
    }

    fun addSessionListener(listener: (CarLinkSessionEvent) -> Unit) {
        sessionListeners.addIfAbsent(listener)
    }

    fun removeSessionListener(listener: (CarLinkSessionEvent) -> Unit) {
        sessionListeners.remove(listener)
    }

    fun initialize(): CarLinkOperationResult {
        if (!adapter.isAvailable) {
            diagnostics.event("provider-unavailable")
            return CarLinkOperationResult.Failure(
                "PROVIDER_UNAVAILABLE",
                CarLinkProjectionBackend.PROVIDER_UNAVAILABLE_MESSAGE,
            )
        }
        return guarded("initialize", null) {
            adapter.initialize()
            diagnostics.event("initialize", "provider=${adapter.providerName}")
        }
    }

    fun startDiscovery(): CarLinkOperationResult {
        if (!adapter.isAvailable) {
            return CarLinkOperationResult.Failure(
                "PROVIDER_UNAVAILABLE",
                CarLinkProjectionBackend.PROVIDER_UNAVAILABLE_MESSAGE,
            )
        }
        discovered.clear()
        diagnostics.event("discovering")
        return guarded("startDiscovery", null) { adapter.startDiscovery() }
    }

    fun stopDiscovery() {
        guarded("stopDiscovery", null) { adapter.stopDiscovery() }
    }

    /**
     * The device [connect] would actually talk to. [ProjectionManager] calls
     * this BEFORE resource arbitration, so the claimed resources always match
     * the device really connected.
     *
     * - `requested == null` → the first discovered device (same choice
     *   [connect] makes for a null request);
     * - `requested != null` → the requested device **only if it is still
     *   discovered**, otherwise null. A device that vanished between request
     *   and connect must never silently fall back to another phone.
     */
    fun resolveConnectDevice(requested: ProjectionDevice?): ProjectionDevice? {
        if (requested == null) {
            return discovered.firstOrNull()?.toProjectionDevice(backendId)
        }
        return discovered
            .firstOrNull { it.deviceId == requested.id }
            ?.toProjectionDevice(backendId)
    }

    fun connect(device: ProjectionDevice?): CarLinkOperationResult {
        if (!adapter.isAvailable) {
            val message = CarLinkProjectionBackend.PROVIDER_UNAVAILABLE_MESSAGE
            emit(
                CarLinkSessionEvent.Error(
                    "PROVIDER_UNAVAILABLE",
                    message,
                    severity = CarLinkErrorSeverity.FATAL,
                ),
            )
            return CarLinkOperationResult.Failure("PROVIDER_UNAVAILABLE", message)
        }
        // Explicit request: connect THAT device or fail. Never fall back to a
        // different phone (that would connect USB while WIFI was arbitrated).
        // Null request: the first discovered device, same as resolveConnectDevice.
        val target = if (device != null) {
            discovered.firstOrNull { it.deviceId == device.id }
        } else {
            discovered.firstOrNull()
        }
        if (target == null) {
            val message = if (device != null) {
                "CarLink device ${device.id} is no longer available"
            } else {
                "no CarLink device discovered yet"
            }
            emit(
                CarLinkSessionEvent.Error(
                    "CONNECT_FAILED",
                    message,
                    severity = CarLinkErrorSeverity.FATAL,
                ),
            )
            return CarLinkOperationResult.Failure("CONNECT_FAILED", message)
        }
        diagnostics.event("connecting", "device=${target.deviceId}")
        // Mint this attempt's session token BEFORE the adapter call; every
        // callback of this session must echo it back.
        val token = CarLinkSessionToken(nextSessionToken.incrementAndGet())
        synchronized(sessionLock) {
            pendingSessionToken = token
            activeSessionToken = null
        }
        return guarded("connect", token) { adapter.connect(target, token) }
    }

    fun disconnect(): CarLinkOperationResult {
        val token = synchronized(sessionLock) { activeSessionToken ?: pendingSessionToken }
        return guarded("disconnect", token) { adapter.disconnect() }
    }

    fun dispose() {
        try {
            guarded("dispose", null) { adapter.dispose() }
        } finally {
            teardownSession("dispose", null)
            discovered.clear()
        }
    }

    fun onMetadata(listener: (ProjectionMetadata) -> Unit) = metadata.addListener(listener)

    fun offMetadata(listener: (ProjectionMetadata) -> Unit) = metadata.removeListener(listener)

    /**
     * Runs an adapter call; failures become session errors, never exceptions.
     * [token] scopes the failure to the session it concerns (null = provider).
     */
    private fun guarded(
        operation: String,
        token: CarLinkSessionToken?,
        block: () -> Unit,
    ): CarLinkOperationResult {
        return try {
            block()
            CarLinkOperationResult.Success
        } catch (error: Exception) {
            diagnostics.event(
                "adapter-failed",
                "op=$operation error=${error.javaClass.simpleName} session=$token",
            )
            teardownSession(operation, token)
            val message = "CarLink adapter $operation failed: ${error.javaClass.simpleName}"
            emit(
                CarLinkSessionEvent.Error(
                    code = "ADAPTER_FAILURE",
                    message = message,
                    cause = error,
                ),
            )
            CarLinkOperationResult.Failure("ADAPTER_FAILURE", message, error)
        }
    }

    /**
     * Clears the session state of [token] (or the current one when null, e.g.
     * dispose). Only the OWNED session may be torn down: a teardown of dead
     * session A must never close session B's media sinks or channels. Media
     * close and SDK calls run outside [sessionLock].
     */
    private fun teardownSession(reason: String, token: CarLinkSessionToken?) {
        val mediaToClose: CarLinkMediaSinks?
        val wasActive: Boolean
        synchronized(sessionLock) {
            val owned = token == null ||
                token == activeSessionToken ||
                token == pendingSessionToken
            if (!owned) {
                // Stale teardown of an old session: never touch the current one.
                return
            }
            wasActive = sessionActive
            if (token != null) {
                if (activeSessionToken == token) activeSessionToken = null
                if (pendingSessionToken == token) pendingSessionToken = null
            } else {
                activeSessionToken = null
                pendingSessionToken = null
            }
            sessionActive = false
            currentDevice = null
            audio.stopAll()
            video.onActive(false)
            video.unbind()
            audio.unbind()
            mediaToClose = sessionMedia
            sessionMedia = null
            sessionMediaToken = null
        }
        if (mediaToClose != null) {
            try {
                mediaToClose.close()
            } catch (error: Exception) {
                diagnostics.event("media-close-failed", "error=${error.javaClass.simpleName}")
            }
        }
        if (wasActive) {
            diagnostics.event(
                "session-torn-down",
                "reason=${CarLinkDiagnostics.redact(reason)} session=$token",
            )
        }
    }

    private fun emit(event: CarLinkSessionEvent) {
        for (listener in sessionListeners) listener(event)
    }

    /** True when [session] is the current pending or active session. */
    private fun isCurrent(session: CarLinkSessionToken): Boolean =
        synchronized(sessionLock) {
            session == activeSessionToken || session == pendingSessionToken
        }

    private fun staleDiagnostic(kind: String, session: CarLinkSessionToken) {
        diagnostics.event("stale-session-callback-ignored", "kind=$kind session=$session")
    }

    private inner class Listener : CarLinkProtocolListener {
        override fun onDeviceFound(device: CarLinkDevice) {
            if (discovered.none { it.deviceId == device.deviceId }) {
                discovered.add(device)
                diagnostics.event(
                    "device-found",
                    "device=${device.deviceId} vendor=${device.vendorHint ?: "unknown"}",
                )
            }
            val snapshot = discoveredDevices()
            for (listener in deviceListeners) listener(snapshot)
        }

        override fun onSessionStarted(session: CarLinkSessionToken, device: CarLinkDevice) {
            // Only the pending connect attempt may start a session. A late
            // start of an old (failed/replaced) attempt must not mark a session
            // active, steal currentDevice, or acquire media sinks.
            val accepted = synchronized(sessionLock) {
                if (session != pendingSessionToken) {
                    false
                } else {
                    pendingSessionToken = null
                    activeSessionToken = session
                    currentDevice = device
                    sessionActive = true
                    true
                }
            }
            if (!accepted) {
                staleDiagnostic("session-start", session)
                return
            }
            diagnostics.event("connected", "device=${device.deviceId} session=$session")
            val media = try {
                mediaSinks.acquire()
            } catch (error: Exception) {
                diagnostics.event("media-acquire-failed", "error=${error.javaClass.simpleName}")
                teardownSession("media-acquire-failed", session)
                emit(
                    CarLinkSessionEvent.Error(
                        "ADAPTER_FAILURE",
                        "media sink acquisition failed: ${error.javaClass.simpleName}",
                        error,
                    ),
                )
                return
            }
            // Media ownership is bound to THIS session's token: a later
            // teardown of some other session can never close it.
            synchronized(sessionLock) {
                if (activeSessionToken == session) {
                    sessionMedia = media
                    sessionMediaToken = session
                }
            }
            video.bind(media.video)
            audio.bind(media.audio)
            video.onActive(true)
            emit(CarLinkSessionEvent.Connected(device.toProjectionDevice(backendId)))
        }

        override fun onSessionEnded(session: CarLinkSessionToken, reason: String) {
            // Identity comparison: an end of a DEAD session is stale — consume
            // it with a diagnostic and never touch the current session, its
            // media, its resources, or the visible state (a late Ready would
            // overwrite the latched Error or a newer Connecting/Connected).
            if (!isCurrent(session)) {
                staleDiagnostic("session-end", session)
                return
            }
            teardownSession(reason, session)
            diagnostics.event("disconnected", "reason=${CarLinkDiagnostics.redact(reason)}")
            emit(CarLinkSessionEvent.Disconnected(CarLinkDiagnostics.redact(reason)))
        }

        override fun onVideoConfig(
            session: CarLinkSessionToken,
            config: com.shilapi.xcertplay.projection.ProjectionVideoConfig,
        ) {
            if (!isCurrent(session)) {
                staleDiagnostic("video-config", session)
                return
            }
            video.onConfig(config)
        }

        override fun onVideoFrame(
            session: CarLinkSessionToken,
            frame: com.shilapi.xcertplay.projection.ProjectionVideoFrame,
        ) {
            // Old H264/H265 access units must never reach the new session's
            // decoder — identity check first, then forward.
            if (!isCurrent(session)) {
                staleDiagnostic("video-frame", session)
                return
            }
            video.onFrame(frame)
        }

        override fun onAudioStarted(session: CarLinkSessionToken, format: CarLinkAudioFormat) {
            if (!isCurrent(session)) {
                staleDiagnostic("audio-started", session)
                return
            }
            audio.start(format)
        }

        override fun onAudioFrame(session: CarLinkSessionToken, frame: CarLinkAudioFrame) {
            if (!isCurrent(session)) {
                staleDiagnostic("audio-frame", session)
                return
            }
            audio.frame(frame)
        }

        override fun onAudioStopped(session: CarLinkSessionToken, streamId: Int) {
            if (!isCurrent(session)) {
                staleDiagnostic("audio-stopped", session)
                return
            }
            audio.stop(streamId)
        }

        override fun onMetadata(session: CarLinkSessionToken, metadata: ProjectionMetadata) {
            if (!isCurrent(session)) {
                staleDiagnostic("metadata", session)
                return
            }
            this@CarLinkController.metadata.publish(metadata)
        }

        override fun onError(
            session: CarLinkSessionToken?,
            code: String,
            message: String,
            severity: CarLinkErrorSeverity,
            cause: Throwable?,
        ) {
            val redacted = CarLinkDiagnostics.redact(message)
            // A session-scoped error of a DEAD session is stale (e.g. a late
            // FATAL of session A must not tear down session B). Provider errors
            // (session == null) always apply.
            if (session != null && !isCurrent(session)) {
                staleDiagnostic("error", session)
                return
            }
            when (severity) {
                CarLinkErrorSeverity.RECOVERABLE -> {
                    // Transient failure: log as a diagnostic and keep the
                    // session (and its resources) alive.
                    diagnostics.event("error-recoverable", "code=$code message=$redacted")
                    emit(CarLinkSessionEvent.Error(code, redacted, cause, CarLinkErrorSeverity.RECOVERABLE))
                }
                CarLinkErrorSeverity.FATAL -> {
                    diagnostics.event("error-fatal", "code=$code message=$redacted session=$session")
                    // Tear down the session this error belongs to. Its later
                    // onSessionEnded carries the same token and is then stale
                    // by identity — no counting needed.
                    teardownSession("fatal:$code", session)
                    emit(CarLinkSessionEvent.Error(code, redacted, cause, CarLinkErrorSeverity.FATAL))
                }
            }
        }
    }
}

/** Lifecycle events surfaced by [CarLinkController]. */
sealed class CarLinkSessionEvent {
    data class Connected(val device: ProjectionDevice) : CarLinkSessionEvent()
    data class Disconnected(val reason: String) : CarLinkSessionEvent()
    data class Error(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
        val severity: CarLinkErrorSeverity = CarLinkErrorSeverity.FATAL,
    ) : CarLinkSessionEvent()
}
