package com.baidu.carlife.sdk.receiver

import android.app.Activity
import android.view.MotionEvent
import android.view.Surface
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.DisplaySpec
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface CarLifeReceiver: CarLifeContext {

    val activityClass: Class<out Activity>
    /**
     * 监听当前连接进度
     */
    fun addConnectProgressListener(listener: ConnectProgressListener)

    fun removeConnectProgressListener(listener: ConnectProgressListener)

    fun setFileTransferListener(listener: FileTransferListener?)

    // 设置视频帧的渲染参数
    fun setDisplaySpec(displaySpec: DisplaySpec)

    // 获取视频帧的渲染参数
    fun getDisplaySpec(): DisplaySpec

    // 创建好SurfaceView之后，调用此方法传递Surface, 用于构建MediaCodec，解码渲染
    // 传递空的话，停止渲染，非空开始渲染，主线程调用
    fun setSurface(surface: Surface?)

    fun setSurfaceRequestCallback(callback: SurfaceRequestCallback?)

    fun onSurfaceSizeChanged(width: Int, height: Int)

    fun addOnVideoSizeChangedListener(listener: OnVideoSizeChangedListener)

    fun removeOnVideoSizeChangedListener(listener: OnVideoSizeChangedListener)

    // Activity 生命周期回调
    fun onActivityStarted()

    fun onActivityStopped()

    // 传递反控事件
    fun onTouchEvent(event: MotionEvent)

    // 传递硬按键消息
    fun onKeyEvent(keyCode: Int)

    fun initStatisticsInfo(channel: String, cuid: String)

    fun connect()

    fun stopConnect()

    /** DiPlay lifecycle extension (Phase 9.1.1): permanently fences this receiver's transport - no auto-reconnect after stop. */
    fun shutdown()

    fun disconnect()

    fun ready()

    /**
     * 设置车机连接类型
     * USB: CONNECTION_TYPE_AOA
     * AP: CONNECTION_TYPE_HOTSPOT
     * P2P: CONNECTION_TYPE_WIFIDIRECT
     */
    fun setConnectType(type:Int)

    /**
     * DiPlay lifecycle extension (Phase 9.2W-A, host-local only): reconfigures
     * the LOCAL transport family WITHOUT starting any network/USB work.
     *
     * Unlike [setConnectType] (which auto-connects via configConnectType +
     * transport.connect()), this only:
     *  - fully stops the previous transport (and suppresses detach
     *    auto-reconnect),
     *  - rebuilds the local transport implementation for [type],
     *  - changes nothing on the wire (protocol/version/auth untouched).
     * The caller starts the attempt later via [connect]. Only legal while no
     * session is active; session fencing stays at the DiPlay provider
     * boundary (CarLifeSessionToken).
     */
    fun configureConnectTypeWithoutStarting(type: Int) {}

    /**
     * DiPlay host-local extension (Phase 9.2W-A, diagnostics only): wireless
     * transport progress probe. Never affects the protocol path.
     */
    fun setTransportProbeListener(listener: com.baidu.carlife.sdk.receiver.transport.wirless.WirlessTransportProbe?) {}

    /**
     * DiPlay host-local extension (Phase 9.2W-B1): binds the PROTOCOL-PROVIDED
     * phone IP (from the Bluetooth bootstrap) so the next [connect] opens the
     * TCP channel set directly (no UDP 7999 discovery). Never guesses an IP.
     */
    fun setWirelessPhoneIp(ip: String) {}
}
