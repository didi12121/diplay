package com.baidu.carlife.sdk.receiver.transport.instant

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.CONFIG_TARGET_BLUETOOTH_NAME
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.transport.communicator.BluetoothCommunicator
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.util.Logger
import java.io.IOException
import java.lang.Exception
import java.util.*

// 负责查找bluetooth device，并建立socket通道
class BluetoothDeviceDiscover(
    private val context: CarLifeContext,
    private val callback: Callback? = null
) : BroadcastReceiver(), Communicator.Callbacks {
    interface Callback {
        fun onDeviceConnected(communicator: BluetoothCommunicator)
    }

    private val bluetoothAdapter by lazy {
        (context.applicationContext
            .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter
    }

    private val targetName by lazy { context.getConfig<String>(CONFIG_TARGET_BLUETOOTH_NAME) }

    private var connectedCommunicator: BluetoothCommunicator? = null

    private var isReady = false

    private val filter by lazy {
        IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
    }

    private val discoverRunnable = object : Runnable {
        override fun run() {
            // 避免重复执行
            context.main().removeCallbacks(this)
            if (!isReady) return
            context.io().execute { discoverDevice() }
        }
    }

    fun startDiscover() {
        isReady = true
        context.applicationContext.registerReceiver(this, filter)
        context.main().post(discoverRunnable)
    }

    private fun discoverDevice() {
        if (bluetoothAdapter?.isEnabled == false) {
            // 未开启蓝牙，直接return
            Logger.e(Constants.TAG, "bluetoothAdapter?.isEnabled: ${bluetoothAdapter?.isEnabled}, so return")
            return
        }

        var connectedSocket: BluetoothSocket? = null
        if (targetName != null) {
            val targetDevice = bluetoothAdapter?.bondedDevices?.find { it.name == targetName }
            if (targetDevice != null) {
                try {
                    Logger.d(Constants.TAG, "start connect to targetDevice: ", targetDevice.name)
                    connectedSocket = connect(targetDevice)
                    Logger.d(Constants.TAG, "connected to ", targetDevice.name)
                } catch (e: IOException) {
                    Logger.e(Constants.TAG, "BluetoothDeviceDiscover connect device exception ", e)
                }

                if (connectedSocket != null) {
                    connectedCommunicator = BluetoothCommunicator(connectedSocket, this)
                    callback?.onDeviceConnected(connectedCommunicator!!)
                } else if (isReady){
                    // 如果存在已连接设备，则5s之后重新尝试连接
                    context.main().postDelayed(discoverRunnable, 2000)
                }
            }
        } else {
            val connectedDevices =
                bluetoothAdapter?.bondedDevices?.filter { isConnected(it) }.orEmpty()
            for (connectedDevice in connectedDevices) {
                try {
                    Logger.d(Constants.TAG, "start connect to bondedDevices: ", connectedDevice.name)
                    connectedSocket = connect(connectedDevice)
                    Logger.d(Constants.TAG, "connected to ", connectedDevice.name)
                    break
                } catch (e: IOException) {
                    Logger.e(Constants.TAG, "BluetoothDeviceDiscover connect device exception ", e)
                }
            }

            if (connectedSocket != null) {
                connectedCommunicator = BluetoothCommunicator(connectedSocket, this)
                callback?.onDeviceConnected(connectedCommunicator!!)
            } else if (connectedDevices.isNotEmpty() && isReady) {
                // 如果存在已连接设备，则5s之后重新尝试连接
                context.main().postDelayed(discoverRunnable, 5000)
            }
        }
    }

    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val socket = device.createRfcommSocketToServiceRecord(
            UUID.fromString(Constants.BLUETOOTH_COMMUNICATE_UUID)
        )
        socket.connect()
        return socket
    }

    fun terminate() {
        isReady = false
        connectedCommunicator?.terminate()
    }

    // Communicator.Callbacks
    override fun onTerminated(channel: Int) {
        // 如果连接断开了，应该重新尝试连接
        connectedCommunicator = null
        context.applicationContext.unregisterReceiver(this)
        context.main().removeCallbacks(discoverRunnable)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.STATE_OFF
                )
                if (state == BluetoothAdapter.STATE_ON && isReady) {
                    this.context.main().postDelayed(discoverRunnable, 1000)
                }
                Logger.d(Constants.TAG, "bluetoothReceiver ACTION_STATE_CHANGED ", state)
            }
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.STATE_DISCONNECTED
                )
                Logger.d(Constants.TAG, "bluetoothReceiver ACTION_CONNECTION_STATE_CHANGED ", state)
                if (state == BluetoothAdapter.STATE_CONNECTED && connectedCommunicator == null) {
                    // 当有设备连接上，并且之前没有连接成功的设备，发起发现流程
                    this.context.main().post(discoverRunnable)
                }
            }
        }
    }

    private fun isConnected(device: BluetoothDevice): Boolean {
        try {
            val isConnectedMethod = device.javaClass.getMethod("isConnected")
            return isConnectedMethod.invoke(device) as Boolean
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "BluetoothDeviceDiscover isConnected exception ", e)
            return true
        }
    }
}