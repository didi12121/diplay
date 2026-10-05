package com.baidu.carlife.sdk.receiver.transport.instant

import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import com.baidu.carlife.protobuf.*
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.*
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_INFO_REQUEST
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_INFO_RESPONSE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_MD_STATUS
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_REQUEST_IP
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_RESPONSE_IP
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_TARGET_INFO_REQUEST
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_WIRELESS_TARGET_INFO_RESPONSE
import com.baidu.carlife.sdk.internal.transport.communicator.BluetoothCommunicator
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class InstantConnectionSetup(private val context: CarLifeContext,
                             private val callback: Callback
): BluetoothDeviceDiscover.Callback, WifiDirectManager.Callbacks {
    interface Callback {
        fun onDeviceConnected(address: String)
    }

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_WIFI = 1
        const val TYPE_WIFI_DIRECT = 2
        const val TYPE_ALL = 3

        const val FREQUENCY_2_4G = 0
        const val FREQUENCY_5G   = 1

        const val TYPE_BT_AUDIO_NONE = 0
        const val TYPE_BT_AUDIO = 1
    }

    private val wifiDirectManager = WifiDirectManager(context, this)

    private val wifiDirectName: String? = context.getConfig(CONFIG_WIFI_DIRECT_NAME)
    private val isProcessing = AtomicBoolean(false)

    private var communicator: BluetoothCommunicator? = null

    private val bluetoothDeviceDiscover by lazy { BluetoothDeviceDiscover(context, this) }

    fun connect() {
        bluetoothDeviceDiscover.startDiscover()
    }

    // BluetoothDeviceDiscover.Callback
    override fun onDeviceConnected(communicator: BluetoothCommunicator) {
        thread { process(communicator) }
    }

    // WifiDirectManager.Callbacks
    override fun onDeviceConnected(info: WifiP2pInfo) {
        // 此时应该向手机端请求IP地址
        communicator?.let { requestIp(it) }
    }

    fun terminate() {
        bluetoothDeviceDiscover.terminate()
        wifiDirectManager.terminate()
    }

    fun process(communicator: BluetoothCommunicator) {
        if (isProcessing.getAndSet(true)) {
            // 判断如果已经启动了，直接返回，应该是不会跑到这里的
            Logger.e(Constants.TAG, "InstantConnectionSetup multiple bluetooth socket!!!")
            return
        }

        this.communicator = communicator
        while (true) {
            var message: CarLifeMessage? = null
            try {
                message = communicator.read()
                dispatchMessage(communicator, message)
            }
            catch (e: Exception) {
                Logger.e(Constants.TAG, "InstantConnectionSetup process exception: " + Log.getStackTraceString(e))
                break
            }
            finally {
                message?.recycle()
            }
        }

        isProcessing.set(false)
        this.communicator = null
    }

    private fun dispatchMessage(communicator: BluetoothCommunicator, message: CarLifeMessage) {
        when (message.serviceType) {
            MSG_WIRELESS_INFO_REQUEST -> handleWirelessInfoRequest(communicator, message)
            MSG_WIRELESS_TARGET_INFO_REQUEST -> handleTargetInfoRequest(communicator, message)
            MSG_WIRELESS_RESPONSE_IP -> handleResponseIp(communicator, message)
            MSG_WIRELESS_MD_STATUS -> handleResponseWirlessStatus(communicator, message)
        }
    }

    private fun handleWirelessInfoRequest(communicator: BluetoothCommunicator, message: CarLifeMessage) {
        Logger.d(Constants.BLUETOOH_TAG, "handleWirelessInfoRequest: MSG_WIRELESS_INFO_RESPONSE")
        val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_WIRELESS_INFO_RESPONSE)

        response.payload(CarlifeWirlessInfoProto.CarlifeWirlessInfo.newBuilder()
            .setWirlessType(context.getConfig(CONFIG_WIRLESS_TYPE, TYPE_NONE))
            .setWifiFrequency(context.getConfig(CONFIG_WIRLESS_FREQUENCY, FREQUENCY_2_4G))
            .build())
        communicator.write(response)
    }

    private fun handleTargetInfoRequest(communicator: BluetoothCommunicator, message: CarLifeMessage) {
        Logger.d(Constants.BLUETOOH_TAG, "handleTargetInfoRequest: MSG_WIRELESS_TARGET_INFO_RESPONSE")
        wifiDirectName ?: return

        val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_WIRELESS_TARGET_INFO_RESPONSE)
        response.payload(CarlifeWirlessTargetProto.CarlifeWirlessTarget.newBuilder()
            .setWifiDeviceName(wifiDirectName)
            .setTargetInfo("")
            .build())
        communicator.write(response)

        if (wifiDirectManager.isConnected) {
            requestIp(communicator)
        }
        else {
            // 使当前设备可被发现
            wifiDirectManager.discoverable()
        }
    }

    private fun requestIp(communicator: BluetoothCommunicator) {
        try {
            communicator.write(CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_WIRELESS_REQUEST_IP))
        }
        catch (e: Exception) {
            Logger.e(Constants.TAG, "InstantConnectionSetup requestIp exception :", e)
        }
    }

    private fun handleResponseIp(communicator: BluetoothCommunicator, message: CarLifeMessage) {
        val wirelessIp = message.protoPayload as? CarlifeWirlessIpProto.CarlifeWirlessIp
        Logger.d(Constants.BLUETOOH_TAG, "handleResponseIp is : " + wirelessIp?.wirlessip)
        if (wirelessIp != null && wirelessIp.wirlessip.isNotEmpty()) {
            callback.onDeviceConnected(wirelessIp.wirlessip)
        }
        else if (wifiDirectManager.isConnected){
            // 如果获取ip失败，一秒之后再次尝试请求
            context.postDelayed(1000) { requestIp(communicator) }
        }
    }

    private fun handleResponseWirlessStatus(communicator: BluetoothCommunicator, message: CarLifeMessage) {
        Logger.d(Constants.BLUETOOH_TAG, "handleResponseWirlessStatus")
        val wirelessInfo = message.protoPayload as CarlifeWirlessStatusProto.CarlifeWirlessStatus
        context.onDeviceWirlessStatus(wirelessInfo.status)
    }
}