package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface ConnectionChangeListener {
    /**
     * 连接建立，分下面三种情况
     * 1 AOA连接建立
     * 2 ADB六条Socket建立成功
     * 3 Wifi六条Socket建立成功
     */
    fun onConnectionAttached(context: CarLifeContext) {}

    /**
     * 在未detach的前提下，重新进行attach操作，会回调reattach
     */
    fun onConnectionReattached(context: CarLifeContext) {}

    /**
     * 连接断开
     * 1 USB断开
     * 2 网络断开
     */
    fun onConnectionDetached(context: CarLifeContext) {}

    /**
     * 协议校验成功
     * statistics info 校验成功时
     */
    fun onConnectionEstablished(context: CarLifeContext) {}

    /**
     * 与设备协议版本不兼容
     * ProtocolVersionMatch 校验失败时
     */
    fun onConnectionVersionNotSupprt(context: CarLifeContext) {}

    /**
     * 车机渠道号验证失败
     * CarlifeAuthenResult 校验失败时
     */
    fun onConnectionAuthenFailed(context: CarLifeContext) {}
}