package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionLogger
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionVideoSink

/**
 * Session orchestrator between [CarLinkProtocolAdapter] and the shared media
 * layer. It owns discovery state, the discovered device list, the channels, and
 * the metadata fan-out — but no protocol details. Swap the adapter and the whole
 * controller keeps working.
 */
class CarLinkController(
    private val adapter: CarLinkProtocolAdapter,
    videoSink: ProjectionVideoSink,
    audioSink: ProjectionAudioSink,
    logger: ProjectionLogger = ProjectionLogger.NONE,
    private val backendId: String = CarLinkProjectionBackend.ID,
) {
    private val diagnostics = CarLinkDiagnostics(logger, backendId)

    val video = CarLinkVideoChannel(videoSink, diagnostics)
    val audio = CarLinkAudioChannel(audioSink, diagnostics)
    val input = CarLinkInputChannel(adapter)
    val metadata = CarLinkMetadataChannel()

    private val discovered = java.util.concurrent.CopyOnWriteArrayList<CarLinkDevice>()
    private val deviceListeners =
        java.util.concurrent.CopyOnWriteArrayList<(List<ProjectionDevice>) -> Unit>()
    private val sessionListeners =
        java.util.concurrent.CopyOnWriteArrayList<(CarLinkSessionEvent) -> Unit>()

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
        adapter.initialize()
        diagnostics.event("initialize", "provider=${adapter.providerName}")
    }

    fun startDiscovery() {
        if (!adapter.isAvailable) return
        discovered.clear()
        diagnostics.event("discovering")
        adapter.startDiscovery()
    }

    fun stopDiscovery() {
        adapter.stopDiscovery()
    }

    fun connect(device: ProjectionDevice?) {
        if (!adapter.isAvailable) {
            emit(CarLinkSessionEvent.Error("PROVIDER_UNAVAILABLE", "CarLink protocol provider unavailable"))
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
        adapter.connect(target)
    }

    fun disconnect() {
        adapter.disconnect()
    }

    fun dispose() {
        adapter.dispose()
        discovered.clear()
        sessionActive = false
        currentDevice = null
    }

    fun onMetadata(listener: (ProjectionMetadata) -> Unit) = metadata.addListener(listener)

    fun offMetadata(listener: (ProjectionMetadata) -> Unit) = metadata.removeListener(listener)

    private fun emit(event: CarLinkSessionEvent) {
        for (listener in sessionListeners) listener(event)
    }

    private inner class Listener : CarLinkProtocolListener {
        override fun onDeviceFound(device: CarLinkDevice) {
            if (discovered.none { it.deviceId == device.deviceId }) {
                discovered.add(device)
                diagnostics.event("device-found", "device=${device.deviceId} vendor=${device.vendorHint ?: "unknown"}")
            }
            val snapshot = discoveredDevices()
            for (listener in deviceListeners) listener(snapshot)
        }

        override fun onSessionStarted(device: CarLinkDevice) {
            currentDevice = device
            sessionActive = true
            diagnostics.event("connected", "device=${device.deviceId}")
            video.onActive(true)
            emit(CarLinkSessionEvent.Connected(device.toProjectionDevice(backendId)))
        }

        override fun onSessionEnded(reason: String) {
            sessionActive = false
            video.onActive(false)
            diagnostics.event("disconnected", "reason=${CarLinkDiagnostics.redact(reason)}")
            emit(CarLinkSessionEvent.Disconnected(CarLinkDiagnostics.redact(reason)))
            currentDevice = null
        }

        override fun onVideoFrame(codec: String, keyFrame: Boolean, payload: ByteArray, offset: Int, length: Int) {
            video.onFrame(codec, keyFrame, payload, offset, length)
        }

        override fun onVideoConfig(codec: String, codecData: ByteArray) {
            video.onConfig(codec, codecData)
        }

        override fun onAudioFrame(channel: String, presentationTimeUs: Long, payload: ByteArray) {
            val role = when (channel.lowercase()) {
                "media", "music" -> com.shilapi.xcertplay.projection.ProjectionAudioChannel.MEDIA
                "navigation", "nav", "guidance" -> com.shilapi.xcertplay.projection.ProjectionAudioChannel.NAVIGATION
                "call", "phone", "telephony" -> com.shilapi.xcertplay.projection.ProjectionAudioChannel.PHONE_CALL
                else -> com.shilapi.xcertplay.projection.ProjectionAudioChannel.VOICE_ASSISTANT
            }
            audio.frame(role, presentationTimeUs, payload)
        }

        override fun onMetadata(metadata: ProjectionMetadata) {
            this@CarLinkController.metadata.publish(metadata)
        }

        override fun onError(code: String, message: String, cause: Throwable?) {
            diagnostics.event("error", "code=$code message=${CarLinkDiagnostics.redact(message)}")
            emit(CarLinkSessionEvent.Error(code, CarLinkDiagnostics.redact(message)))
        }
    }
}

/** Lifecycle events surfaced by [CarLinkController]. */
sealed class CarLinkSessionEvent {
    data class Connected(val device: ProjectionDevice) : CarLinkSessionEvent()
    data class Disconnected(val reason: String) : CarLinkSessionEvent()
    data class Error(val code: String, val message: String) : CarLinkSessionEvent()
}
