package com.baidu.carlife.sdk.util.wifip2p.operations

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pManager
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.wifip2p.WifiP2pOperation

class StopPeerDiscoverOperation(private val wifiP2pManager: WifiP2pManager,
                                private val channel: WifiP2pManager.Channel): WifiP2pOperation {
    @SuppressLint("MissingPermission")
    override fun execute(listener: WifiP2pManager.ActionListener) {
        wifiP2pManager.stopPeerDiscovery(channel, object: WifiP2pManager.ActionListener {
            override fun onSuccess() {
                listener.onSuccess()
            }

            override fun onFailure(reason: Int) {
                Logger.d(Constants.TAG, "StopPeerDiscoverOperation onFailure ", reason)
                listener.onSuccess()
            }
        })
    }
}