package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTransport
import com.shilapi.xcertplay.projection.ProjectionVideoConfig
import com.shilapi.xcertplay.projection.ProjectionVideoFrame

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
 * Format of one CarLink audio stream, announced by
 * [CarLinkProtocolListener.onAudioStarted] before its first frame.
 *
 * Fully backend-neutral and strongly typed: channel [role] is a
 * [ProjectionAudioChannel], never a protocol wire string.
 */
data class CarLinkAudioFormat(
    /** Protocol stream number; opaque, unique per open stream in a session. */
    val streamId: Int,
    /** Routing role of this stream. */
    val role: ProjectionAudioChannel,
    val codec: ProjectionAudioCodec,
    val sampleRate: Int,
    val channels: Int,
    /** Backend wire payload type, kept for diagnostics only. */
    val payloadType: Int = 0,
)

/**
 * One raw audio access unit (or PCM frame) of a CarLink stream.
 *
 * The payload is an unframed codec access unit — no transport/RTP framing is
 * assumed at this boundary. [offset]/[length] delimit the valid window so
 * adapters can forward buffers without copying.
 */
class CarLinkAudioFrame(
    val streamId: Int,
    val presentationTimeUs: Long,
    val payload: ByteArray,
    val offset: Int = 0,
    val length: Int = payload.size - offset,
)

/**
 * Callbacks a [CarLinkProtocolAdapter] uses to push session events upward.
 * All methods may be called on any thread owned by the protocol stack.
 *
 * Media surfaces are strongly typed: video uses the neutral
 * [ProjectionVideoConfig]/[ProjectionVideoFrame] objects (codec, size, PTS,
 * keyframe flag and payload window all preserved), audio has an explicit
 * started/frame/stopped lifecycle per stream. No string codec guessing happens
 * at this boundary.
 */
interface CarLinkProtocolListener {
    /** A phone was found during discovery. */
    fun onDeviceFound(device: CarLinkDevice) {}

    /** The session reached the phone and media may start flowing. */
    fun onSessionStarted(device: CarLinkDevice) {}

    /** The session ended (peer detach, timeout, explicit disconnect). */
    fun onSessionEnded(reason: String) {}

    /** Out-of-band video configuration record (codec + decoder config). */
    fun onVideoConfig(config: ProjectionVideoConfig) {}

    /** Encoded video access unit; payload is only valid during the call. */
    fun onVideoFrame(frame: ProjectionVideoFrame) {}

    /** One audio stream opened; frames of [CarLinkAudioFormat.streamId] follow. */
    fun onAudioStarted(format: CarLinkAudioFormat) {}

    /** One audio access unit of an open stream. */
    fun onAudioFrame(frame: CarLinkAudioFrame) {}

    /** The audio stream [streamId] ended; no further frames will arrive. */
    fun onAudioStopped(streamId: Int) {}

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
 * **Exception boundary**: every method below is called through a guard layer
 * (`CarLinkController`) that converts thrown exceptions into
 * `ProjectionState.Error` and cleans up session/resource state. Implementations
 * may throw; they must never crash a UI or protocol thread by leaking runtime
 * exceptions past this interface — but even if they do, the guard catches them.
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
