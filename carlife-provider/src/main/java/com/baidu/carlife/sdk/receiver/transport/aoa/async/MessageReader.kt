package com.baidu.carlife.sdk.receiver.transport.aoa.async

import android.hardware.usb.UsbRequest
import android.os.Build
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.toInt
import java.io.IOException
import java.nio.ByteBuffer

class MessageReader(private val pool: UsbRequestPool) {
    private var messageSize = 0

    private val header = ByteBuffer.allocate(CarLifeMessage.HEADER_SIZE)

    private var readingMessage: CarLifeMessage? = null

    // android O 以下，UsbRequest queue不支持从ByteBuffer的分段写入，每次都会从开头开始写
    // 这样对于长度大于16K的数据，就会导致后边的数据会冲掉前面的数据，所以需要单独使用一个buffer进行读
    // 然后拷贝到CarLifeMessage的body中
    private var readSize = 0
    private val queuedBuffer = ByteBuffer.allocate(16 * 1024)

    fun read() {
        reset()

        // Logger.e(Constants.TAG, "MessageReader read header")
        val request = pool.acquireInRequest(this)
        header.rewind()
        if (!pool.queue(request, header, CarLifeMessage.HEADER_SIZE)) {
            pool.releaseRequest(request)
            throw IOException("MessageReader queue read header failed")
        }
    }

    fun read(request: UsbRequest): CarLifeMessage? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            readAfterApi26(request)
        }
        else {
            readBelowApi26(request)
        }
    }

    private fun readAfterApi26(request: UsbRequest): CarLifeMessage? {
        if (readingMessage == null) {
            if (header.position() == 0) {
                Logger.e(Constants.TAG, "MessageReader read zero header length")
                // 说明读到的长度为0，重新读取头部
                pool.releaseRequest(request)
                read()
                return null
            }
            // 表示读完了header
            val channel = header.array().toInt()
            messageSize = header.array().toInt(4)
            if (!verifyHeader(channel, messageSize)) {
                pool.releaseRequest(request)
                throw IOException("invalid message channel $channel or message size $messageSize")
            }
            // 读到消息头，继续读消息体
            val message = CarLifeMessage.obtain(channel, length = messageSize)
            message.wrappedBody.rewind()
            message.wrappedBody.limit(messageSize)

            if (!pool.queue(request, message.wrappedBody, messageSize)) {
                message.recycle()
                pool.releaseRequest(request)
                throw IOException("MessageReader queue read message failed")
            }

            readingMessage = message
            return null
        }

        val readSize = readingMessage!!.wrappedBody.position()
        val remaining = messageSize - readSize
        // 读到了一条完整的消息
        if (readSize == messageSize) {
            pool.releaseRequest(request)
            val message = readingMessage!!
            readingMessage = null
            return message
        }
        // 还没有读完，继续读
        if (!pool.queue(request, readingMessage!!.wrappedBody, remaining)) {
            reset()
            pool.releaseRequest(request)
            throw IOException("MessageReader queue read message failed")
        }
        return null
    }

    private fun readBelowApi26(request: UsbRequest): CarLifeMessage? {
        if (readingMessage == null) {
            if (header.position() == 0) {
                Logger.e(Constants.TAG, "MessageReader read zero header length")
                // 说明读到的长度为0，重新读取头部
                pool.releaseRequest(request)
                read()
                return null
            }
            // 表示读完了header
            val channel = header.array().toInt()
            messageSize = header.array().toInt(4)
            if (!verifyHeader(channel, messageSize)) {
                pool.releaseRequest(request)
                throw IOException("invalid message channel $channel or message size $messageSize")
            }
            // 读到消息头，继续读消息体
            val message = CarLifeMessage.obtain(channel, length = messageSize)
            // Logger.e(Constants.TAG, "MessageReader read body ${message.hashCode()} $messageSize")
            queuedBuffer.rewind()
            if (!pool.queue(request, queuedBuffer, queuedBuffer.limit())) {
                message.recycle()
                pool.releaseRequest(request)
                throw IOException("MessageReader queue read message failed")
            }

            readingMessage = message
            return null
        }

        // 拷贝已读数据到消息体中
        val bufferSize = queuedBuffer.position()
        queuedBuffer.rewind()
        queuedBuffer.get(readingMessage!!.body, readSize, bufferSize)

        readSize += bufferSize
        val remaining = messageSize - readSize

        // 读到了一条完整的消息
        if (readSize == messageSize) {
            // Logger.e(Constants.TAG, "MessageReader read complete ${readingMessage!!.hashCode()}")
            pool.releaseRequest(request)
            val message = readingMessage!!
            readingMessage = null
            return message
        }

        // 还没有读完，继续读
        // Logger.e(Constants.TAG, "MessageReader read partial $remaining $messageSize")
        queuedBuffer.rewind()
        if (!pool.queue(request, queuedBuffer, remaining.coerceAtMost(queuedBuffer.limit()))) {
            reset()
            pool.releaseRequest(request)
            throw IOException("MessageReader queue read message failed")
        }
        return null
    }

    private fun verifyHeader(channel: Int, size: Int): Boolean {
        return channel >= Constants.MSG_CHANNEL_CMD
                && channel <= Constants.MSG_CHANNEL_UPDATE
                && messageSize > 0
    }

    private fun reset() {
        readSize = 0
        messageSize = 0

        readingMessage?.let {
            it.recycle()
            readingMessage = null
        }
    }
}