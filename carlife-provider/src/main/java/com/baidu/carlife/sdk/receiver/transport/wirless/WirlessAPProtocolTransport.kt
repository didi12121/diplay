package com.baidu.carlife.sdk.receiver.transport.wirless

import android.content.Intent
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.ProtocolTransport
import com.baidu.carlife.sdk.util.Logger.Companion.d
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

class WirlessAPProtocolTransport(
    private val mCarLifeContext: CarLifeContext,
    connectionListener: ConnectionListener?
) : ProtocolTransport(mCarLifeContext, connectionListener) {
    private var receiveBuf: ByteArray? = null
    private var mSocket: DatagramSocket? = null
    private var mPacket: DatagramPacket? = null
    private var mWIFIConnectThread: WifiConnectThread? = null
    private val mWirlessConnector = WirlessConnector()

    companion object {
        private const val TAG = "WirlessTransport"
        private const val RECEIVE_BUFFER_SIZE = 1024
        private const val BOARDCAST_WIFI_PORT = 7999
    }

    override fun connect() {
        try {
            d(Constants.TAG, "WirlessProtocolTransport connect listener")
            if (mSocket == null) {
                mSocket = DatagramSocket(BOARDCAST_WIFI_PORT)
                receiveBuf = ByteArray(RECEIVE_BUFFER_SIZE)
            }
            if (mWIFIConnectThread == null) {
                mWIFIConnectThread = WifiConnectThread()
                mWIFIConnectThread!!.start()
            }
        } catch (e: SocketException) {
            d(Constants.TAG, "connect error:", e)
        }
    }

    override fun ready() {}
    override fun listen() {}
    override fun terminate() {
        if (mWIFIConnectThread != null) {
            mWIFIConnectThread!!.stopConnect()
        }
        mWIFIConnectThread = null
        mWirlessConnector?.terminate()
        if (mSocket != null) {
            mSocket!!.close()
        }
        mSocket = null
        mPacket = null
        onConnectionDetached()
        d(Constants.TAG, "WirlessProtocolTransport terminate")
    }

    override fun write(message: CarLifeMessage) {
        mWirlessConnector!!.write(message)
    }

    override fun read(): CarLifeMessage {
        return mWirlessConnector!!.read()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    /**
     * 正在连接，包含连接成功
     *
     * @return
     */
    private val isConnecting: Boolean
        private get() = mCarLifeContext.connectionState != CarLifeContext.CONNECTION_DETACHED

    private inner class WifiConnectThread : Thread() {
        private var isRunning = true
        override fun run() {
            while (isRunning) {
                try {
                    mPacket = DatagramPacket(receiveBuf, receiveBuf!!.size)
                    if (!isConnecting) {
                        d(Constants.TAG, "start read broadcast socket")
                        mSocket!!.receive(mPacket)
                        d(Constants.TAG, "read broadcast socket")
                        if (isRunning && null != mPacket && !isConnecting) {
                            val serverIPAddress = mPacket!!.address
                            d(
                                Constants.TAG, "connect  packet:" +
                                        serverIPAddress.hostAddress
                            )
                            if (mWirlessConnector!!.startConnect(serverIPAddress.hostAddress)) {
                                d(Constants.TAG, "wifi onConnectionAttached")
                                onConnectionAttached()
                            }
                        }
                    } else {
                        d(
                            Constants.TAG, "is connected stop wifi :" +
                                    mCarLifeContext.connectionType
                        )
                        stopConnect()
                    }
                } catch (e: Exception) {
                    d(Constants.TAG, "UDPSocket IOException:", e)
                    try {
                        sleep(1000)
                    } catch (ignored: InterruptedException) {
                    }
                    // onConnectionDetached();
                }
            }
        }

        fun stopConnect() {
            isRunning = false
        }

        init {
            name = "WIFIConnectThread"
        }
    }
}