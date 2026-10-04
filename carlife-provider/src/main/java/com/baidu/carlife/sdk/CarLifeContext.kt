package com.baidu.carlife.sdk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import com.baidu.carlife.protobuf.*
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import java.io.File
import java.util.concurrent.ExecutorService

@DoNotStrip
interface CarLifeContext: TransportListener, ModuleStateChangeListener, WirlessStatusListener {
    companion object {
        /**
         * 连接断开
         * 1 USB断开
         * 2 网络断开
         */
        const val CONNECTION_DETACHED    = 0
        /**
         * 连接建立，分下面三种情况
         * 1 AOA连接建立
         * 2 ADB六条Socket建立成功
         * 3 Wifi六条Socket建立成功
         */
        const val CONNECTION_ATTACHED    = 1

        /**
         * 重新attach，发生在
         * CONNECTION_ATTACHED -> CONNECTION_ATTACHED
         * CONNECTION_ESTABLISHED -> CONNECTION_ATTACHED
         */
        const val CONNECTION_REATTACH    = 2

        /**
         * 协议校验成功
         * statistics info 校验成功时
         */
        const val CONNECTION_ESTABLISHED = 3

        /**
         * 连接类型
         * aoa连接
         */
        const val CONNECTION_TYPE_AOA     = 0x0002
        /**
         * 连接类型
         * wifi 热点连接
         */
        const val CONNECTION_TYPE_HOTSPOT = 0x0005
        /**
         * 连接类型
         * wifi 直连
         */
        const val CONNECTION_TYPE_WIFIDIRECT = 0x0009
    }

    // 应用context
    val applicationContext: Context

    // 应用系统类型
    val osType: String

    val versionName: String

    val versionCode: Int

    // CarLife 协议版本
    var protocolVersion: Int

    // CarLife 手机端版本
    var carlifeVersion: Int

    var isVersionSupport: Boolean

    // root dir for carlife stuff
    val dataDir: File

    // root dir for carlife caches
    val cacheDir: File

    // 存储设备相关信息，方便获取
    var deviceInfo: CarlifeDeviceInfoProto.CarlifeDeviceInfo?

    // 存储渠道信息，方便获取
    var statisticsInfo: CarlifeStatisticsInfoProto.CarlifeStatisticsInfo?

    // 获取连接状态
    val connectionState: Int

    // 连接类型
    var connectionType: Int

    var authResult: Boolean

    val sharedPreferences: SharedPreferences

    // 断开连接
    fun terminate()

    fun setFeatures(features: Map<String, Int>)

    fun setFeature(key: String, feature: Int)

    fun setFeature(key: String, feature: Int, isLocal: Boolean)

    fun getFeature(key: String, defaultConfig: Int): Int

    fun listFeatures(): List<CarlifeFeatureConfigProto.CarlifeFeatureConfig>

    fun addFeatureConfigChangeListener(listener: FeatureConfigChangeListener)

    fun removeFeatureConfigChangeListener(listener: FeatureConfigChangeListener)

    // 异步发送消息
    fun postMessage(message: CarLifeMessage)

    // 同步发送消息
    fun sendMessage(message: CarLifeMessage)

    fun postMessage(channel: Int, serviceType: Int) {
        postMessage(CarLifeMessage.obtain(channel, serviceType))
    }

    fun sendMessage(channel: Int, serviceType: Int) {
        sendMessage(CarLifeMessage.obtain(channel, serviceType))
    }

    fun setConfig(key: String, config: Any)

    fun removeConfig(key: String)

    fun <T> getConfig(key: String, defaultConfig: T): T

    fun <T> getConfig(key: String): T? {
        return getConfig(key, null)
    }

    // encryption
    var isEncryptionEnabled: Boolean

    fun encrypt(message: CarLifeMessage): CarLifeMessage

    fun decrypt(message: CarLifeMessage): CarLifeMessage

    // helpers
    fun post(runnable: ()->Unit)

    fun post(runnable: Runnable) {
        post { runnable.run() }
    }

    fun postDelayed(delayed: Long, runnable: ()->Unit)

    fun postDelayed(runnable: Runnable, delayed: Long) {
        postDelayed(delayed) { runnable.run() }
    }

    // thread pools
    fun main(): Handler

    fun io(): ExecutorService

    fun compute(): ExecutorService

    // 音频焦点
    fun requestAudioFocus(listener: AudioManager.OnAudioFocusChangeListener,
                          streamType: Int,
                          focusType: Int,
                          focusGrantDelayed: Boolean = false,
                          focusForceGrant: Boolean = false): Int

    fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener)

    // Transport related
    fun registerTransportListener(listener: TransportListener)

    fun unregisterTransportListener(listener: TransportListener)

    fun registerConnectionChangeListener(listener: ConnectionChangeListener)

    fun unregisterConnectionChangeListener(listener: ConnectionChangeListener)

    fun registerWirlessStatusListeners(listener: WirlessStatusListener)

    fun unregisterWirlessStatusListeners(listener: WirlessStatusListener)

    // configuration change related
    fun notifyConfigurationChange(configuration: Int)

    fun registerConfigurationListener(listener: ConfigurationChangeListener)

    fun unregisterConfigurationListener(listener: ConfigurationChangeListener)

    // Module related
    fun findModule(id: Int): CarLifeModule?

    fun listModule(): List<CarLifeModule>

    fun addModule(module: CarLifeModule)

    fun removeModule(module: CarLifeModule)

    // subscribe related
    fun findSubscriber(id: Int): CarLifeSubscriber?

    fun addSubscriber(subscriber: CarLifeSubscriber)

    fun removeSubscriber(subscriber: CarLifeSubscriber)

    fun listSubscriber(): List<CarlifeSubscribeMobileCarLifeInfoProto.CarlifeSubscribeMobileCarLifeInfo>

    // subscribable related
    fun findSubscribable(id: Int): CarLifeSubscribable?

    fun addSubscribable(subscribable: CarLifeSubscribable)

    fun removeSubscribable(subscribable: CarLifeSubscribable)

    fun listSubscribable(): List<CarlifeVehicleInfoProto.CarlifeVehicleInfo>

    fun onNewIntent(intent: Intent)

    // 判断当前连接是否成功建立，对应Established
    fun isConnected(): Boolean

    fun isAttached(): Boolean

    fun getConnectType(): Int

    /**
     * 与设备建立连接
     * @CallSuper
     */
    fun onConnectionAttached() {
        onConnectionAttached(this)
    }

    /**
     *  与设备断开连接
     *  @CallSuper
     */
    fun onConnectionDetached() {
        onConnectionDetached(this)
    }

    /**
     * 与设备经过握手
     * @CallSuper
     */
    fun onConnectionEstablished() {
        onConnectionEstablished(this)
    }

    /**
     *  与设备协议版本不兼容
     *  @CallSuper
     */
    fun onConnectionVersionNotSupprt() {
        onConnectionVersionNotSupprt(this)
    }

    /**
     *  车机渠道号验证失败
     *  @CallSuper
     */
    fun onConnectionAuthenFailed() {
        onConnectionAuthenFailed(this)
    }
}