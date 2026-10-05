package com.baidu.carlife.sdk.util.wifip2p

import android.net.wifi.p2p.WifiP2pManager

interface WifiP2pOperation {
    fun execute(listener: WifiP2pManager.ActionListener)
}