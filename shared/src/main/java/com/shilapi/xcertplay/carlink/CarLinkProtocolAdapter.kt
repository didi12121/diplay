package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport

/**
 * One Android phone discovered through an ICCOA CarLink stack.
 *
 * [deviceId] is an opaque protocol identifier. No protocol fields (ports, keys,
 * certificates) belong here.
 */
data class CarLinkDevice(
    val deviceId: String,
    val name: String,
    val vendorHint: String? = null,
    val transport: ProjectionTransport = ProjectionTransport.UNKNOWN,
) {
    fun toProjectionDevice(backendId: String = CarLinkProjectionBackend.ID): ProjectionDevice =
        ProjectionDevice(
            id = deviceId,
            name = name,
            backendId = backendId,
            transport = transport,
            vendorHint = vendorHint,
        )
}

/**
 * Callbacks a [CarLinkProtocolAdapter] uses to push session events upward.
 * All methods may be called on any thread owned by the protocol stack.
 */
interface CarLinkProtocolListener {
    /** A phone was found during discovery. */
    fun onDeviceFound(device: CarLinkDevice) {}

    /** The session reached the phone and media may start flowing. */
    fun onSessionStarted(device: CarLinkDevice) {}

    /** The session ended (peer detach, timeout, explicit disconnect). */
    fun onSessionEnded(reason: String) {}

    /** Encoded video access unit; payload is only valid during the call. */
    fun onVideoFrame(codec: String, keyFrame: Boolean, payload: ByteArray, offset: Int, length: Int) {}

    /** Out-of-band video configuration record. */
    fun onVideoConfig(codec: String, codecData: ByteArray) {}

    /** Encoded or PCM audio payload for one channel role. */
    fun onAudioFrame(channel: String, presentationTimeUs: Long, payload: ByteArray) {}

    /** Now-playing / navigation metadata update. */
    fun onMetadata(metadata: ProjectionMetadata) {}

    /** The protocol stack reported a failure. */
    fun onError(code: String, message: String, cause: Throwable?) {}
}

/**
 * Isolation seam over an ICCOA CarLink protocol implementation.
 *
 * DiPlay ships no ICCOA CarLink SDK and this repository contains no protocol
 * implementation: no ports, no handshakes, no certificates, nothing reverse
 * engineered. When an official SDK or legally obtained protocol documentation
 * becomes available, implement this interface in a dedicated adapter module
 * (`OfficialCarLinkSdkAdapter`, `NativeCarLinkProtocolAdapter`) and the rest of
 * DiPlay — ProjectionManager, media sinks, UI — works unchanged.
 *
 * Threading mirrors the rest of `shared`: implementations own their workers,
 * calls return quickly, and callbacks arrive on protocol threads.
 */
interface CarLinkProtocolAdapter {
    /** Human readable provider name for diagnostics, e.g. `mock`, `iccoa-official`. */
    val providerName: String

    /**
     * False when no protocol provider is present (no SDK installed). The backend
     * reports `CarLink protocol provider unavailable` instead of pretending to
     * connect.
     */
    val isAvailable: Boolean

    /** Prepares the protocol stack. Idempotent. */
    fun initialize()

    /** Releases every protocol resource. */
    fun dispose()

    /** Starts looking for phones; results arrive via [CarLinkProtocolListener.onDeviceFound]. */
    fun startDiscovery()

    fun stopDiscovery()

    /** Opens a session with [device]; progress arrives via the listener. */
    fun connect(device: CarLinkDevice)

    fun disconnect()

    fun sendTouch(event: ProjectionTouchEvent)

    fun sendKey(event: ProjectionKeyEvent)

    fun setListener(listener: CarLinkProtocolListener?)
}
