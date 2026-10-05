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
    connectionListener: ConnectionListener?,
    // DiPlay host-local extension (Phase 9.2W-A): diagnostics-only probe.
    probe: WirlessTransportProbe? = null,
) : ProtocolTransport(mCarLifeContext, connectionListener) {
    private var receiveBuf: ByteArray? = null
    private var mSocket: DatagramSocket? = null
    private var mPacket: DatagramPacket? = null
    private var mWIFIConnectThread: WifiConnectThread? = null
    private val mWirlessConnector = WirlessConnector(probe)

    // DiPlay (9.2W-A.1): the probe is bound DYNAMICALLY (see setProbeListener)
    // instead of constructor-captured, so a transport configured before the
    // host bound its session probe is never left with a dead null probe.
    @Volatile
    private var probe: WirlessTransportProbe? = probe

    /**
     * DiPlay host-local extension (Phase 9.2W-B1): when set, [connect] opens
     * the TCP channel set DIRECTLY to this protocol-provided phone IP (BT
     * hotspot mode) and never binds UDP 7999 / runs discovery. When null the
     * legacy UDP discovery path is unchanged.
     */
    @Volatile
    var phoneIp: String? = null

    /** DiPlay (9.2W-A.1): (re)binds the diagnostics probe of the live session. */
    fun setProbeListener(listener: WirlessTransportProbe?) {
        probe = listener
        mWirlessConnector.probe = listener
    }

    // DiPlay host-local extension: discovery datagram counter (diagnostics).
    private var udpPacketsReceived = 0

    companion object {
        private const val TAG = "WirlessTransport"
        private const val RECEIVE_BUFFER_SIZE = 1024
        private const val BOARDCAST_WIFI_PORT = 7999
        /** DiPlay: exported for probe diagnostics/tests (upstream value). */
        const val UDP_DISCOVERY_PORT = BOARDCAST_WIFI_PORT
    }

    override fun connect() {
        // ---- BT hotspot mode (9.2W-B1): the phone IP came from the Bluetooth
        // bootstrap - open the TCP channel set directly, WITHOUT UDP 7999. ----
        val knownIp = phoneIp
        if (knownIp != null) {
            d(Constants.TAG, "WirlessProtocolTransport connect to protocol phone ip")
            Thread({
                try {
                    probe?.onTcpConnecting(knownIp)
                    if (mWirlessConnector.startConnect(knownIp)) {
                        probe?.onTransportAttached(knownIp)
                        onConnectionAttached()
                    } else {
                        probe?.onTransportError("TCP_CONNECT_FAILED:channel-set")
                    }
                } catch (e: Exception) {
                    d(Constants.TAG, "direct tcp connect error:", e)
                    probe?.onTransportError("TCP_CONNECT_FAILED:${e.javaClass.simpleName}")
                }
            }, "CarLifeBtHotspotTcp").start()
            return
        }
        try {
            d(Constants.TAG, "WirlessProtocolTransport connect listener")
            if (mSocket == null) {
                mSocket = DatagramSocket(BOARDCAST_WIFI_PORT)
                receiveBuf = ByteArray(RECEIVE_BUFFER_SIZE)
                // DiPlay: real bind success -> WIFI_UDP_LISTENING.
                probe?.onUdpListening(BOARDCAST_WIFI_PORT)
            }
            if (mWIFIConnectThread == null) {
                mWIFIConnectThread = WifiConnectThread()
                mWIFIConnectThread!!.start()
            }
        } catch (e: SocketException) {
            d(Constants.TAG, "connect error:", e)
            // DiPlay (9.2W-A.1): a bind failure is a REAL, IMMEDIATE transport
            // failure (e.g. UDP 7999 already taken) - never a silent log line
            // that later masquerades as a 90s NO_DISCOVERY_PACKET timeout.
            probe?.onTransportError("UDP_BIND_FAILED:${e.javaClass.simpleName}")
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
            try {
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
                                // DiPlay host-local extension: discovery
                                // diagnostics (phone IP + datagram count).
                                udpPacketsReceived++
                                val phoneIp = serverIPAddress.hostAddress ?: continue
                                probe?.onUdpPacketReceived(phoneIp, udpPacketsReceived)
                                probe?.onTcpConnecting(phoneIp)
                                if (mWirlessConnector!!.startConnect(phoneIp)) {
                                    d(Constants.TAG, "wifi onConnectionAttached")
                                    probe?.onTransportAttached(phoneIp)
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
                        // DiPlay fix (Phase 9.2W-A): never sleep AFTER the
                        // stop request - terminate() must end this thread
                        // deterministically (no zombie discovery thread into
                        // the next session).
                        try {
                            if (isRunning) {
                                sleep(1000)
                            }
                        } catch (ignored: InterruptedException) {
                        }
                        // onConnectionDetached();
                    }
                }
            } finally {
                // DiPlay: deterministic lifecycle signal for probe/tests.
                probe?.onUdpStopped()
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
