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
    val phoneCarLifeVersion: String? = null,
    val authResult: String? = null,
    val lastError: String? = null,
    /** Projection resources currently held by the CarLife backend. */
    val heldResources: Set<com.shilapi.xcertplay.projection.ProjectionResource> = emptySet(),
    /** Session token of the current probe attempt (identity, not secret). */
    val session: Long? = null,
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
