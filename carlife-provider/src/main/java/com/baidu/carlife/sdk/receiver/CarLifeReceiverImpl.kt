package com.baidu.carlife.sdk.receiver

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import com.baidu.carlife.protobuf.CarlifeStatisticsInfoProto.CarlifeStatisticsInfo
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.*
import com.baidu.carlife.sdk.internal.CarLifeContextImpl
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ProtocolTracer
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.PROTOCOL_VERSION_MAJOR_VERSION_4
import com.baidu.carlife.sdk.internal.protocol.encrypt.EncryptionTool
import com.baidu.carlife.sdk.receiver.display.RemoteDisplayRenderer
import com.baidu.carlife.sdk.receiver.transport.GroupedProtocolTransport
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.internal.RsaEncryptor
import com.baidu.carlife.sdk.receiver.protocol.v1.*
import com.baidu.carlife.sdk.receiver.touch.RemoteControlManager
import com.baidu.carlife.sdk.internal.DisplaySpec
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom

class CarLifeReceiverImpl(
    context: Context,
    channel: String,
    cuid: String,
    private val features: MutableMap<String, Int>,
    override val activityClass: Class<out Activity>,
    configs: Map<String, Any>
) : CarLifeContextImpl(context, configs), CarLifeReceiver, FileTransferListener {
    private var publicKey: PublicKey? = null
    private var encryptionTool: EncryptionTool? = null

    private val transport: GroupedProtocolTransport

    private var fileTransferListener: FileTransferListener? = null
    private val onVideoSizeChangedListeners: MutableSet<OnVideoSizeChangedListener> = mutableSetOf()

    private val establishHandler: ConnectionEstablishHandler

    private val renderer: RemoteDisplayRenderer

    private val controlManager: RemoteControlManager

    init {
        protocolVersion = getConfig(CONFIG_PROTOCOL_VERSION, PROTOCOL_VERSION_MAJOR_VERSION_4)
        statisticsInfo = CarlifeStatisticsInfo.newBuilder()
            .setChannel(channel)
            .setCuid(cuid)
            .setVersionCode(versionCode)
            .setVersionName(versionName)
            .setConnectCount(0)
            .setConnectSuccessCount(0)
            .setConnectTime(0)
            .build()

        if (getConfig(CONFIG_CONTENT_ENCRYPTION, false)) {
            initEncryption()
        }

        setFeatures(features)

        transport = GroupedProtocolTransport(this)

        renderer = RemoteDisplayRenderer(this, OnVideoSizeChangedListener { width, height ->
            notifyVideoSizeChanged(width, height)
        })

        controlManager = RemoteControlManager(this)
        addOnVideoSizeChangedListener(controlManager)

        registerTransportListener(ProtocolTracer(true))
        establishHandler = ConnectionEstablishHandler(this)
        registerTransportListener(establishHandler)
        registerTransportListener(renderer)
        registerTransportListener(FeaturesHandler(encryptionTool, publicKey))
        registerTransportListener(ModuleStatusHandler())
        registerTransportListener(FileTransferHandler(this, this))

        registerTransportListener(DualSubscribeHandler())
    }

    override fun onConnectionDetached() {
        super<CarLifeContextImpl>.onConnectionDetached()
        // 连接断开之后，重置为初始值
        setFeatures(features)
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        super<CarLifeContextImpl>.onConnectionReattached(context)
        // 连接断开之后，重置为初始值
        setFeatures(features)
    }

    override fun setFeature(key: String, feature: Int) {
        setFeature(key, feature, true)
    }

    override fun setFeature(key: String, feature: Int, isLocal: Boolean) {
        super.setFeature(key, feature, isLocal)
        if (isLocal) {
            features[key] = feature
        }
    }

    override fun addConnectProgressListener(listener: ConnectProgressListener) {
        establishHandler.addConnectProgressListener(listener)
    }

    override fun removeConnectProgressListener(listener: ConnectProgressListener) {
        establishHandler.removeConnectProgressListener(listener)
    }

    @Synchronized
    private fun notifyVideoSizeChanged(width: Int, height: Int) {
        onVideoSizeChangedListeners.forEach { it.onVideoSizeChanged(width, height) }
    }

    @Synchronized
    override fun addOnVideoSizeChangedListener(listener: OnVideoSizeChangedListener) {
        renderer.encoderInfo?.let {
            listener.onVideoSizeChanged(it.width, it.height)
        }
        onVideoSizeChangedListeners.add(listener)
    }

    @Synchronized
    override fun removeOnVideoSizeChangedListener(listener: OnVideoSizeChangedListener) {
        onVideoSizeChangedListeners.remove(listener)
    }

    override fun setFileTransferListener(listener: FileTransferListener?) {
        fileTransferListener = listener
    }

    override fun setDisplaySpec(displaySpec: DisplaySpec) {
        renderer.displaySpec = displaySpec
    }

    override fun getDisplaySpec() = renderer.displaySpec

    override fun setSurface(surface: Surface?) {
        renderer.setSurface(surface)
    }

    override fun setSurfaceRequestCallback(callback: SurfaceRequestCallback?) {
        renderer.surfaceRequester = callback
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        controlManager.onSurfaceSizeChanged(width, height)
    }

    override fun onTouchEvent(event: MotionEvent) {
        if (isConnected()) {
            controlManager.onTouchEvent(event)
        }
    }

    override fun onKeyEvent(keyCode: Int) {

    }

    /**
     *  车机端发起连接，当车机端初始化完成后调用
     */
    override fun connect() {
        // 开始连接
        transport.connect()
    }

    /**
     *  车机端发起断开连接，并且停止连接过程
     */
    override fun stopConnect() {
        transport.stopConnect()
    }

    /**
     *  车机端发起主动断开连接，断开后会发起重连
     */
    override fun disconnect() {
        terminate()
    }

    /**
     * 设置一种连接方式，并发起连接
     */
    override fun setConnectType(type: Int) {
        Logger.d(Constants.TAG, "setConnectType, $type")
        setFeature(FEATURE_CONFIG_CONNECT_TYPE, type)
        transport.configConnectType()
        transport.connect()
    }

    /**
     *  监听usb广播消息，被动接受连接。
     */
    override fun ready() {
        // 准备就绪
        transport.ready()
    }

    /**
     *  车机端发起主动断开连接，断开后会发起重连
     */
    override fun terminate() {
        // 主动断开连接
        transport.terminate()
    }

    /**
     *  往carlife app 异步发送消息
     */
    override fun postMessage(message: CarLifeMessage) {
        transport.postMessage(message)
    }

    /**
     *  往carlife app 同步发送消息
     */
    override fun sendMessage(message: CarLifeMessage) {
        transport.sendMessage(message)
    }

    /**
     *  CarLifeMessage 消息加密
     */
    override fun encrypt(message: CarLifeMessage): CarLifeMessage {
        return encryptionTool?.encrypt(message) ?: message
    }

    /**
     *  CarLifeMessage 消息解密
     */
    override fun decrypt(message: CarLifeMessage): CarLifeMessage {
        return encryptionTool?.decrypt(message) ?: message
    }

    private fun initEncryption() {
        try {
            // init rsa keys
            val keygen = KeyPairGenerator.getInstance("RSA")
            keygen.initialize(2048, SecureRandom())
            keygen.genKeyPair().let {
                publicKey = it.public
                encryptionTool = EncryptionTool(RsaEncryptor.withPrivateKey(it.private))
            }
            setFeature(FEATURE_CONFIG_CONTENT_ENCRYPTION, 1)
        } catch (e: Exception) {
            setFeature(FEATURE_CONFIG_CONTENT_ENCRYPTION, 0)
            Logger.e(Constants.TAG, "CarLifeReceiverImpl initEncryption exception: ", e)
        }
    }

    override fun initStatisticsInfo(channel: String, cuid: String) {
        statisticsInfo = CarlifeStatisticsInfo.newBuilder()
            .setChannel(channel)
            .setCuid(cuid)
            .setVersionCode(versionCode)
            .setVersionName(versionName)
            .setConnectCount(0)
            .setConnectSuccessCount(0)
            .setConnectTime(0)
            .build()
    }

    override fun onReceiveFile(file: File) {
        fileTransferListener?.onReceiveFile(file)
    }

    override fun onActivityStarted() {
        renderer.onActivityStarted()
    }

    override fun onActivityStopped() {
        renderer.onActivityStopped()
    }
}