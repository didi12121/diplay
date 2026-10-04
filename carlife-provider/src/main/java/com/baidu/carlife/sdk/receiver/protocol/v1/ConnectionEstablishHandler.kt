package com.baidu.carlife.sdk.receiver.protocol.v1

import android.os.Build
import android.text.TextUtils
import com.baidu.carlife.protobuf.*
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_HU_INFO
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_HU_PROTOCOL_VERSION
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_AUTH_RESULT
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_INFO
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_STATISTIC_INFO
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_START
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.PROTOCOL_VERSION_MATCH
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.PROTOCOL_VERSION_MINOR_VERSION
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.TIME_FORMAT_FOR_HMS
import com.baidu.carlife.sdk.util.TimerUtils
import com.baidu.carlife.sdk.util.getDateFormat
import com.baidu.encryption.EncryptionUtils
import java.util.*

class ConnectionEstablishHandler(private val context: CarLifeContext) : TransportListener {
    private val progressListeners = mutableSetOf<ConnectProgressListener>()

    private var currentProgress: Int = 0

    private var connectTime: String = ""

    @Volatile
    private var lastReceiveMessageTime = 0L

    private val heartBeatTask = Runnable {
        if (System.currentTimeMillis() - lastReceiveMessageTime > 8000) {
            Logger.e(Constants.TAG, "ConnectionEstablishHandler heartbeat timeout")
            context.terminate()
        } else {
            Logger.v(
                Constants.TAG,
                "ConnectionEstablishHandler message delay ${System.currentTimeMillis() - lastReceiveMessageTime}"
            )
            context.postMessage(MSG_CHANNEL_VIDEO, ServiceTypes.MSG_VIDEO_HEARTBEAT)
        }
    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        lastReceiveMessageTime = System.currentTimeMillis()
        when (message.serviceType) {
            MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS -> handleProtocolVersionMatch(context, message)
            MSG_CMD_MD_INFO -> handleMDInfo(context, message)
            ServiceTypes.MSG_CMD_MD_AUTH_RESPONSE -> handleAuthResponse(context, message)
            MSG_CMD_MD_AUTH_RESULT -> handleAuthResult(context, message)
        }
        return false
    }

    override fun onSendMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_VIDEO_ENCODER_START -> {
                // 发送MSG_CMD_VIDEO_ENCODER_START之后，认为当前进度是100
                onProgress(Constants.VALUE_PROGRESS_100)
            }
        }
        return false
    }

    override fun onConnectionAttached(context: CarLifeContext) {
        // 清空加解密标记
        context.isEncryptionEnabled = false
        // 连接建立之后，发送MSG_CMD_HU_PROTOCOL_VERSION，第一条消息
        val message = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_CMD_HU_PROTOCOL_VERSION)
        message.payload(
            CarlifeProtocolVersionProto.CarlifeProtocolVersion.newBuilder()
                .setMajorVersion(context.protocolVersion)
                .setMinorVersion(PROTOCOL_VERSION_MINOR_VERSION)
                .build()
        )
        context.postMessage(message)
        onProgress(Constants.VALUE_PROGRESS_0)
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        // 清空加解密标记
        context.isEncryptionEnabled = false
        currentProgress = 0

        TimerUtils.stop(heartBeatTask)
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        onProgress(Constants.VALUE_PROGRESS_70)
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        currentProgress = 0

        TimerUtils.stop(heartBeatTask)
    }

    private fun handleProtocolVersionMatch(context: CarLifeContext, message: CarLifeMessage) {
        val versionStatus = message.protoPayload as?
                CarlifeProtocolVersionMatchStatusProto.CarlifeProtocolVersionMatchStatus

        var statisticsInfo = context.statisticsInfo

        if (versionStatus != null) {
            context.carlifeVersion = versionStatus.carlifeProtocolVersion
        }

        if (versionStatus?.matchStatus == PROTOCOL_VERSION_MATCH && statisticsInfo != null) {
            val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_CMD_STATISTIC_INFO)
            response.payload(statisticsInfo)
            context.postMessage(response)

            onProgress(Constants.VALUE_PROGRESS_30)
            // 收到第一条回复之后再开启心跳计时
            TimerUtils.schedule(heartBeatTask, 10, 1000)
        } else {
            // 版本不匹配时，设置为false.如果连接再次建立成功后，再还原为true
            context.isVersionSupport = false
            context.onConnectionVersionNotSupprt()
        }
    }

    private fun handleMDInfo(context: CarLifeContext, message: CarLifeMessage) {
        // 存储MD info
        context.deviceInfo = message.protoPayload as? CarlifeDeviceInfoProto.CarlifeDeviceInfo

        // send HU info
        sendHuInfo(context)
        // 发起鉴权请求
        sendAuthRequest(context)
    }

    private fun sendHuInfo(context: CarLifeContext) {
        val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_CMD_HU_INFO)
        response.payload(
            CarlifeDeviceInfoProto.CarlifeDeviceInfo.newBuilder()
                .setOs(context.osType)
                .setBoard(Build.BOARD)
                .setBootloader(Build.BOOTLOADER)
                .setBrand(Build.BRAND)
                .setCpuAbi(Build.CPU_ABI)
                .setCpuAbi2(Build.CPU_ABI2)
                .setDevice(Build.DEVICE)
                .setDisplay(Build.DISPLAY)
                .setFingerprint(Build.FINGERPRINT)
                .setHardware(Build.HARDWARE)
                .setHost(Build.HOST)
                .setCid(Build.ID)
                .setManufacturer(Build.MANUFACTURER)
                .setModel(Build.MODEL)
                .setProduct(Build.PRODUCT)
                .setSerial(Build.SERIAL)
                .setCodename(Build.VERSION.CODENAME)
                .setIncremental(Build.VERSION.INCREMENTAL)
                .setSdk(Build.VERSION.SDK)
                .setSdkInt(Build.VERSION.SDK_INT)
                .setRelease(Build.VERSION.RELEASE)
                .setCarlifeversion(context.versionName)
                .build()
        )
        context.postMessage(response)
    }

    private fun sendAuthRequest(context: CarLifeContext) {
        Logger.d(Constants.TAG, "AuthenticationStep1 获取MD信息，开启鉴权")
        val responseAuthReq = CarLifeMessage.obtain(MSG_CHANNEL_CMD,
            ServiceTypes.MSG_CMD_HU_AUTH_REQUEST
        )
        connectTime = Date().getDateFormat(System.currentTimeMillis(),TIME_FORMAT_FOR_HMS).toString()
        // 版本+鉴权时间 以;隔开 手机端解析：版本为2.0与连接鉴权时间
        val randomValue = Constants.SDK_VERSION_CODE + ";" + connectTime
        Logger.d(Constants.TAG, "AuthenticationStep1.1 randomValue = $randomValue")
        responseAuthReq.payload(
            CarlifeAuthenRequestProto.CarlifeAuthenRequest.newBuilder()
                .setRandomValue(randomValue)
                .build()
        )
        context.postMessage(responseAuthReq)
        // 开启计时任务，超30s response未返回也需要断开
        TimerUtils.schedule(mAuthenticationTimeTask, 30000)
    }

    private val mAuthenticationTimeTask = Runnable {
        Logger.w(Constants.TAG, "Carlife Connect authentication failed.")
        // 车机主动断开
        context.terminate()
    }

    private fun handleAuthResponse(context: CarLifeContext, message: CarLifeMessage) {
        Logger.d(Constants.TAG,"AuthenticationStep2 获取移动端校验码")
        TimerUtils.stop(mAuthenticationTimeTask)
        var encryptionInfo = false
        val authResult = CarLifeMessage.obtain(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_HU_AUTH_RESULT)
        val authResponse = message.protoPayload as? CarlifeAuthenResponseProto.CarlifeAuthenResponse
        if (authResponse != null && !TextUtils.isEmpty(authResponse.encryptValue)) {
            Logger.d(Constants.TAG,"AuthenticationStep2.1 authenticationInfo = " + authResponse.encryptValue)
            val deviceInfo = context.deviceInfo
            var mdInfoAuthen = ""
            if (deviceInfo != null) {
                mdInfoAuthen = deviceInfo.brand + deviceInfo.model + deviceInfo.sdk + connectTime
            }
            Logger.d(Constants.TAG, "AuthenticationStep2.2 mdInfoAuthen = $mdInfoAuthen")
            // so鉴权处理
            encryptionInfo = EncryptionUtils.getVerifyResult(mdInfoAuthen,authResponse.encryptValue)
            Logger.d(Constants.TAG, "AuthenticationStep2.3 encryptionInfo = $encryptionInfo")
        }
        authResult.payload(
            CarlifeAuthenResultProto.CarlifeAuthenResult.newBuilder()
                .setAuthenResult(encryptionInfo)
                .build()
        )
        context.postMessage(authResult)

        // 如果校验不过，5秒后主动断开
        if(!encryptionInfo) {
            TimerUtils.schedule(mAuthenticationTimeTask, 5000)
        }
    }

    private fun handleAuthResult(context: CarLifeContext, message: CarLifeMessage) {
        context.postMessage(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_MD_AUTH_RESULT_RESPONSE)

        val authResult = message.protoPayload as? CarlifeAuthenResultProto.CarlifeAuthenResult
        if (authResult != null && authResult.authenResult) {
            context.onConnectionEstablished()
        } else {
            context.onConnectionAuthenFailed()
        }
    }

    // connect progress related
    @Synchronized
    fun addConnectProgressListener(listener: ConnectProgressListener) {
        // 如果当前处于连接中，直接回调状态
        if (context.connectionState == CarLifeContext.CONNECTION_ATTACHED ||
            context.connectionState == CarLifeContext.CONNECTION_REATTACH
        ) {
            listener.onProgress(currentProgress)
        }
        progressListeners.add(listener)
    }

    @Synchronized
    fun removeConnectProgressListener(listener: ConnectProgressListener) {
        progressListeners.remove(listener)
    }

    @Synchronized
    fun onProgress(progress: Int) {
        currentProgress = progress
        progressListeners.forEach { it.onProgress(progress) }
    }
}