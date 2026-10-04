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

    fun disconnect()

    fun ready()

    /**
     * 设置车机连接类型
     * USB: CONNECTION_TYPE_AOA
     * AP: CONNECTION_TYPE_HOTSPOT
     * P2P: CONNECTION_TYPE_WIFIDIRECT
     */
    fun setConnectType(type:Int)
}
