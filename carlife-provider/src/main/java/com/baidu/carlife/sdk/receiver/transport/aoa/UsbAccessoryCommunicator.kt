package com.baidu.carlife.sdk.receiver.transport.aoa

import android.content.Context
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.Constants.AOA_MESSAGE_MAX_SIZE
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_UPDATE
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.receiver.transport.aoa.async.UsbRequestPool
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.toInt
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbAccessoryCommunicator(
    private val context: CarLifeContext,
    private val usbDevice: UsbDevice
) :
    Communicator {
    private val usbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null

    private val isTerminated = AtomicBoolean(false)
    private val headerBuffer = ByteArray(CarLifeMessage.HEADER_SIZE)

    fun openAccessory(): Boolean {
        try {
            Logger.d(Constants.TAG, "UsbAccessoryCommunicator openAccessory")
            usbInterface = usbDevice.getInterface(0)
            usbConnection = usbManager.openDevice(usbDevice)
            usbConnection!!.claimInterface(usbInterface, true)
            if (!UsbAccessoryStarter(usbDevice, usbConnection!!).openAccessory()) {
                // 切换AOA模式成功之后，USB会重新连接一次，以新的VID，PID重新连接
                // 返回true的条件必须是检测处于AOA mode才行
                terminate(false)
                Logger.d(Constants.TAG, "UsbAccessoryCommunicator openAccessory switch to aoa")
                return false
            }
            return true
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "openAccessory exception: ", e)
            terminate()
            return false
        }
    }

    override fun write(message: CarLifeMessage) {
        Logger.v(Constants.TAG, "UsbAccessoryCommunicator write ")
        usbConnection?.let {
            val endpoint = usbInterface!!.getEndpoint(UsbRequestPool.BULK_ENDPOINT_OUT_ADDRESS)
            // 先发送消息头
            var transfered = 0
            while (transfered == 0) {
                transfered =
                    it.bulkTransfer(endpoint, message.header(), 0, CarLifeMessage.HEADER_SIZE, 0)
                if (transfered == 0) {
                    Logger.e(Constants.TAG, "bulkTransfer write zero header length")
                }
            }

            if (transfered < 0) {
                throw IOException("UsbAccessoryCommunicator bulkTransfer header write failed")
            }
            // 再发送消息体
            transfered = 0
            while (transfered == 0) {
                transfered = it.bulkTransfer(endpoint, message.body, 0, message.size, 0)
                if (transfered == 0) {
                    Logger.e(Constants.TAG, "bulkTransfer write zero body length")
                }
            }

            if (transfered < 0) {
                throw IOException("UsbAccessoryCommunicatorSync bulkTransfer body failed")
            }
            return
        }
        throw IOException("UsbAccessoryCommunicatorSync is terminated")
    }

    override fun read(): CarLifeMessage {
        usbConnection?.let {
            // 先读取8字节协议头
            val endpoint = usbInterface!!.getEndpoint(UsbRequestPool.BULK_ENDPOINT_IN_ADDRESS)
            var transfered = 0
            while (transfered == 0) {
                // 有时候bulkTransfer会返回0，此时应该继续读，一直等到读到数据为止
                transfered =
                    it.bulkTransfer(endpoint, headerBuffer, 0, CarLifeMessage.HEADER_SIZE, 0)
                if (transfered == 0) {
                    Logger.e(Constants.TAG, "bulkTransfer read zero header length")
                }
            }

            if (transfered < 0) {
                throw IOException("UsbAccessoryCommunicatorSync bulkTransfer header read failed")
            }

            val channel = headerBuffer.toInt()
            val messageSize = headerBuffer.toInt(4)
            if (channel < MSG_CHANNEL_CMD || channel > MSG_CHANNEL_UPDATE ||
                messageSize < 0 || messageSize > AOA_MESSAGE_MAX_SIZE
            ) {
                throw IOException("invalid message channel $channel or message size $messageSize")
            }

            val message = CarLifeMessage.obtain(channel, length = messageSize)
            var remaining = messageSize
            while (remaining > 0) {
                val offset = messageSize - remaining
                transfered = it.bulkTransfer(endpoint, message.body, offset, remaining, 0)

                if (transfered > 0) {
                    remaining -= transfered
                }

                if (transfered < 0) {
                    message.recycle()
                    throw IOException("UsbAccessoryCommunicator bulkTransfer header failed $messageSize $remaining $transfered")
                }
            }
            return message
        }
        throw IOException("UsbAccessoryCommunicator read from terminated communicator")
    }

    override fun terminate() {
        terminate(true)
    }

    fun terminate(resetUsbDevice: Boolean) {
        if (!isTerminated.getAndSet(true)) {
            Logger.d(Constants.TAG, "UsbAccessoryCommunicator terminate")
            usbConnection?.releaseInterface(usbInterface)
            if (resetUsbDevice) {
                resetUsbDevice()
            }
            usbConnection?.close()
            usbConnection = null
        }
    }

    private fun resetUsbDevice() {
        try {
            val resetDevice = UsbDeviceConnection::class.java.getMethod("resetDevice")
            val result = resetDevice.invoke(usbConnection) //调用借钱方法，得到返回值
            Logger.d(Constants.TAG, "USB Device resetDevice result: $result")
        } catch (e: Exception) {
            e.printStackTrace()
            Logger.e(Constants.TAG, "USB Device resetDevice error", e)
        }
    }
}