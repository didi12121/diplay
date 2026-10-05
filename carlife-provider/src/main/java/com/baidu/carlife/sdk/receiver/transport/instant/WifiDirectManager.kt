package com.baidu.carlife.sdk.receiver.transport.instant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.CarLifeContext.Companion.CONNECTION_TYPE_WIFIDIRECT
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger

class WifiDirectManager(
    private val context: CarLifeContext,
    private val callbacks: Callbacks
) : BroadcastReceiver() {
    interface Callbacks {
        fun onDeviceConnected(info: WifiP2pInfo) {}
    }

    private val wifiP2pManager =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel

    private val discoverableTask: WifiDirectDiscoverableTask

    var isConnected = false
        private set

    init {
        channel = wifiP2pManager.initialize(
            context.applicationContext,
            Looper.getMainLooper(),
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // 如果channel出现异常的话，需要重新初始化
                    channel = wifiP2pManager.initialize(
                        context.applicationContext,
                        Looper.getMainLooper(),
                        this
                    )
                }
            })
        discoverableTask = WifiDirectDiscoverableTask(wifiP2pManager, channel)

        val filter = IntentFilter()
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        context.applicationContext.registerReceiver(this, filter)

        wifiP2pManager.requestConnectionInfo(channel) {
            Logger.d(Constants.TAG, "requestConnectionInfo ", it)
            if (it != null && it.groupFormed) {
                isConnected = true
            }
        }
    }

    fun discoverable() {
        discoverableTask.discoverable()
    }

    fun terminate() {
        Logger.d(Constants.TAG, "WifiP2pManager terminate")
        discoverableTask.terminate()
    }

    override fun onReceive(context1: Context, intent: Intent) {
        Logger.d(Constants.TAG, "onReceive intent.action: ", intent.action)
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {

                val info =
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO) as? WifiP2pInfo
                Logger.d(Constants.TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION info: $info")
                if (info != null && info.groupFormed) {
                    isConnected = true
                    callbacks.onDeviceConnected(info)
                    val groupInfo =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP) as? WifiP2pGroup
                    if (groupInfo != null) {
                        Logger.d(Constants.TAG, "WifiDirectManager group: ", groupInfo)
                    }
                } else {
                    isConnected = false

                    // p2p连接断开后，使当前设备可被发现
                    if (context.connectionType == CONNECTION_TYPE_WIFIDIRECT) {
                        discoverableTask.discoverable()
                    }
                }
                Logger.d(Constants.TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION ", isConnected)
            }
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val p2pIsEnable = intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED
                )
                if (p2pIsEnable != WifiP2pManager.WIFI_P2P_STATE_ENABLED) isConnected = false
            }
        }
    }
}