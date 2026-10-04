package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioChannel
import com.shilapi.xcertplay.projection.ProjectionAudioCodec
import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionKeyEvent
import com.shilapi.xcertplay.projection.ProjectionMetadata
import com.shilapi.xcertplay.projection.ProjectionSampleFormat
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
 * Opaque session identity minted by [CarLinkController] — one per connect
 * attempt. Every session-scoped callback carries it, so a late callback from
 * an old session is recognized by IDENTITY COMPARISON (never by arrival
 * counting or timing) and can be ignored deterministically.
 *
 * A real adapter binds this token to its SDK session identity (sessionHandle,
 * connectionId, …) on connect and echoes the token on every callback. If an
 * SDK has no session identity of its own, the adapter must fully stop the
 * previous session's callback workers before binding the next token.
 */
data class CarLinkSessionToken(val value: Long) {
    override fun toString(): String = "session#$value"
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
    /**
     * PCM byte order of raw PCM16 payloads. Ignored for AAC/Opus. A real SDK
     * must declare its endianness here — never assume Apple's big-endian
     * wired LPCM; the renderer byte-swaps exactly once based on this.
     */
    val sampleFormat: ProjectionSampleFormat = ProjectionSampleFormat.PCM_S16_LE,
)

/** Severity of an asynchronous [CarLinkProtocolListener.onError] report. */
enum class CarLinkErrorSeverity {
    /** Temporary failure (e.g. a dropped packet): the session stays alive. */
    RECOVERABLE,

    /** Session-killing failure (link lost, SDK abort): the session is torn down. */
    FATAL,
}

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
    /** A phone was found during discovery (provider-scoped, no session). */
    fun onDeviceFound(device: CarLinkDevice) {}

    /** The session [session] reached the phone and media may start flowing. */
    fun onSessionStarted(session: CarLinkSessionToken, device: CarLinkDevice) {}

    /** Session [session] ended (peer detach, timeout, explicit disconnect). */
    fun onSessionEnded(session: CarLinkSessionToken, reason: String) {}

    /** Out-of-band video configuration record (codec + decoder config). */
    fun onVideoConfig(session: CarLinkSessionToken, config: ProjectionVideoConfig) {}

    /** Encoded video access unit; payload is only valid during the call. */
    fun onVideoFrame(session: CarLinkSessionToken, frame: ProjectionVideoFrame) {}

    /** One audio stream opened; frames of [CarLinkAudioFormat.streamId] follow. */
    fun onAudioStarted(session: CarLinkSessionToken, format: CarLinkAudioFormat) {}

    /** One audio access unit of an open stream. */
    fun onAudioFrame(session: CarLinkSessionToken, frame: CarLinkAudioFrame) {}

    /** The audio stream [streamId] of session [session] ended. */
    fun onAudioStopped(session: CarLinkSessionToken, streamId: Int) {}

    /** Now-playing / navigation metadata update of session [session]. */
    fun onMetadata(session: CarLinkSessionToken, metadata: ProjectionMetadata) {}

    /**
     * The protocol stack reported a failure.
     *
     * [session] scopes the error: `null` is a provider/discovery/global error
     * (no session exists); non-null is a failure of that specific session. A
     * provider error must never be given a fabricated token.
     *
     * [CarLinkErrorSeverity.RECOVERABLE] is logged as a diagnostic and the
     * session keeps running; [CarLinkErrorSeverity.FATAL] tears the session
     * down (audio stopped, media sinks closed, resources released) and latches
     * `ProjectionState.Error` so a later `onSessionEnded` cannot overwrite the
     * error with a healthy state. When in doubt, report FATAL.
     */
    fun onError(
        session: CarLinkSessionToken?,
        code: String,
        message: String,
        severity: CarLinkErrorSeverity,
        cause: Throwable? = null,
    ) {}
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

    /**
     * Opens a session with [device] under the controller-minted [session]
     * token; progress arrives via the listener **echoing that token**. The
     * adapter must not mint its own token — it binds [session] to the real SDK
     * session identity (sessionHandle/connectionId) and translates callbacks
     * back to [session].
     */
    fun connect(device: CarLinkDevice, session: CarLinkSessionToken)

    fun disconnect()

    fun sendTouch(event: ProjectionTouchEvent)

    fun sendKey(event: ProjectionKeyEvent)

    fun setListener(listener: CarLinkProtocolListener?)
}
