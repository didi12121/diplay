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

    /**
     * Begins one connection attempt under [token] over [transport]
     * (Phase 9.2W-A). The transport is part of the connect REQUEST: resolved
     * before resource acquisition and configured here WITHOUT starting
     * network/USB work early (see
     * [CarLifeReceiver.configureConnectTypeWithoutStarting]); the actual
     * scan/listen/connect starts only inside this call.
     *
     * Default: legacy implementers are USB-only — delegate to the wired entry
     * point.
     */
    fun startConnection(
        token: CarLifeSessionToken,
        transport: CarLifeTransport,
        listener: (CarLifeConnectionEvent) -> Unit,
    ) = startConnection(token, listener)

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

    // ---- Modern wireless Bluetooth bootstrap (Phase 9.2W-B1) ----

    /**
     * Starts the session-scoped RFCOMM bootstrap of [token] toward the bonded
     * device with EXACT name [targetBluetoothName] (never a random device,
     * never a reflection fallback). Every [listener] event is permanently bound
     * to [token]; a late Bluetooth callback of a dead attempt is rejected by
     * identity. No Wi-Fi Direct machinery is started by this call.
     */
    fun startBluetoothBootstrap(
        token: CarLifeSessionToken,
        targetBluetoothName: String,
        listener: (CarLifeBootstrapEvent) -> Unit,
    ) {}

    /** Stops/fences the bootstrap of [token] (idempotent). */
    fun stopBluetoothBootstrap(token: CarLifeSessionToken) {}

    /**
     * Continues the wireless transport to a PROTOCOL-PROVIDED phone IP: the
     * TCP channel set opens directly (no UDP discovery). Returns true only for
     * the current armed attempt.
     */
    fun connectWirelessToPhoneIp(token: CarLifeSessionToken, ip: String): Boolean = false

    /** Bonded Bluetooth device NAMES only (no MAC/address); empty when unknown. */
    fun listBondedBluetoothNames(): List<String> = emptyList()

    /** False when the BLUETOOTH_CONNECT runtime grant is missing (API 31+). */
    fun hasBluetoothConnectPermission(): Boolean = true

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
    // ---- Transport / wireless diagnostics (Phase 9.2W-A) ----
    /** CarLifeTransport name of the current attempt: USB_AOA / WIFI_AP. */
    val transport: String = "USB_AOA",
    /** Local IPv4 seen on active interfaces (no SSID, no MAC). */
    val localIp: String? = null,
    /** Active network transport type: WIFI / ETHERNET / CELLULAR / OTHER. */
    val networkType: String? = null,
    /** UDP discovery port (7999, upstream constant) while listening. */
    val udpPort: Int? = null,
    val udpListening: Boolean = false,
    /** Phone IP taken from the UDP discovery datagram source address. */
    val phoneIp: String? = null,
    val udpPacketsReceived: Int = 0,
    /** TCP channel states: "cmd"/"video"/"audio"/"tts"/"vr"/"touch"/"update"
     *  -> "connecting" / "connected" / "failed". Eager connect (audited):
     *  each channel opens its socket immediately. */
    val tcpChannels: Map<String, String> = emptyMap(),
    /** True once the TCP channel set attached (protocol may now run). */
    val transportAttached: Boolean = false,
    // ---- Modern Bluetooth bootstrap diagnostics (Phase 9.2W-B1) ----
    /** BLUETOOTH_CONNECT granted (API 31+); always true on older platforms. */
    val btPermission: Boolean = true,
    /** Selected target device NAME only (never a MAC/address). */
    val btTarget: String? = null,
    val btBonded: String? = null,
    /** idle / connecting / connected / error. */
    val btRfcomm: String = "idle",
    val btInfoRequest: Boolean = false,
    val btInfoResponse: Boolean = false,
    /** Exactly what B1 advertises: HOTSPOT (TYPE_WIFI). */
    val btAdvertisedType: String? = null,
    val btTargetInfoRequest: Boolean = false,
    /** Always false in B1: MSG_WIRELESS_REQUEST_IP is never sent speculatively. */
    val btRequestIpSent: Boolean = false,
    val btResponseIpReceived: Boolean = false,
    /** WIFI_DIRECT_REQUIRED when the phone's messages require the P2P path. */
    val modernWirelessPath: String? = null,
)

/**
 * Modern-wireless Bluetooth bootstrap events (Phase 9.2W-B1), permanently
 * bound to the [CarLifeSessionToken] of the attempt that started the
 * bootstrap. Late events of a dead attempt are rejected by identity - a
 * bootstrap callback is NEVER relabelled with a newer token.
 */
sealed class CarLifeBootstrapEvent {
    abstract val session: CarLifeSessionToken

    data class Searching(
        override val session: CarLifeSessionToken,
        val targetName: String,
    ) : CarLifeBootstrapEvent()

    data class TargetFound(
        override val session: CarLifeSessionToken,
        val targetName: String,
    ) : CarLifeBootstrapEvent()

    data class RfcommConnecting(
        override val session: CarLifeSessionToken,
        val targetName: String,
    ) : CarLifeBootstrapEvent()

    data class RfcommConnected(
        override val session: CarLifeSessionToken,
        val targetName: String,
    ) : CarLifeBootstrapEvent()

    /** kind: "info-request" / "info-response-sent" / "target-info-request" / "response-ip" / "md-status" / "other". */
    data class Message(
        override val session: CarLifeSessionToken,
        val kind: String,
    ) : CarLifeBootstrapEvent()

    data class WirelessIp(
        override val session: CarLifeSessionToken,
        val ip: String,
    ) : CarLifeBootstrapEvent()

    data class Failed(
        override val session: CarLifeSessionToken,
        val reason: String,
    ) : CarLifeBootstrapEvent()
}

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
