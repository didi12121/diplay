package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface WirlessStatusListener {
    fun onDeviceWirlessStatus(status: Int)
}