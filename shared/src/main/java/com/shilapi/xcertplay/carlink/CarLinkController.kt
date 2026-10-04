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

    /** Sinks of the active session, closed when it ends. */
    @Volatile
    private var sessionMedia: CarLinkMediaSinks? = null

    @Volatile
    var currentDevice: CarLinkDevice? = null
        private set

    @Volatile
    var sessionActive: Boolean = false
        private set

    /**
     * Session epoch bookkeeping: every connect attempt gets a fresh epoch and
     * callbacks are interpreted against the epoch of the session they belong
     * to. A real adapter backed by an SDK with session identity (sessionId,
     * handle, connectionId) should prefer that identity; this counter is the
     * controller-level fallback.
     */
    private var nextSessionEpoch = 0L

    @Volatile
    private var currentSessionEpoch = 0L

    /**
     * Trailing-end guard: sessions torn down non-normally (FATAL error, adapter
     * failure mid-connect/disconnect, media-acquire failure) may still deliver
     * their `onSessionEnded` afterwards. Such an end belongs to a DEAD session
     * and must never overwrite a newer session's state (e.g. publish Ready over
     * Connecting/Connected) or tear down a newer live session. Counted because
     * more than one dead session can owe an end.
     */
    @Volatile
    private var staleEndsExpected = 0

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
        return guarded("initialize") {
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
        return guarded("startDiscovery") { adapter.startDiscovery() }
    }

    fun stopDiscovery() {
        guarded("stopDiscovery") { adapter.stopDiscovery() }
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
        currentSessionEpoch = synchronized(this) { ++nextSessionEpoch }
        return guarded("connect") { adapter.connect(target) }
    }

    fun disconnect(): CarLinkOperationResult {
        // An explicit disconnect ends the CURRENT session: the upcoming
        // onSessionEnded belongs to it, not to some earlier dead session.
        staleEndsExpected = 0
        return guarded("disconnect") { adapter.disconnect() }
    }

    fun dispose() {
        try {
            guarded("dispose") { adapter.dispose() }
        } finally {
            teardownSession("dispose")
            discovered.clear()
        }
    }

    fun onMetadata(listener: (ProjectionMetadata) -> Unit) = metadata.addListener(listener)

    fun offMetadata(listener: (ProjectionMetadata) -> Unit) = metadata.removeListener(listener)

    /** Runs an adapter call; failures become session errors, never exceptions. */
    private fun guarded(operation: String, block: () -> Unit): CarLinkOperationResult {
        return try {
            block()
            CarLinkOperationResult.Success
        } catch (error: Exception) {
            diagnostics.event(
                "adapter-failed",
                "op=$operation error=${error.javaClass.simpleName}",
            )
            // A session in flight (connect/disconnect) or an active one may owe
            // a trailing onSessionEnded after this teardown.
            val trailingPossible = sessionActive ||
                operation == "connect" || operation == "disconnect"
            teardownSession(operation, trailingEndPossible = trailingPossible)
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
     * Clears session and channel state after a session ends or fails.
     *
     * [trailingEndPossible] arms the trailing-end guard: the torn-down SDK
     * session may still deliver its `onSessionEnded`, and that callback must
     * later be recognized as belonging to this DEAD session (ignored with a
     * diagnostic) instead of overwriting or tearing down a newer one.
     */
    private fun teardownSession(reason: String, trailingEndPossible: Boolean = false) {
        val wasActive = sessionActive
        sessionActive = false
        currentDevice = null
        audio.stopAll()
        video.onActive(false)
        video.unbind()
        audio.unbind()
        val media = sessionMedia
        sessionMedia = null
        if (media != null) {
            try {
                media.close()
            } catch (error: Exception) {
                diagnostics.event("media-close-failed", "error=${error.javaClass.simpleName}")
            }
        }
        if (trailingEndPossible) staleEndsExpected++
        if (wasActive) {
            diagnostics.event(
                "session-torn-down",
                "reason=${CarLinkDiagnostics.redact(reason)} epoch=$currentSessionEpoch",
            )
        }
    }

    private fun emit(event: CarLinkSessionEvent) {
        for (listener in sessionListeners) listener(event)
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

        override fun onSessionStarted(device: CarLinkDevice) {
            currentDevice = device
            sessionActive = true
            diagnostics.event("connected", "device=${device.deviceId}")
            val media = try {
                mediaSinks.acquire()
            } catch (error: Exception) {
                diagnostics.event("media-acquire-failed", "error=${error.javaClass.simpleName}")
                teardownSession("media-acquire-failed", trailingEndPossible = true)
                emit(
                    CarLinkSessionEvent.Error(
                        "ADAPTER_FAILURE",
                        "media sink acquisition failed: ${error.javaClass.simpleName}",
                        error,
                    ),
                )
                return
            }
            sessionMedia = media
            video.bind(media.video)
            audio.bind(media.audio)
            video.onActive(true)
            emit(CarLinkSessionEvent.Connected(device.toProjectionDevice(backendId)))
        }

        override fun onSessionEnded(reason: String) {
            // Trailing-end guard: sessions torn down non-normally (FATAL error,
            // adapter failure) may deliver their onSessionEnded afterwards.
            // Such an end belongs to a DEAD session — consume it with a clear
            // diagnostic and do NOT publish state changes (a late Ready would
            // overwrite the latched Error or a newer Connecting/Connected) and
            // do NOT tear down a newer live session or its media.
            if (staleEndsExpected > 0) {
                staleEndsExpected--
                diagnostics.event(
                    "stale-session-end-ignored",
                    "reason=${CarLinkDiagnostics.redact(reason)} epoch=$currentSessionEpoch",
                )
                return
            }
            teardownSession(reason)
            diagnostics.event("disconnected", "reason=${CarLinkDiagnostics.redact(reason)}")
            emit(CarLinkSessionEvent.Disconnected(CarLinkDiagnostics.redact(reason)))
        }

        override fun onVideoConfig(config: com.shilapi.xcertplay.projection.ProjectionVideoConfig) {
            video.onConfig(config)
        }

        override fun onVideoFrame(frame: com.shilapi.xcertplay.projection.ProjectionVideoFrame) {
            video.onFrame(frame)
        }

        override fun onAudioStarted(format: CarLinkAudioFormat) {
            audio.start(format)
        }

        override fun onAudioFrame(frame: CarLinkAudioFrame) {
            audio.frame(frame)
        }

        override fun onAudioStopped(streamId: Int) {
            audio.stop(streamId)
        }

        override fun onMetadata(metadata: ProjectionMetadata) {
            this@CarLinkController.metadata.publish(metadata)
        }

        override fun onError(
            code: String,
            message: String,
            severity: CarLinkErrorSeverity,
            cause: Throwable?,
        ) {
            val redacted = CarLinkDiagnostics.redact(message)
            when (severity) {
                CarLinkErrorSeverity.RECOVERABLE -> {
                    // Transient failure: log as a diagnostic and keep the
                    // session (and its resources) alive.
                    diagnostics.event("error-recoverable", "code=$code message=$redacted")
                    emit(CarLinkSessionEvent.Error(code, redacted, cause, CarLinkErrorSeverity.RECOVERABLE))
                }
                CarLinkErrorSeverity.FATAL -> {
                    diagnostics.event("error-fatal", "code=$code message=$redacted")
                    // The dead SDK session usually still reports its end later.
                    teardownSession("fatal:$code", trailingEndPossible = true)
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
