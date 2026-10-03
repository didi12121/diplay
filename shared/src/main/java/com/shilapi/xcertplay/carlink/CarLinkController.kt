package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionLogger
import com.shilapi.xcertplay.projection.ProjectionMetadata

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

    fun initialize() {
        if (!adapter.isAvailable) {
            diagnostics.event("provider-unavailable")
            return
        }
        guarded("initialize") {
            adapter.initialize()
            diagnostics.event("initialize", "provider=${adapter.providerName}")
        }
    }

    fun startDiscovery() {
        if (!adapter.isAvailable) return
        discovered.clear()
        diagnostics.event("discovering")
        guarded("startDiscovery") { adapter.startDiscovery() }
    }

    fun stopDiscovery() {
        guarded("stopDiscovery") { adapter.stopDiscovery() }
    }

    fun connect(device: ProjectionDevice?) {
        if (!adapter.isAvailable) {
            emit(CarLinkSessionEvent.Error("PROVIDER_UNAVAILABLE", CarLinkProjectionBackend.PROVIDER_UNAVAILABLE_MESSAGE))
            return
        }
        val target = device?.let { projection ->
            discovered.firstOrNull { it.deviceId == projection.id }
        } ?: discovered.firstOrNull()
        if (target == null) {
            emit(CarLinkSessionEvent.Error("CONNECT_FAILED", "no CarLink device discovered yet"))
            return
        }
        diagnostics.event("connecting", "device=${target.deviceId}")
        guarded("connect") { adapter.connect(target) }
    }

    fun disconnect() {
        guarded("disconnect") { adapter.disconnect() }
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
    private fun guarded(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            diagnostics.event(
                "adapter-failed",
                "op=$operation error=${error.javaClass.simpleName}",
            )
            teardownSession(operation)
            emit(
                CarLinkSessionEvent.Error(
                    code = "ADAPTER_FAILURE",
                    message = "CarLink adapter $operation failed: ${error.javaClass.simpleName}",
                    cause = error,
                ),
            )
        }
    }

    /** Clears session and channel state after a session ends or fails. */
    private fun teardownSession(reason: String) {
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
        if (wasActive) {
            diagnostics.event("session-torn-down", "reason=${CarLinkDiagnostics.redact(reason)}")
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
                teardownSession("media-acquire-failed")
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

        override fun onError(code: String, message: String, cause: Throwable?) {
            diagnostics.event("error", "code=$code message=${CarLinkDiagnostics.redact(message)}")
            emit(CarLinkSessionEvent.Error(code, CarLinkDiagnostics.redact(message), cause))
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
    ) : CarLinkSessionEvent()
}
