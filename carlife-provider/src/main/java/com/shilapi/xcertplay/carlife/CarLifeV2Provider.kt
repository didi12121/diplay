// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.content.Context
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.receiver.CarLifeReceiver
import com.baidu.carlife.sdk.receiver.ConnectProgressListener

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

    /** The attempt currently bound to SDK callbacks (token-bound listener). */
    private var attempt: Attempt? = null

    private class Attempt(
        val token: CarLifeSessionToken,
        val sink: (CarLifeConnectionEvent) -> Unit,
    ) {
        /** False until this attempt's connect() ran: registration replays and
         *  leftovers of older attempts cannot masquerade as this session. */
        @Volatile
        var armed = false

        lateinit var listener: ConnectionChangeListener
        lateinit var progress: ConnectProgressListener

        fun emit(event: CarLifeConnectionEvent) {
            if (!armed) return // registration replay or pre-connect leftover
            sink(event) // event carries THIS attempt's fixed token
        }
    }

    override fun initialize(context: Context, config: CarLifeProviderConfig) {
        synchronized(lock) {
            // Idempotent: one CarLife.init per application process. CarLife is
            // a global singleton holding one receiver — a second init would
            // replace it and orphan the previous one.
            if (facade != null) return
            facade = facadeFactory(context, config)
        }
    }

    override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
        val facade = facade ?: error("CarLifeV2Provider.initialize() must run first")
        // ---- Fence the previous attempt BEFORE binding the new token ----
        val old = synchronized(lock) { attempt.also { attempt = null } }
        if (old != null) {
            // 1. unregister the old listener: the SDK dispatch list no longer
            //    contains it, so no further callback can reach the old sink;
            runCatching { facade.removeConnectionListener(old.listener) }
            runCatching { facade.removeProgressListener(old.progress) }
            detachVideo(old.token)
            // 2. fence the old transport: tear its attempt down AND suppress
            //    the detach auto-reconnect so it can never come back.
            runCatching { facade.shutdown() }
        }
        // ---- Bind the new attempt ----
        val current = Attempt(token, listener)
        current.listener = connectionListenerFor(current, token)
        current.progress = progressListenerFor(current, token)
        synchronized(lock) { attempt = current }
        // Registration may synchronously replay stale connection state — the
        // attempt is not armed yet, so those events are dropped.
        runCatching { facade.addConnectionListener(current.listener) }
        runCatching { facade.addProgressListener(current.progress) }
        facade.connect()
        current.armed = true
    }

    override fun stopConnection(token: CarLifeSessionToken) {
        val facade = facade ?: return
        val current = synchronized(lock) {
            if (attempt?.token != token) return
            attempt.also { attempt = null }
        } ?: return
        runCatching { facade.removeConnectionListener(current.listener) }
        runCatching { facade.removeProgressListener(current.progress) }
        runCatching { facade.shutdown() }
    }

    override fun diagnostics(): CarLifeProviderDiagnostics {
        val facade = facade ?: return CarLifeProviderDiagnostics()
        return CarLifeProviderDiagnostics(
            usbDevices = runCatching { facade.usbDeviceSummaries() }.getOrDefault(emptyList()),
            localProtocolVersion = runCatching { facade.protocolVersion() }.getOrNull(),
            phoneCarlifeProtocolVersion = runCatching { facade.carlifeVersion() }.getOrNull(),
            connectionState = runCatching { facade.connectionState() }.getOrDefault(0),
        )
    }

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

    override fun dispose() {
        videoBridges.keys.toList().forEach { detachVideo(it) }
        val facade = facade ?: return
        val current = synchronized(lock) { attempt.also { attempt = null } }
        if (current != null) {
            runCatching { facade.removeConnectionListener(current.listener) }
            runCatching { facade.removeProgressListener(current.progress) }
        }
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

    companion object {
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
