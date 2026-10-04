package com.baidu.carlife.sdk.receiver.transport.aoa.async

import android.hardware.usb.UsbRequest
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.Logger
import java.io.IOException
import java.util.concurrent.Semaphore

class MessageSender(private val pool: UsbRequestPool) {
    companion object {
        private const val SENDING_HEADER = 0
        private const val SENDING_BODY = 1
        private const val SENDING_COMPLETE = 2
    }
    private val semaphore = Semaphore(0)

    @Volatile
    private var stage = SENDING_COMPLETE
    @Volatile
    private var sendingMessage: CarLifeMessage? = null

    fun send(message: CarLifeMessage) {
        sendingMessage = message
        stage = SENDING_HEADER
        val request = pool.acquireOutRequest(this)
        // fill header then send
        message.header(true)
        message.wrappedHeader.rewind()
        if (!pool.queue(request, message.wrappedHeader, CarLifeMessage.HEADER_SIZE)) {
            pool.releaseRequest(request)
            throw IOException("MessageReader queue send header failed")
        }
        semaphore.acquire()
    }

    fun send(request: UsbRequest) {
        if (stage == SENDING_HEADER) {
            if (sendingMessage!!.wrappedHeader.position() == 0) {
                // header 发送失败了，重新发送
                Logger.e(Constants.TAG, "MessageSender send zero header length")
                if (!pool.queue(request, sendingMessage!!.wrappedHeader, CarLifeMessage.HEADER_SIZE)) {
                    pool.releaseRequest(request)
                    throw IOException("MessageReader queue send header failed")
                }
            }
            else {
                stage = SENDING_BODY
                sendingMessage!!.wrappedBody.rewind()
                sendingMessage!!.wrappedBody.limit(sendingMessage!!.size)
                if (!pool.queue(request, sendingMessage!!.wrappedBody, sendingMessage!!.size)) {
                    pool.releaseRequest(request)
                    throw IOException("MessageReader queue send body failed")
                }
            }
        }
        else {
            if (sendingMessage!!.wrappedBody.position() == 0) {
                Logger.e(Constants.TAG, "MessageSender send zero body length")
                if (!pool.queue(request, sendingMessage!!.wrappedBody, sendingMessage!!.size)) {
                    pool.releaseRequest(request)
                    throw IOException("MessageReader queue send body failed")
                }
            }
            else {
                stage = SENDING_COMPLETE
                pool.releaseRequest(request)
                semaphore.release()
            }
        }
    }

    fun complete() {
        if (stage != SENDING_COMPLETE) {
            semaphore.release()
        }
    }
}