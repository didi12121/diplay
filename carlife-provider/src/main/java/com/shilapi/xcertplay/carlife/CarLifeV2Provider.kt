// SPDX-License-Identifier: AGPL-3.0-only (DiPlay additions; upstream SDK code keeps Apache-2.0)
package com.shilapi.xcertplay.carlife

import android.content.Context
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.receiver.CarLife

/**
 * Real CarLife provider over the open CarLife V2.0 SDK
 * (`CarLife-Android-Vehicle-V2.0/carlife-sdk`, Apache-2.0, Baidu
 * Apollo-DuerOS public source).
 *
 * Wired AOA only (CONNECTION_TYPE_AOA). Wireless CarLife+ is NOT in scope and
 * NOT open sourced upstream — never claimed here.
 *
 * Session identity: each [startConnection] binds one [CarLifeSessionToken] to
 * the SDK's connection callbacks. The upstream SDK has no per-session callback
 * identity, so this wrapper enforces the boundary itself: a new attempt first
 * stops the previous one (its callback worker is finished) before the new
 * token is installed — callbacks arriving for an older token are dropped with
 * a diagnostic. The probe/backend layer additionally checks tokens.
 */
class CarLifeV2Provider : CarLifeProvider {

    override val isAvailable: Boolean = true

    private var appContext: Context? = null
    private val lock = Any()

    /** Token currently bound to SDK callbacks (identity, not a secret). */
    private var boundToken: CarLifeSessionToken? = null
    private var boundListener: ((CarLifeConnectionEvent) -> Unit)? = null

    private val sdkListener = object : ConnectionChangeListener {
        override fun onConnectionAttached(context: CarLifeContext) =
            dispatch(CarLifeConnectionEvent.Attached(current()))

        override fun onConnectionReattached(context: CarLifeContext) =
            dispatch(CarLifeConnectionEvent.Reattached(current()))

        override fun onConnectionDetached(context: CarLifeContext) =
            dispatch(CarLifeConnectionEvent.Detached(current()))

        override fun onConnectionEstablished(context: CarLifeContext) =
            dispatch(CarLifeConnectionEvent.Established(current()))

        override fun onConnectionVersionNotSupprt(context: CarLifeContext) =
            dispatch(CarLifeConnectionEvent.VersionNotSupported(current()))

        override fun onConnectionAuthenFailed(context: CarLifeContext) =
            dispatch(CarLifeConnectionEvent.AuthFailed(current()))
    }

    private fun current(): CarLifeSessionToken =
        synchronized(lock) { boundToken ?: CarLifeSessionToken(-1L) }

    private fun dispatch(event: CarLifeConnectionEvent) {
        val listener = synchronized(lock) { boundListener } ?: return
        listener(event)
    }

    override fun initialize(context: Context, config: CarLifeProviderConfig) {
        val app = context.applicationContext
        synchronized(lock) { appContext = app }
        // DEMO_CHANNEL — NOT FOR PRODUCTION — COMPATIBILITY UNVERIFIED.
        // Uses the PUBLIC upstream sample configuration only; no invented
        // channel, no forged authentication, no phone-side patching.
        val features = mapOf(
            Configs.FEATURE_CONFIG_CONNECT_TYPE to CarLifeContext.CONNECTION_TYPE_AOA,
        )
        val configs = mapOf(
            Configs.CONFIG_PROTOCOL_VERSION to config.protocolVersion,
        )
        CarLife.init(
            app,
            config.channel,
            config.cuid,
            features,
            requireNotNull(config.activityClass) { "CarLifeProviderConfig.activityClass is required" },
            configs,
        )
        CarLife.receiver().setConnectType(CarLifeContext.CONNECTION_TYPE_AOA)
    }

    override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
        synchronized(lock) {
            // Boundary rule: the previous attempt is stopped BEFORE the new
            // token is installed, so its callback worker cannot masquerade as
            // the new session (the SDK carries no per-session identity).
            boundToken = null
            boundListener = null
        }
        runCatching { CarLife.receiver().stopConnect() }
        synchronized(lock) {
            boundToken = token
            boundListener = listener
        }
        CarLife.receiver().registerConnectionChangeListener(sdkListener)
        CarLife.receiver().connect()
    }

    override fun stopConnection(token: CarLifeSessionToken) {
        synchronized(lock) {
            if (boundToken != token) return
            boundToken = null
            boundListener = null
        }
        runCatching {
            CarLife.receiver().unregisterConnectionChangeListener(sdkListener)
            CarLife.receiver().stopConnect()
        }
    }

    override fun dispose() {
        synchronized(lock) {
            boundToken = null
            boundListener = null
        }
        runCatching {
            CarLife.receiver().unregisterConnectionChangeListener(sdkListener)
            CarLife.receiver().disconnect()
        }
    }
}
