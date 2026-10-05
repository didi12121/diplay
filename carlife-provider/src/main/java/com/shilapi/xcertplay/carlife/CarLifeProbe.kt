// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

/**
 * Opaque CarLife session identity — one per connect/probe attempt, minted by
 * [CarLifeProbe] / [CarLifeProjectionBackend].
 *
 * Deliberately a SEPARATE type from `CarLinkSessionToken`: CarLife and
 * CarLink are different protocols and their session identities must never be
 * interchanged. Old-session callbacks are rejected by identity comparison.
 */
data class CarLifeSessionToken(val value: Long) {
    override fun toString(): String = "carlife-session#$value"
}

/**
 * Diagnostic states of the CarLife wired Compatibility Probe. Only protocol
 * progress is recorded — never user content, media payloads, tokens or
 * secrets.
 */
enum class CarLifeProbeState {
    /** A candidate Android phone appeared on USB. */
    USB_DEVICE_FOUND,

    /** DiPlay requested the phone to switch into AOA accessory mode. */
    AOA_SWITCH_REQUESTED,

    /** AOA accessory mode attached (NOT yet protocol success). */
    AOA_ATTACHED,

    /** CarLife protocol handshake / version negotiation in flight. */
    PROTOCOL_NEGOTIATING,

    /** Phone accepted the protocol version. */
    PROTOCOL_ACCEPTED,

    /** Channel / authentication exchange in flight. */
    AUTHENTICATING,

    /** CONNECTION_ESTABLISHED from the real SDK callback — probe success. */
    ESTABLISHED,

    /** Phone rejected the protocol version (onConnectionVersionNotSupprt). */
    VERSION_REJECTED,

    /** Channel/auth verification failed (onConnectionAuthenFailed). */
    AUTH_FAILED,

    /** USB / AOA link detached. */
    DETACHED,

    /** Unexpected transport or SDK failure. */
    ERROR,
}

/**
 * Precise failure classification of the probe (required reporting surface):
 * never a vague "connection failed".
 */
enum class CarLifeBlocker {
    /** Wired CarLife works end to end. */
    NONE,

    /** No real Android phone has been probed yet. */
    NO_REAL_DEVICE,

    /** The phone refused to switch into AOA accessory mode. */
    AOA_COMPATIBILITY,

    /** Handshake ran but the phone rejected our protocol version. */
    PROTOCOL_VERSION,

    /** Protocol accepted but channel verification / auth failed. */
    CHANNEL_OR_AUTH,

    /** Anything else (transport crash, SDK exception...). */
    OTHER,
}

/**
 * Everything the debug probe panel may show. Strings are protocol/diagnostic
 * values only — no user data, no media, no credentials.
 */
data class CarLifeProbeReport(
    val state: CarLifeProbeState = CarLifeProbeState.DETACHED,
    val blocker: CarLifeBlocker = CarLifeBlocker.NO_REAL_DEVICE,
    val usbDevice: String? = null,
    val aoaState: String = "idle",
    /** Real SDK connection state (CarLifeContext constants 0..3). */
    val connectionState: Int = 0,
    /** CONFIG_PROTOCOL_VERSION sent during negotiation. */
    val protocolVersion: Int? = null,
    /** CarLife version reported by the phone, when known. */
    /**
     * CarLife protocol version reported by the phone (ProtocolVersionMatch:
     * CarLifeContext.carlifeVersion = versionStatus.carlifeProtocolVersion).
     * 0 = not reported by the phone - this is NOT a phone app version.
     */
    val phoneCarlifeProtocolVersion: Int? = null,
    val authResult: String? = null,
    val lastError: String? = null,
    /** Projection resources currently held by the CarLife backend. */
    val heldResources: Set<com.shilapi.xcertplay.projection.ProjectionResource> = emptySet(),
    /** Session token of the current probe attempt (identity, not secret). */
    val session: Long? = null,
    // ---- Video pipeline diagnostics (real data only) ----
    /** VIDEO_CONFIG_RECEIVED -> VIDEO_FRAME_RECEIVED -> VIDEO_FRAME_QUEUED ->
     *  VIDEO_DECODER_STARTED -> FIRST_OUTPUT_FRAME_RENDERED */
    val videoStage: String = "idle",
    val videoCodec: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoConfigCount: Int = 0,
    val videoFrameCount: Int = 0,
    val videoBytes: Long = 0,
    val keyframeCount: Int = 0,
    val lastFrameAgeMs: Long? = null,
    val ptsSource: String = "HOST_RECEIVE_SYNTHETIC",
    val decoderState: String = "idle",
    val firstFrameRendered: Boolean = false,
    val lastVideoError: String? = null,
    // ---- Video handshake (protocol-level flags for real-device triage) ----
    val videoInitSent: Boolean = false,
    val videoInitDoneReceived: Boolean = false,
    val videoStartSent: Boolean = false,
    val videoDataSeen: Boolean = false,
    // ---- Touch diagnostics (Phase 9.2b; counts only, no user content) ----
    /** "enabled" (Connected) / "waiting" (no session) / "error" (send failed). */
    val touchState: String = "waiting",
    /** Touch events accepted by the provider (sent to the phone). */
    val touchEventsSent: Int = 0,
    val touchDownCount: Int = 0,
    val touchMoveCount: Int = 0,
    val touchUpCount: Int = 0,
    val touchCancelCount: Int = 0,
    /** Last accepted action: DOWN/MOVE/UP/CANCEL. */
    val lastTouchAction: String? = null,
    /** Last content-LOCAL coordinate sent (x,y). */
    val lastTouchX: Float? = null,
    val lastTouchY: Float? = null,
    /** Touch surface currently declared to the SDK (content pixels). */
    val touchSurfaceWidth: Int? = null,
    val touchSurfaceHeight: Int? = null,
    /** Touch events rejected (stale session, outside content, send failure). */
    val touchDropped: Int = 0,
    /** Last rejection reason / failure class name (diagnostic only). */
    val lastTouchError: String? = null,
)

/**
 * Connection events translated from the real CarLife SDK
 * (`ConnectionChangeListener`), tagged with the [CarLifeSessionToken] they
 * belong to. Callbacks of an old session are ignored by identity.
 */
sealed class CarLifeConnectionEvent {
    abstract val session: CarLifeSessionToken

    data class Attached(override val session: CarLifeSessionToken) : CarLifeConnectionEvent()
    data class Reattached(override val session: CarLifeSessionToken) : CarLifeConnectionEvent()
    data class Detached(override val session: CarLifeSessionToken) : CarLifeConnectionEvent()
    data class Established(override val session: CarLifeSessionToken) : CarLifeConnectionEvent()
    data class VersionNotSupported(override val session: CarLifeSessionToken) : CarLifeConnectionEvent()
    data class AuthFailed(override val session: CarLifeSessionToken) : CarLifeConnectionEvent()
    /** SDK connection progress (0..100). Diagnostic only; NEVER the success criterion. */
    data class Progress(override val session: CarLifeSessionToken, val progress: Int) : CarLifeConnectionEvent()
    data class Failed(
        override val session: CarLifeSessionToken,
        val message: String,
    ) : CarLifeConnectionEvent()
}
