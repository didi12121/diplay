package com.baidu.carlife.sdk.receiver.transport.aoa.async

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.Constants.AOA_MESSAGE_MAX_SIZE
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_UPDATE
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.receiver.transport.aoa.UsbAccessoryStarter
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.toInt
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbAccessoryCommunicatorAsync(private val context: CarLifeContext,
                                    private val usbDevice: UsbDevice):
    Communicator {
    private val usbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val usbRequestPool = UsbRequestPool()

    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null

    private val messageReader = MessageReader(usbRequestPool)
    private val messageSender = MessageSender(usbRequestPool)

    private val isTerminated = AtomicBoolean(false)

    fun openAccessory(): Boolean {
        try {
            usbInterface = usbDevice.getInterface(0)
            usbConnection = usbManager.openDevice(usbDevice)
            usbConnection!!.claimInterface(usbInterface, true)
            if (!UsbAccessoryStarter(usbDevice, usbConnection!!).openAccessory()) {
                // 切换AOA模式成功之后，USB会重新连接一次，以新的VID，PID重新连接
                // 返回true的条件必须是检测处于AOA mode才行
                terminate()
                return false
            }
            usbRequestPool.attach(usbConnection!!, usbInterface!!)
            return true
        }
        catch (e: Exception) {
            Logger.e(Constants.TAG, "openAccessory exception: ", e)
            terminate()
            return false
        }
    }

    override fun write(message: CarLifeMessage) {
        messageSender.send(message)
    }

    override fun read(): CarLifeMessage {
        messageReader.read()
        while (!isTerminated.get()) {
            val request = usbConnection!!.requestWait() ?: throw IOException("UsbDeviceConnection requestWait return null")
            if (usbRequestPool.isInput(request)) {
                messageReader.read(request)?.let {
                    return it
                }
            }
            else {
                messageSender.send(request)
            }
        }
        throw IOException("UsbAccessoryCommunicator read from terminated communicator")
    }

    override fun terminate() {
        if (!isTerminated.getAndSet(true)) {
            usbRequestPool.release()

            // 防止write一直阻塞，手动释放信号
            messageSender.complete()

            usbConnection?.releaseInterface(usbInterface)
            usbConnection?.close()
            usbConnection = null

            Logger.d(Constants.TAG, "UsbAccessoryCommunicator released")
        }
    }
}