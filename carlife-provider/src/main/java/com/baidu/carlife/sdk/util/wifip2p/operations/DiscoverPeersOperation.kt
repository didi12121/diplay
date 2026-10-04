package com.baidu.carlife.sdk.util.wifip2p.operations

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pManager
import com.baidu.carlife.sdk.util.wifip2p.WifiP2pOperation

class DiscoverPeersOperation(private val wifiP2pManager: WifiP2pManager,
                             private val channel: WifiP2pManager.Channel): WifiP2pOperation {
    @SuppressLint("MissingPermission")
    override fun execute(listener: WifiP2pManager.ActionListener) {
        wifiP2pManager.discoverPeers(channel, listener)
    }
}