package com.baidu.carlife.sdk.receiver.transport.wirless

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.ProtocolTransport
import com.baidu.carlife.sdk.receiver.transport.instant.InstantConnectionSetup
import com.baidu.carlife.sdk.util.Logger.Companion.d
import com.baidu.carlife.sdk.util.Logger.Companion.e

/**
 * 无感连接
 */
class WirlessP2PProtocolTransport(
    private val mCarLifeContext: CarLifeContext,
    connectionListener: ConnectionListener?
) : ProtocolTransport(mCarLifeContext, connectionListener) {

    companion object {
        private const val TAG = "InstantProtocolTransport"
    }

    private val mWirelessConnector by lazy { WirlessConnector() }

    private val mInstantConnectionSetup by lazy {
        InstantConnectionSetup(mCarLifeContext, object : InstantConnectionSetup.Callback {
            override fun onDeviceConnected(address: String) {
                if (!isConnecting) {
                    try {
                        if (mWirelessConnector.startConnect(address)) {
                            d(Constants.TAG, "WifiDirect onConnectionAttached")
                            onConnectionAttached()
                        }
                    } catch (e: Exception) {
                        e(Constants.TAG, "wifi getByName exception ", e)
                    }
                }
            }
        })
    }

    override fun connect() {
        d(Constants.TAG, "InstantProtocolTransport connect")
        mInstantConnectionSetup.connect()
    }

    override fun ready() {}

    override fun listen() {}

    override fun terminate() {
        mInstantConnectionSetup.terminate()
        mWirelessConnector.terminate()
        onConnectionDetached()
        d(Constants.TAG, "InstantProtocolTransport terminate")
    }

    override fun write(message: CarLifeMessage) {
        mWirelessConnector.write(message)
    }

    override fun read(): CarLifeMessage {
        return mWirelessConnector.read()
    }

    /**
     * 正在连接，包含连接成功
     *
     * @return
     */
    private val isConnecting: Boolean
        get() = mCarLifeContext.connectionState != CarLifeContext.CONNECTION_DETACHED

}