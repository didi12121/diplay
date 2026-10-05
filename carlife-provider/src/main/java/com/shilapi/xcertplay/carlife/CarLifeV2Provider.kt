// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.content.Context
import android.view.MotionEvent
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.receiver.CarLifeReceiver
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe

/**
 * Narrow view of the CarLife SDK receiver used by [CarLifeV2Provider].
 *
 * Exists so the provider's session fencing can be tested against a scripted
 * SDK (register-replay behavior, late callbacks, transport teardown) without
 * a phone — while the production implementation drives the real upstream
 * receiver untouched.
 */
interface CarLifeReceiverFacade {
    fun addConnectionListener(listener: ConnectionChangeListener)
    fun removeConnectionListener(listener: ConnectionChangeListener)
    fun addTransportListener(listener: com.baidu.carlife.sdk.internal.transport.TransportListener) {}
    fun removeTransportListener(listener: com.baidu.carlife.sdk.internal.transport.TransportListener) {}
    fun addProgressListener(listener: ConnectProgressListener)
    fun removeProgressListener(listener: ConnectProgressListener)
    fun connect()
    fun stopConnect()
    /** Permanent fence of the current attempt (suppresses detach auto-reconnect). */
    fun shutdown()
    fun connectionState(): Int
    fun protocolVersion(): Int
    fun carlifeVersion(): Int
    /** USB candidates as "VID:PID" only — never serials or personal data. */
    fun usbDeviceSummaries(): List<String>

    // ---- High-level touch uplink (Phase 9.2b) ----
    /** Content-LOCAL surface size feeding RemoteControlManager's final mapping. */
    fun onSurfaceSizeChanged(width: Int, height: Int) {}
    /** Synchronous high-level touch send (CarLifeReceiver.onTouchEvent). */
    fun onTouchEvent(event: MotionEvent) {}

    // ---- Transport reconfiguration + wireless probe (Phase 9.2W-A) ----
    /**
     * Reconfigures the LOCAL transport family WITHOUT auto-connect (host-local
     * seam, see CarLifeReceiver.configureConnectTypeWithoutStarting).
     */
    fun configureConnectTypeWithoutStarting(type: Int) {}
    /** Diagnostics-only wireless transport probe (host-local). */
    fun setTransportProbeListener(listener: WirlessTransportProbe?) {}
}

/**
 * Real CarLife provider over the open CarLife V2.0 SDK
 * (`CarLife-Android-Vehicle-V2.0/carlife-sdk`, Apache-2.0, Baidu
 * Apollo-DuerOS public source).
 *
 * Wired AOA only (CONNECTION_TYPE_AOA). Wireless CarLife+ is NOT in scope and
 * NOT open sourced upstream — never claimed here.
 *
 * Lifecycle contract (Phase 9.1.1):
 *  1. [initialize] performs SDK/config setup ONLY — no USB scan, no USB
 *     permission request, no `transport.connect()`. The connect type is set
 *     through `FEATURE_CONFIG_CONNECT_TYPE`; the SDK's `setConnectType()`
 *     (which auto-connects) is deliberately NOT called. Real USB work starts
 *     only after ProjectionManager arbitration succeeded and [startConnection]
 *     runs.
 *  2. One process ⇒ one provider ⇒ one `CarLife.init` ⇒ one captured
 *     [CarLifeReceiver]. [initialize] is idempotent and every later operation
 *     uses the captured receiver — never the mutable global `CarLife.receiver()`.
 *  3. Session fencing: every attempt gets a dedicated listener instance
 *     permanently bound to its [CarLifeSessionToken]. Stopping attempt A
 *     unregisters A's listener and fences A's transport BEFORE B's token is
 *     bound; events reaching an old instance carry A's token and are rejected
 *     downstream by identity. Additionally, events arriving before the current
 *     attempt's `connect()` (the SDK replays connection state on listener
 *     registration) are dropped — a late SDK callback can therefore never be
 *     re-labelled as the new token.
 */
class CarLifeV2Provider(
    private val facadeFactory: (Context, CarLifeProviderConfig) -> CarLifeReceiverFacade = { context, config -> realFacade(context, config) },
) : CarLifeProvider {

    override val isAvailable: Boolean = true

    private val lock = Any()
    private var facade: CarLifeReceiverFacade? = null

    /** Application context for local network diagnostics (Phase 9.2W-A). */
    @Volatile
    private var appContext: Context? = null

    /**
     * Transport family the captured receiver is currently configured for
     * (init configures USB AOA). Reconfiguration is host-local and never
     * auto-connects; unchanged configuration is skipped so the proven USB
     * path is byte-for-byte its Phase 9.1 behavior.
     */
    @Volatile
    private var configuredConnectType: Int = CarLifeContext.CONNECTION_TYPE_AOA

    /** The attempt currently bound to SDK callbacks (token-bound listener). */
    private var attempt: Attempt? = null

    private class Attempt(
        val token: CarLifeSessionToken,
        val sink: (CarLifeConnectionEvent) -> Unit,
        /** Transport of THIS attempt (Phase 9.2W-A); never a previous session's. */
        val transport: CarLifeTransport = CarLifeTransport.USB_AOA,
    ) {
        /** False until this attempt's connect() ran: registration replay and
         *  leftovers of older attempts cannot masquerade as this session. */
        @Volatile
        var armed = false

        lateinit var listener: ConnectionChangeListener
        lateinit var progress: ConnectProgressListener

        /** Guards the touch surface bookkeeping below. */
        val touchLock = Any()

        /** Content-LOCAL touch surface declared for this attempt (0 = none). */
        @Volatile
        var surfaceWidth = 0

        @Volatile
        var surfaceHeight = 0

        /** Last size actually pushed to the captured receiver (-1 = never). */
        @Volatile
        var pushedSurfaceWidth = -1

        @Volatile
        var pushedSurfaceHeight = -1

        /** Wireless transport diagnostics of THIS attempt (Phase 9.2W-A). */
        val wireless = WirelessState()

        fun emit(event: CarLifeConnectionEvent) {
            if (!armed) return // registration replay or pre-connect leftover
            sink(event) // event carries THIS attempt's fixed token
        }
    }

    /** Wireless transport diagnostics state (per attempt; no payloads). */
    private class WirelessState {
        @Volatile var udpListening = false
        @Volatile var udpPort: Int? = null
        @Volatile var phoneIp: String? = null
        @Volatile var udpPackets = 0
        @Volatile var transportAttached = false
        val tcpChannels = java.util.concurrent.ConcurrentHashMap<Int, String>()
    }

    override fun initialize(context: Context, config: CarLifeProviderConfig) {
        synchronized(lock) {
            // Idempotent: one CarLife.init per application process. CarLife is
            // a global singleton holding one receiver — a second init would
            // replace it and orphan the previous one.
            if (facade != null) return
            appContext = context.applicationContext
            facade = facadeFactory(context, config)
        }
    }

    override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
        startConnection(token, CarLifeTransport.USB_AOA, listener)
    }

    override fun startConnection(
        token: CarLifeSessionToken,
        transport: CarLifeTransport,
        listener: (CarLifeConnectionEvent) -> Unit,
    ) {
        val facade = facade ?: error("CarLifeV2Provider.initialize() must run first")
        // ---- Fence the previous attempt BEFORE binding the new token ----
        val old = synchronized(lock) { attempt.also { attempt = null } }
        if (old != null) {
            // 1. unregister the old listener: the SDK dispatch list no longer
            //    contains it, so no further callback can reach the old sink;
            runCatching { facade.removeConnectionListener(old.listener) }
            runCatching { facade.removeProgressListener(old.progress) }
            runCatching { facade.setTransportProbeListener(null) }
            detachVideo(old.token)
            // 2. fence the old transport: tear its attempt down AND suppress
            //    the detach auto-reconnect so it can never come back.
            runCatching { facade.shutdown() }
        }
        // ---- Bind the new attempt (armed = false) ----
        // Registration MAY synchronously replay stale connection state - the
        // attempt is not armed yet, so those events are dropped.
        val current = Attempt(token, listener, transport)
        current.listener = connectionListenerFor(current, token)
        current.progress = progressListenerFor(current, token)
        synchronized(lock) { attempt = current }
        runCatching { facade.addConnectionListener(current.listener) }
        runCatching { facade.addProgressListener(current.progress) }
        // ---- Bind the attempt-specific wireless probe BEFORE configuring
        // (9.2W-A.1): the probe is propagated into the configured transport
        // dynamically, so no real discovery event is ever dropped. ----
        runCatching { facade.setTransportProbeListener(probeListenerFor(current, token)) }
        // ---- FAIL-CLOSED transport selection (9.2W-A.1) ----
        // The configuration must succeed BEFORE connect(); on failure the
        // cache is NOT updated and connect() is NOT called - the physical
        // transport can never drift from the resources the manager claimed.
        val connectType = transport.connectType()
        if (connectType != configuredConnectType) {
            try {
                facade.configureConnectTypeWithoutStarting(connectType)
            } catch (error: Exception) {
                // The receiver may be left half-reconfigured - never trust the
                // cache again until an explicit reconfiguration succeeds, so a
                // later request always retries configuration (no drift).
                configuredConnectType = CONNECT_TYPE_UNKNOWN
                throw error
            }
            configuredConnectType = connectType
        }
        // ---- Arm, then connect: real events caused by connect() (e.g. the
        // synchronous UDP bind -> onUdpListening) belong to THIS attempt, while
        // registration replay stays fenced by the pre-arming window. ----
        current.armed = true
        facade.connect()
    }

    override fun stopConnection(token: CarLifeSessionToken) {
        val facade = facade ?: return
        val current = synchronized(lock) {
            if (attempt?.token != token) return
            attempt.also { attempt = null }
        } ?: return
        runCatching { facade.removeConnectionListener(current.listener) }
        runCatching { facade.removeProgressListener(current.progress) }
        runCatching { facade.setTransportProbeListener(null) }
        runCatching { facade.shutdown() }
    }

    override fun diagnostics(): CarLifeProviderDiagnostics {
        val facade = facade ?: return CarLifeProviderDiagnostics()
        val current = synchronized(lock) { attempt }
        val wireless = current?.wireless
        return CarLifeProviderDiagnostics(
            usbDevices = runCatching { facade.usbDeviceSummaries() }.getOrDefault(emptyList()),
            localProtocolVersion = runCatching { facade.protocolVersion() }.getOrNull(),
            phoneCarlifeProtocolVersion = runCatching { facade.carlifeVersion() }.getOrNull(),
            connectionState = runCatching { facade.connectionState() }.getOrDefault(0),
            // ---- Transport / wireless (Phase 9.2W-A; IPs only, no SSID/MAC) ----
            transport = current?.transport?.name ?: "USB_AOA",
            localIp = localIpv4(),
            networkType = activeNetworkType(),
            udpPort = wireless?.udpPort,
            udpListening = wireless?.udpListening ?: false,
            phoneIp = wireless?.phoneIp,
            udpPacketsReceived = wireless?.udpPackets ?: 0,
            tcpChannels = wireless?.tcpChannels?.entries
                ?.associate { channelName(it.key) to it.value } ?: emptyMap(),
            transportAttached = wireless?.transportAttached ?: false,
        )
    }

    /** Upstream MSG_CHANNEL_* id -> diagnostic name (no payloads). */
    private fun channelName(channel: Int): String = when (channel) {
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD -> "cmd"
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO -> "video"
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO -> "audio"
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_TTS -> "tts"
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_VR -> "vr"
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_TOUCH -> "touch"
        com.baidu.carlife.sdk.Constants.MSG_CHANNEL_UPDATE -> "update"
        else -> "ch$channel"
    }

    /** First active non-loopback IPv4 (diagnostic only). */
    private fun localIpv4(): String? = runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        java.util.Collections.list(interfaces).asSequence()
            .filter { runCatching { it.isUp }.getOrDefault(false) && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    /** Active network transport type (diagnostic only; no SSID/MAC). */
    private fun activeNetworkType(): String? = runCatching {
        val context = appContext ?: return null
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return null
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        when {
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    }.getOrNull()

    private val videoBridges = java.util.concurrent.ConcurrentHashMap<CarLifeSessionToken, CarLifeVideoBridge>()

    override fun attachVideo(token: CarLifeSessionToken, video: CarLifeVideoListener) {
        val facade = facade ?: return
        val bridge = CarLifeVideoBridge(token, video)
        videoBridges[token] = bridge
        runCatching { facade.addTransportListener(bridge) }
    }

    override fun detachVideo(token: CarLifeSessionToken) {
        val facade = facade ?: return
        val bridge = videoBridges.remove(token) ?: return
        runCatching { facade.removeTransportListener(bridge) }
    }

    // ---- Touch uplink (Phase 9.2b): session-fenced, high-level API only ----

    /** The armed attempt of [token], or null when [token] is stale. */
    private fun armedAttempt(token: CarLifeSessionToken): Attempt? =
        synchronized(lock) { attempt }?.takeIf { it.token == token && it.armed }

    override fun updateTouchSurface(token: CarLifeSessionToken, width: Int, height: Int) {
        val facade = facade ?: return
        val current = armedAttempt(token) ?: return // stale token: rejected
        synchronized(current.touchLock) {
            current.surfaceWidth = width
            current.surfaceHeight = height
            pushTouchSurface(current, facade)
        }
    }

    override fun sendTouch(token: CarLifeSessionToken, event: MotionEvent): Boolean {
        val facade = facade ?: return false
        val current = armedAttempt(token) ?: return false // stale token: rejected
        synchronized(current.touchLock) {
            // A touch is only meaningful once its coordinate frame is known:
            // the receiver must get the surface size before/with the first
            // event so RemoteControlManager maps exactly once.
            if (current.surfaceWidth <= 0 || current.surfaceHeight <= 0) return false
            pushTouchSurface(current, facade)
            // Synchronous borrow: the caller recycles `event` after this call.
            return runCatching { facade.onTouchEvent(event) }.isSuccess
        }
    }

    /** Pushes the declared surface size at most once per change (per attempt). */
    private fun pushTouchSurface(current: Attempt, facade: CarLifeReceiverFacade) {
        val width = current.surfaceWidth
        val height = current.surfaceHeight
        if (width <= 0 || height <= 0) return
        if (current.pushedSurfaceWidth == width && current.pushedSurfaceHeight == height) return
        runCatching { facade.onSurfaceSizeChanged(width, height) }
        current.pushedSurfaceWidth = width
        current.pushedSurfaceHeight = height
    }

    override fun dispose() {
        videoBridges.keys.toList().forEach { detachVideo(it) }
        val facade = facade ?: return
        val current = synchronized(lock) { attempt.also { attempt = null } }
        if (current != null) {
            runCatching { facade.removeConnectionListener(current.listener) }
            runCatching { facade.removeProgressListener(current.progress) }
        }
        runCatching { facade.setTransportProbeListener(null) }
        runCatching { facade.shutdown() }
    }

    // ---- SDK callback adapters: the token is fixed per listener instance ----

    private fun connectionListenerFor(
        attempt: Attempt,
        token: CarLifeSessionToken,
    ): ConnectionChangeListener = object : ConnectionChangeListener {
        override fun onConnectionAttached(context: CarLifeContext) =
            attempt.emit(CarLifeConnectionEvent.Attached(token))

        override fun onConnectionReattached(context: CarLifeContext) =
            attempt.emit(CarLifeConnectionEvent.Reattached(token))

        override fun onConnectionDetached(context: CarLifeContext) =
            attempt.emit(CarLifeConnectionEvent.Detached(token))

        override fun onConnectionEstablished(context: CarLifeContext) =
            attempt.emit(CarLifeConnectionEvent.Established(token))

        override fun onConnectionVersionNotSupprt(context: CarLifeContext) =
            attempt.emit(CarLifeConnectionEvent.VersionNotSupported(token))

        override fun onConnectionAuthenFailed(context: CarLifeContext) =
            attempt.emit(CarLifeConnectionEvent.AuthFailed(token))
    }

    private fun progressListenerFor(
        attempt: Attempt,
        token: CarLifeSessionToken,
    ): ConnectProgressListener = object : ConnectProgressListener {
        override fun onProgress(progress: Int) {
            attempt.emit(CarLifeConnectionEvent.Progress(token, progress))
        }
    }

    /**
     * Wireless transport probe bound to ONE attempt (Phase 9.2W-A): a late
     * callback of a superseded/dead attempt can never mutate the current
     * attempt's diagnostics or emit its stages.
     */
    private fun probeListenerFor(
        attempt: Attempt,
        token: CarLifeSessionToken,
    ): WirlessTransportProbe = object : WirlessTransportProbe {
        private fun live(): Boolean =
            synchronized(lock) { this@CarLifeV2Provider.attempt }?.let { it === attempt && it.armed } == true

        private fun stage(stage: CarLifeProbeState) {
            attempt.emit(CarLifeConnectionEvent.WirelessStage(token, stage))
        }

        override fun onUdpListening(port: Int) {
            if (!live()) return
            attempt.wireless.udpListening = true
            attempt.wireless.udpPort = port
            stage(CarLifeProbeState.WIFI_UDP_LISTENING)
        }

        override fun onUdpStopped() {
            if (!live()) return
            attempt.wireless.udpListening = false
        }

        override fun onUdpPacketReceived(fromIp: String, total: Int) {
            if (!live()) return
            attempt.wireless.phoneIp = fromIp
            attempt.wireless.udpPackets = total
            stage(CarLifeProbeState.WIFI_PHONE_DISCOVERED)
        }

        override fun onTcpConnecting(host: String) {
            if (!live()) return
            attempt.wireless.phoneIp = host
            stage(CarLifeProbeState.WIFI_TCP_CONNECTING)
        }

        override fun onTcpChannelState(channel: Int, port: Int, state: String, error: String?) {
            if (!live()) return
            attempt.wireless.tcpChannels[channel] = state
        }

        override fun onTransportAttached(host: String) {
            if (!live()) return
            attempt.wireless.phoneIp = host
            attempt.wireless.transportAttached = true
            stage(CarLifeProbeState.WIFI_TRANSPORT_ATTACHED)
        }

        override fun onTransportError(error: String) {
            if (!live()) return
            // Immediate, real transport failure (e.g. UDP bind refused) - the
            // backend terminates this attempt NOW instead of waiting for a
            // discovery timeout that would misclassify it.
            attempt.emit(CarLifeConnectionEvent.Failed(token, error))
        }
    }

    companion object {
        /** Cache sentinel: the receiver's transport state is unknown/broken. */
        private const val CONNECT_TYPE_UNKNOWN = -1

        /** Real upstream receiver facade (one receiver per process). */
        internal fun realFacade(appContext: Context, config: CarLifeProviderConfig): CarLifeReceiverFacade {
            // DEMO_CHANNEL — NOT FOR PRODUCTION — COMPATIBILITY UNVERIFIED.
            // Public upstream sample configuration only; no invented channel,
            // no forged authentication, no phone-side patching. The connect
            // type is set via features (NOT setConnectType(), which would
            // auto-start the USB/AOA transport before resource arbitration).
            val features = mapOf(
                Configs.FEATURE_CONFIG_CONNECT_TYPE to CarLifeContext.CONNECTION_TYPE_AOA,
            )
            val configs = mapOf(
                Configs.CONFIG_PROTOCOL_VERSION to config.protocolVersion,
                // RAW_BRIDGE_MODE: MUST be present BEFORE CarLife.init because
                // RemoteDisplayRenderer reads it in its constructor. The host
                // video bridge owns decoding; the upstream FrameDecoder is
                // bypassed. Host-local only - never phone-visible.
                Configs.CONFIG_EXTERNAL_VIDEO_SINK to true,
            )
            CarLife.init(
                appContext,
                config.channel,
                config.cuid,
                features,
                requireNotNull(config.activityClass) { "CarLifeProviderConfig.activityClass is required" },
                configs,
            )
            // Capture the receiver exactly once; never re-read the global.
            val receiver: CarLifeReceiver = CarLife.receiver()
            return RealFacade(receiver, appContext)
        }
    }

    private class RealFacade(
        private val receiver: CarLifeReceiver,
        private val appContext: Context,
    ) : CarLifeReceiverFacade {
        override fun addConnectionListener(listener: ConnectionChangeListener) =
            receiver.registerConnectionChangeListener(listener)

        override fun removeConnectionListener(listener: ConnectionChangeListener) =
            receiver.unregisterConnectionChangeListener(listener)

        override fun addTransportListener(listener: com.baidu.carlife.sdk.internal.transport.TransportListener) =
            receiver.registerTransportListener(listener)

        override fun removeTransportListener(listener: com.baidu.carlife.sdk.internal.transport.TransportListener) =
            receiver.unregisterTransportListener(listener)

        override fun addProgressListener(listener: ConnectProgressListener) =
            receiver.addConnectProgressListener(listener)

        override fun removeProgressListener(listener: ConnectProgressListener) =
            receiver.removeConnectProgressListener(listener)

        override fun connect() = receiver.connect()

        override fun stopConnect() = receiver.stopConnect()

        override fun shutdown() = receiver.shutdown()

        override fun connectionState(): Int = receiver.connectionState

        override fun protocolVersion(): Int = receiver.protocolVersion

        override fun carlifeVersion(): Int = receiver.carlifeVersion

        override fun onSurfaceSizeChanged(width: Int, height: Int) =
            receiver.onSurfaceSizeChanged(width, height)

        override fun onTouchEvent(event: MotionEvent) =
            receiver.onTouchEvent(event)

        override fun configureConnectTypeWithoutStarting(type: Int) =
            receiver.configureConnectTypeWithoutStarting(type)

        override fun setTransportProbeListener(listener: WirlessTransportProbe?) =
            receiver.setTransportProbeListener(listener)

        override fun usbDeviceSummaries(): List<String> {
            // VID:PID only — never serial numbers or other identifying data.
            val manager = appContext.getSystemService(Context.USB_SERVICE)
                as? android.hardware.usb.UsbManager ?: return emptyList()
            return manager.deviceList.values.map { device ->
                "VID:PID " + Integer.toHexString(device.vendorId) + ":" + Integer.toHexString(device.productId)
            }
        }
    }
}
