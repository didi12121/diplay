package com.baidu.carlife.sdk.internal

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.util.Log
import com.baidu.carlife.protobuf.*
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.CarLifeContext.Companion.CONNECTION_TYPE_AOA
import com.baidu.carlife.sdk.CarLifeContext.Companion.CONNECTION_ATTACHED
import com.baidu.carlife.sdk.CarLifeContext.Companion.CONNECTION_ESTABLISHED
import com.baidu.carlife.sdk.CarLifeContext.Companion.CONNECTION_DETACHED
import com.baidu.carlife.sdk.CarLifeContext.Companion.CONNECTION_REATTACH
import com.baidu.carlife.sdk.Configs.*
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import com.baidu.carlife.sdk.util.availableProcessors
import java.io.File
import java.util.concurrent.*

@DoNotStrip
abstract class CarLifeContextImpl(private val context: Context, configs: Map<String, Any> = mapOf()):
    CarLifeContext {
    @Volatile
    override var connectionState = CONNECTION_DETACHED

    override var connectionType = CONNECTION_TYPE_AOA

    override var authResult: Boolean = false


    private val handler = Handler(context.mainLooper)
    private val configurationMap: MutableMap<String, Any> = HashMap(configs)

    private var monitorListeners = 0
        set(value) {
            field = value
            Logger.d(Constants.TAG, "CarLifeContextImpl monitor listener count ", value)
        }

    // 收发message消息的监听器
    private val transportListeners = LinkedBlockingQueue<TransportListener>()

    // 连接改变的监听器
    private val connectionChangeListeners = mutableSetOf<ConnectionChangeListener>()

    // 配置改变监听器
    private val configurationChangeListeners = mutableSetOf<ConfigurationChangeListener>()

    // 手机wifi状态改变的监听器
    private val wirlessStatusListeners = mutableListOf<WirlessStatusListener>()

    private val modules = hashSetOf<CarLifeModule>()
    private val subscribables = hashSetOf<CarLifeSubscribable>()
    private val subscribers = hashSetOf<CarLifeSubscriber>()

    private var featureMap = mutableMapOf<String, Int>()
    private val featureConfigChangeListeners = mutableSetOf<FeatureConfigChangeListener>()

    val audioFocusManager = AudioFocusManager(context)

    // 线程池
    // 计算密集型，线程数保持跟核心数一般多
    private val executorCompute: ThreadPoolExecutor = ThreadPoolExecutor(
        availableProcessors,
        availableProcessors,
        60,
        TimeUnit.SECONDS,
        LinkedBlockingDeque<Runnable>(50)).apply { allowCoreThreadTimeOut(true) }

    // IO密集型，线程数两倍核心数
    private val executorIO =  ThreadPoolExecutor(
        availableProcessors * 2,
        availableProcessors * 2,
        60,
        TimeUnit.SECONDS,
        LinkedBlockingDeque<Runnable>(50)).apply { allowCoreThreadTimeOut(true) }

    final override val dataDir: File
    final override val cacheDir: File

    init {
        // 判断sd卡是否存在, 优先使用外部存储
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
            && Build.VERSION.SDK_INT > 21) {
            dataDir = context.getExternalFilesDir(null)!!
            cacheDir = context.externalCacheDir!!
        } else {
            dataDir = context.filesDir
            cacheDir = context.cacheDir
        }

        // logger 初始化
        val logger = Logger(File(dataDir, "log"))
        logger.level = getConfig(CONFIG_LOG_LEVEL, Log.WARN)
        logger.maxFileSize = getConfig(CONFIG_MAX_LOG_FILE_SIZE, Logger.DEFAULT_MAX_FILE_SIZE)
        logger.maxFileCount = getConfig(CONFIG_MAX_LOG_FILE_COUNT, Logger.DEFAULT_MAX_FILE_COUNT)
        Logger.default = logger
    }

    // 存储设备相关信息，方便获取
    override var deviceInfo: CarlifeDeviceInfoProto.CarlifeDeviceInfo? = null

    override var protocolVersion: Int = 0

    override var carlifeVersion: Int = 0

    override var isVersionSupport: Boolean = true

    // 存储渠道信息，方便获取
    override var statisticsInfo: CarlifeStatisticsInfoProto.CarlifeStatisticsInfo? = null

    override val applicationContext: Context get() {
        return context
    }

    override var isEncryptionEnabled: Boolean = false

    override val osType: String get() {
        return "Android"
    }

    override val versionName: String get() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0);
        return packageInfo.versionName ?: "" // Kotlin 2.x: versionName is String? on modern SDKs
    }

    override val versionCode: Int get() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionCode
    }

    override val sharedPreferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("CarLifeSDK", Context.MODE_PRIVATE)
    }

    override fun isConnected(): Boolean {
        return connectionState == CONNECTION_ESTABLISHED
    }

    override fun getConnectType(): Int {
        return connectionType
    }

    override fun isAttached(): Boolean {
        return connectionState != CONNECTION_DETACHED
    }

    @Synchronized
    final override fun setFeatures(features: Map<String, Int>) {
        featureMap = HashMap(features)

        featureConfigChangeListeners.forEach { it.onFeatureConfigChanged() }
    }

    @Synchronized
    override fun setFeature(key: String, feature: Int, isLocal: Boolean) {
        featureMap[key] = feature

        featureConfigChangeListeners.forEach { it.onFeatureConfigChanged() }
    }

    final override fun getFeature(key: String, defaultConfig: Int): Int {
        featureMap[key]?.let {
            return it
        }
        return defaultConfig
    }

    override fun listFeatures(): List<CarlifeFeatureConfigProto.CarlifeFeatureConfig> {
        return featureMap.map {
            CarlifeFeatureConfigProto.CarlifeFeatureConfig.newBuilder()
                .setKey(it.key)
                .setValue(it.value)
                .build()
        }
    }

    @Synchronized
    override fun addFeatureConfigChangeListener(listener: FeatureConfigChangeListener) {
        featureConfigChangeListeners.add(listener)
    }

    @Synchronized
    override fun removeFeatureConfigChangeListener(listener: FeatureConfigChangeListener) {
        featureConfigChangeListeners.remove(listener)
    }

    final override fun setConfig(key: String, config: Any) {
        configurationMap[key] = config
    }

    final override fun removeConfig(key: String) {
        configurationMap.remove(key)
    }

    @Suppress("UNCHECKED_CAST")
    final override fun <T> getConfig(key: String, defaultConfig: T): T {
        return configurationMap[key]?.let { return it as T } ?: defaultConfig
    }

    override fun onNewIntent(intent: Intent) {

    }

    // helpers
    override fun post(runnable: ()->Unit) {
        handler.post(runnable)
    }

    override fun postDelayed(delayed: Long, runnable: ()->Unit) {
        handler.postDelayed(runnable, delayed)
    }

    override fun main(): Handler {
        return handler
    }

    override fun io(): ExecutorService {
        return executorIO
    }

    override fun compute(): ExecutorService {
        return executorCompute
    }

    override fun requestAudioFocus(
        listener: AudioManager.OnAudioFocusChangeListener,
        streamType: Int,
        focusType: Int,
        focusGrantDelayed: Boolean,
        focusForceGrant: Boolean
    ): Int {
        return audioFocusManager.requestAudioFocus(listener,
            streamType,
            focusType,
            focusGrantDelayed,
            focusForceGrant)
    }

    override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) {
        audioFocusManager.abandonAudioFocus(listener)
    }

    // Transport related
    override fun registerTransportListener(listener: TransportListener) {
        ++monitorListeners
        transportListeners.add(listener)
        // TransportListener本身也是ConnectionChangeListener，单独添加到connectionChangeListeners里
        registerConnectionChangeListener(listener)
    }

    @Synchronized
    override fun unregisterTransportListener(listener: TransportListener) {
        --monitorListeners
        transportListeners.remove(listener)
        // TransportListener本身也是ConnectionChangeListener，需要同时移除
        unregisterConnectionChangeListener(listener)
    }

    override fun registerConnectionChangeListener(listener: ConnectionChangeListener) {
        if (!connectionChangeListeners.contains(listener)) {
            ++monitorListeners
            if (connectionState != CONNECTION_DETACHED) {
                // 如果当前处于连接状态，直接回调
                when (connectionState) {
                    CONNECTION_ATTACHED -> listener.onConnectionAttached(this)
                    CONNECTION_ESTABLISHED -> listener.onConnectionEstablished(this)
                }
            }
            connectionChangeListeners.add(listener)
        }
    }

    @Synchronized
    override fun unregisterConnectionChangeListener(listener: ConnectionChangeListener) {
        if (connectionChangeListeners.remove(listener)) {
            --monitorListeners
        }
    }

    @Synchronized
    override fun notifyConfigurationChange(configuration: Int) {
        configurationChangeListeners.forEach {
            it.onConfigurationChanged(configuration)
        }
    }

    override fun registerConfigurationListener(listener: ConfigurationChangeListener) {
        ++monitorListeners
        configurationChangeListeners.add(listener)
    }

    @Synchronized
    override fun unregisterConfigurationListener(listener: ConfigurationChangeListener) {
        --monitorListeners
        configurationChangeListeners.remove(listener)
    }

    override fun registerWirlessStatusListeners(listener: WirlessStatusListener) {
        ++monitorListeners
        wirlessStatusListeners.add(listener)
    }

    @Synchronized
    override fun unregisterWirlessStatusListeners(listener: WirlessStatusListener) {
        --monitorListeners
        wirlessStatusListeners.remove(listener)
    }

    @Synchronized
    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        transportListeners.forEach {
            // 如果有listener消耗了此消息，停止继续分发
            if (it.onReceiveMessage(context, message)) {
                return true
            }
        }
        return true
    }

    @Synchronized
    override fun onSendMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        transportListeners.forEach {
            if (it.onSendMessage(context, message)) {
                return true
            }
        }
        return true
    }

    // Module related
    override fun findModule(id: Int): CarLifeModule? {
        return modules.find { it.id == id }
    }

    override fun listModule(): List<CarLifeModule> {
        return modules.toList()
    }

    override fun addModule(module: CarLifeModule) {
        module.setStateChangeListener(this)
        modules.add(module)
        // CarLifeModule本身也是ConnectionChangeListener，单独添加到connectionChangeListeners里
        registerTransportListener(module)
    }

    @Synchronized
    override fun removeModule(module: CarLifeModule) {
        if (modules.remove(module)) {
            module.setStateChangeListener(null)
        }
        // CarLifeModule本身也是ConnectionChangeListener，需要同时移除
        unregisterTransportListener(module)
    }

    override fun onModuleStateChanged(module: CarLifeModule) {
        // this should be override by carlife sender
    }

    @Synchronized
    override fun onDeviceWirlessStatus(status: Int) {
        // wifi 连接状态改变
        wirlessStatusListeners.forEach {
            it.onDeviceWirlessStatus(status)
        }
    }

    // subscribe related
    override fun findSubscriber(id: Int): CarLifeSubscriber? {
        return subscribers.find { it.id == id }
    }

    override fun addSubscriber(subscriber: CarLifeSubscriber) {
        subscribers.add(subscriber)
        transportListeners.add(subscriber)
    }

    @Synchronized
    override fun removeSubscriber(subscriber: CarLifeSubscriber) {
        subscribers.remove(subscriber)
        transportListeners.remove(subscriber)
    }

    @Synchronized
    override fun listSubscriber(): List<CarlifeSubscribeMobileCarLifeInfoProto.CarlifeSubscribeMobileCarLifeInfo> {
        return subscribers.map {
            CarlifeSubscribeMobileCarLifeInfoProto.CarlifeSubscribeMobileCarLifeInfo.newBuilder()
                .setModuleID(it.id)
                .build()
        }
    }

    // subscribable related
    override fun findSubscribable(id: Int): CarLifeSubscribable? {
        return subscribables.find { it.id == id }
    }

    override fun addSubscribable(subscribable: CarLifeSubscribable) {
        subscribables.add(subscribable)
    }

    @Synchronized
    override fun removeSubscribable(subscribable: CarLifeSubscribable) {
        subscribables.remove(subscribable)
    }

    @Synchronized
    override fun listSubscribable(): List<CarlifeVehicleInfoProto.CarlifeVehicleInfo> {
        return subscribables.map {
            CarlifeVehicleInfoProto.CarlifeVehicleInfo.newBuilder()
                .setModuleID(it.id)
                .setSupportFlag(if (it.SupportFlag) 1 else 0)
                .build()
        }
    }

    @Synchronized
    final override fun onConnectionAttached(context: CarLifeContext) {
        if (connectionState == CONNECTION_DETACHED) {
            connectionState = CONNECTION_ATTACHED
            connectionChangeListeners.forEach { it.onConnectionAttached(this) }
        }
        else if (connectionState != CONNECTION_REATTACH) {
            connectionState = CONNECTION_REATTACH

            connectionChangeListeners.forEach { it.onConnectionReattached(this) }
        }
    }

    @Synchronized
    final override fun onConnectionDetached(context: CarLifeContext) {
        if (connectionState != CONNECTION_DETACHED) {
            connectionState = CONNECTION_DETACHED

            connectionChangeListeners.forEach { it.onConnectionDetached(this) }
        }
    }

    @Synchronized
    final override fun onConnectionEstablished(context: CarLifeContext) {
        if (connectionState != CONNECTION_ESTABLISHED) {
            connectionState = CONNECTION_ESTABLISHED

            connectionChangeListeners.forEach { it.onConnectionEstablished(this) }
        }
    }

    @Synchronized
    final override fun onConnectionVersionNotSupprt(context: CarLifeContext) {

        connectionChangeListeners.forEach {
            it.onConnectionVersionNotSupprt(this)
        }
    }

    @Synchronized
    final override fun onConnectionAuthenFailed(context: CarLifeContext) {

        connectionChangeListeners.forEach {
            it.onConnectionAuthenFailed(this)
        }
    }
}
