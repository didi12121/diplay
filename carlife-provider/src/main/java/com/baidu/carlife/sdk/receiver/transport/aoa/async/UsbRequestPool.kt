package com.baidu.carlife.sdk.receiver.transport.aoa.async

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class UsbRequestPool {
    companion object {
        const val BULK_ENDPOINT_IN_ADDRESS = 0
        const val BULK_ENDPOINT_OUT_ADDRESS = 1

        private val createRequestCount = AtomicInteger(0)
    }

    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null

    // dir in request cache
    private val inRequestCache = LinkedList<UsbRequest>()
    // dir out request cache
    private val outRequestCache = LinkedList<UsbRequest>()
    // requested UsbRequest objects
    private val recordingRequests = mutableSetOf<UsbRequest>()

    fun attach(connection: UsbDeviceConnection, interfaze: UsbInterface) {
        if (usbConnection != null || usbInterface != null) {
            throw IllegalStateException("can not attach to a pool which already attached")
        }

        usbConnection = connection
        usbInterface = interfaze
    }

    fun queue(request: UsbRequest, buffer: ByteArray, offset: Int, length: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request.queue(ByteBuffer.wrap(buffer, offset, length))
        } else {
            request.queue(ByteBuffer.wrap(buffer, offset, length), length)
        }
    }

    fun queue(request: UsbRequest, buffer: ByteBuffer, length: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request.queue(buffer)
        } else {
            request.queue(buffer, length)
        }
    }

    @Synchronized
    fun acquireInRequest(clientData: Any? = null): UsbRequest {
        checkUsbState()
        // return from pool first
        inRequestCache.poll()?.let {
            it.clientData = clientData
            return record(it)
        }

        return record(UsbRequest().apply {
            initialize(usbConnection, usbInterface!!.getEndpoint(BULK_ENDPOINT_IN_ADDRESS))
            this.clientData = clientData
            createRequestCount.incrementAndGet()
            Logger.d(Constants.TAG, "UsbRequestPool create new in request ", this)
        })
    }

    @Synchronized
    fun acquireOutRequest(clientData: Any? = null): UsbRequest {
        checkUsbState()
        // return from pool first
        outRequestCache.poll()?.let {
            it.clientData = clientData
            return record(it)
        }

        return record(UsbRequest().apply {
            initialize(usbConnection, usbInterface!!.getEndpoint(BULK_ENDPOINT_OUT_ADDRESS))
            this.clientData = clientData
            createRequestCount.incrementAndGet()
            Logger.d(Constants.TAG, "UsbRequestPool create new out request ", this)
        })
    }

    @Synchronized
    fun releaseRequest(request: UsbRequest) {
        if (usbInterface == null) {
            request.close()
            return
        }

        recordingRequests.remove(request)

        if (isInput(request)) {
            inRequestCache.offer(request)
        } else {
            outRequestCache.offer(request)
        }
    }

    fun isInput(request: UsbRequest): Boolean {
        return request.endpoint.direction == UsbConstants.USB_DIR_IN
    }

    @Synchronized
    fun release() {
        usbConnection = null
        usbInterface = null

        val leakRequestCount = createRequestCount.get() -
                (inRequestCache.size + outRequestCache.size + recordingRequests.size)
        createRequestCount.set(0)
        Logger.d(Constants.TAG, "UsbRequestPool requests leak ", leakRequestCount)

        inRequestCache.forEach { it.close() }
        inRequestCache.clear()

        outRequestCache.forEach { it.close() }
        outRequestCache.clear()

        recordingRequests.forEach { it.close() }
        recordingRequests.clear()
    }

    private fun record(request: UsbRequest): UsbRequest {
        recordingRequests.add(request)
        return request
    }

    private fun checkUsbState() {
        if (usbConnection == null || usbInterface == null) {
            throw IllegalStateException("access before attached or after release")
        }
    }
}