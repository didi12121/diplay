// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.content.Context
import android.view.MotionEvent

/**
 * Isolation seam between the DiPlay projection core and the open CarLife V2
 * SDK (`com.baidu.carlife.sdk`, Apache-2.0 from Apollo-DuerOS).
 *
 * The real implementation [CarLifeV2Provider] wraps the upstream
 * `CarLife.init / CarLife.receiver()` API; tests inject fakes. Every
 * connection callback is tagged with the [CarLifeSessionToken] of the attempt
 * that started it, so stale callbacks from an old session can be rejected by
 * identity (never by ordering or counting).
 */
interface CarLifeProvider {
    /** False when the CarLife stack cannot run at all. */
    val isAvailable: Boolean

    /**
     * Initializes the SDK. [config] carries the demo/test channel policy —
     * see [CarLifeProviderConfig].
     */
    fun initialize(context: Context, config: CarLifeProviderConfig)

    /**
     * Begins one wired-AOA connection attempt under [token]. Connection
     * events for this attempt are delivered to [listener] tagged with [token].
     */
    fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit)

    /** Cancels/tears down the attempt of [token] (idempotent). */
    fun stopConnection(token: CarLifeSessionToken)

    /**
     * Binds a session-scoped video listener to one attempt's transport
     * messages. Implementations must fence it together with the attempt.
     */
    fun attachVideo(token: CarLifeSessionToken, video: CarLifeVideoListener) {}

    fun detachVideo(token: CarLifeSessionToken) {}

    /**
     * Declares the size of the projection content DiPlay sends touch
     * coordinates in (content-LOCAL pixels, see
     * [CarLifeProjectionBackend.onTouchEvent]). The provider forwards it to
     * `CarLifeReceiver.onSurfaceSizeChanged(...)` so the SDK's
     * RemoteControlManager performs the final surface → video mapping.
     * Rejected when [token] is not the current armed attempt.
     */
    fun updateTouchSurface(token: CarLifeSessionToken, width: Int, height: Int) {}

    /**
     * Sends ONE synchronous touch uplink for [token] through the high-level
     * CarLife API (`CarLifeReceiver.onTouchEvent(MotionEvent)` →
     * RemoteControlManager → phone). [event] carries content-LOCAL coordinates
     * and is only borrowed for the synchronous send — implementations must not
     * retain or recycle it. Returns true when the event reached the receiver;
     * false when the call was rejected (stale token, no current attempt) or
     * failed.
     */
    fun sendTouch(token: CarLifeSessionToken, event: MotionEvent): Boolean = false

    fun dispose()

    /**
     * Real protocol diagnostics for the probe panel. USB entries are
     * VID:PID-only — never serials or personal data.
     */
    fun diagnostics(): CarLifeProviderDiagnostics = CarLifeProviderDiagnostics()
}

/** Real SDK diagnostic snapshot (no user content, no secrets). */
data class CarLifeProviderDiagnostics(
    /** "VID:PID xxxx:yyyy" summaries only. */
    val usbDevices: List<String> = emptyList(),
    /** CONFIG_PROTOCOL_VERSION the vehicle side is using. */
    val localProtocolVersion: Int? = null,
    /** CarLife protocol version reported by the phone, when negotiated. */
    val phoneCarlifeProtocolVersion: Int? = null,
    /** Raw SDK connection state (CarLifeContext constants 0..3). */
    val connectionState: Int = 0,
)

/**
 * Configuration of one CarLife provider instance.
 *
 * DEMO_CHANNEL POLICY: [channel] and [cuid] come from the PUBLIC upstream
 * sample configuration and exist only so a developer can run a personal
 * Compatibility Probe. They are DEMO_CHANNEL — NOT FOR PRODUCTION —
 * COMPATIBILITY UNVERIFIED. A production integration requires a channel
 * legitimately issued by Baidu / the CarLife program. DiPlay must never
 * invent a channel, forge Baidu authentication or patch the phone app.
 */
data class CarLifeProviderConfig(
    /** DEMO_CHANNEL — NOT FOR PRODUCTION — COMPATIBILITY UNVERIFIED. */
    val channel: String = DEMO_CHANNEL,
    /** Local test-receiver identifier only; not an OEM/certified identity. */
    val cuid: String = DEMO_CUID,
    /** Vehicle protocol version; use the public V2.0 sample value. */
    val protocolVersion: Int = DEMO_PROTOCOL_VERSION,
    /** Main activity class the SDK launches for its UI shell. */
    val activityClass: Class<out Activity>? = null,
) {
    companion object {
        /** From the public Apollo-DuerOS V2.0 sample — demo/test only. */
        const val DEMO_CHANNEL = "20029999"

        /** DiPlay test receiver identifier — not an OEM identity. */
        const val DEMO_CUID = "diplay-carlife-probe"

        /** Public V2.0 sample value; do not raise blindly. */
        const val DEMO_PROTOCOL_VERSION = 4
    }
}
