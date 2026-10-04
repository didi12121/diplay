package com.baidu.carlife.sdk.receiver.transport.aoa

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

class UsbAccessoryStarter(private val usbDevice: UsbDevice,
                          private val usbConnection: UsbDeviceConnection) {
    companion object {
        const val VID_ACCESSORY = 0x18D1

        const val PID_ACCESSORY_ONLY = 0x2D00
        const val PID_ACCESSORY_ADB = 0x2D01
        const val PID_ACCESSORY_AUDIO = 0x2D02
        const val PID_ACCESSORY_AUDIO_ADB = 0x2D03
        const val PID_ACCESSORY_AUDIO_BULK = 0x2D04
        const val PID_ACCESSORY_AUDIO_ADB_BULK = 0x2D05

        const val AOA_GET_PROTOCOL = 51
        const val AOA_SEND_IDENT = 52
        const val AOA_START_ACCESSORY = 53
        const val AOA_AUDIO_SUPPORT = 58

        const val AOA_MANUFACTURER = "Baidu"
        const val AOA_MODEL_NAME = "CarLife"
        const val AOA_DESCRIPTION = "Baidu CarLife"
        const val AOA_VERSION = "1.0.0"
        const val AOA_URI = "http://carlife.baidu.com/"
        const val AOA_SERIAL_NUMBER = "0720SerialNo."
    }
    /**
     * 判断当前usb device是否是accessory mode，如果不是进行切换
     * @return 只有当前usb device是accessory mode，返回true，其他情况返回false
     */
    fun openAccessory(): Boolean {
        if (isAccessory()) {
            return true
        }

        if (getProtocolVersion()) {
            if (sendIdentities()) {
                startAccessory()
            }
        }
        return false

    }

    private fun isAccessory(): Boolean {
        return usbDevice.vendorId == VID_ACCESSORY
                && usbDevice.productId >= PID_ACCESSORY_ONLY
                && usbDevice.productId <= PID_ACCESSORY_AUDIO_ADB_BULK
    }

    private fun getProtocolVersion(): Boolean {
        val buffer = ByteArray(2)
        return controlTransferIn(AOA_GET_PROTOCOL, 0, 0, buffer) >= 0
    }

    private fun sendIdentities(): Boolean {
        if (controlTransferOut(AOA_SEND_IDENT, 0, 0, AOA_MANUFACTURER.toByteArray()) < 0) {
            return false
        }

        if (controlTransferOut(AOA_SEND_IDENT, 0, 1, AOA_MODEL_NAME.toByteArray()) < 0) {
            return false
        }

        if (controlTransferOut(AOA_SEND_IDENT, 0, 2, AOA_DESCRIPTION.toByteArray()) < 0) {
            return false
        }

        if (controlTransferOut(AOA_SEND_IDENT, 0, 3, AOA_VERSION.toByteArray()) < 0) {
            return false
        }

        if (controlTransferOut(AOA_SEND_IDENT, 0, 4, AOA_URI.toByteArray()) < 0) {
            return false
        }

        if (controlTransferOut(AOA_SEND_IDENT, 0, 5, AOA_SERIAL_NUMBER.toByteArray()) < 0) {
            return false
        }

        return true
    }

    private fun startAccessory(): Boolean {
        return controlTransferOut(AOA_START_ACCESSORY, 0, 0) >= 0
    }

    private fun controlTransferIn(request: Int, value: Int, index: Int,
                                  buffer: ByteArray? = null,
                                  length: Int = buffer?.size ?: 0): Int {
        return usbConnection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            request,
            value,
            index,
            buffer,
            length,
            0)
    }

    private fun controlTransferOut(request: Int, value: Int, index: Int,
                                   buffer: ByteArray? = null,
                                   length: Int = buffer?.size ?: 0): Int {
        return usbConnection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            request,
            value,
            index,
            buffer,
            length,
            0)
    }
}