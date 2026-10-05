package com.baidu.carlife.sdk.internal.protocol

import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_TOUCH
import com.baidu.carlife.sdk.internal.protocol.decoder.PayloadDecoderFactory
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import com.baidu.carlife.sdk.util.fillByteArray
import com.baidu.carlife.sdk.util.toInt
import com.baidu.carlife.sdk.util.toShort
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 消息体定义
 * MSG_CHANNEL_CMD MSG_CHANNEL_TOUCH
 * 消息体长度    保留     Service Type           消息体
 * B1 B2        B3 B4   B5 B6 B7 B8           xxxxx
 *
 * Others
 * 消息体长度     时间戳         Service Type       消息体
 * B1 B2 B3 B4   B5 B6 B7 B8   B9 B10 B11 B12   xxxxxxx
 */
@DoNotStrip
class CarLifeMessage internal constructor(var channel: Int = 0) : Closeable {
    companion object {
        const val HEADER_SIZE = 8

        // initial buffer size 1K
        const val INITIAL_BUFFER_SIZE = 1024

        // MSG_CHANNEL_CMD, MSG_CHANNEL_TOUCH 头信息有8个字节，其他的12个字节
        const val SHORT_COMMAND_SIZE = 8

        const val LONG_COMMAND_SIZE = 12

        private val pool = CarLifeMessagePool()

        @JvmStatic
        fun obtain(
            channel: Int = MSG_CHANNEL_CMD,
            serviceType: Int? = null,
            length: Int = 0
        ): CarLifeMessage {
            return pool.obtain(channel, serviceType, length).apply {
                acquire()
            }
        }

        @JvmStatic
        fun recycle(message: CarLifeMessage) {
            message.resetBuffer()
            pool.recycle(message)
        }

        // for special usage, for example return for read nothing
        val NULL = CarLifeMessage()
    }

    // 引用计数，记录当前引用数，如果引用为0，才能被recycle
    private val refCount = AtomicInteger(0)

    // debug使用，不应用于任何业务逻辑
    var tag: Long = 0

    // AOA 通道会用到，Socket的不需要，因为本身就是6条通道
    // 只有单条通道需要此header去分发
    private val header = ByteArray(HEADER_SIZE)
    val wrappedHeader = ByteBuffer.wrap(header)

    fun header(fill: Boolean = true): ByteArray {
        if (fill) {
            channel.fillByteArray(header)
            size.fillByteArray(header, 4)
        }
        return header
    }

    var body = ByteArray(INITIAL_BUFFER_SIZE)
        private set(value) {
            field = value
            wrappedBody = ByteBuffer.wrap(value)
        }

    var wrappedBody = ByteBuffer.wrap(body)
        private set

    /**
     * 消息对象，对于Cmd通道消息，通过其获取额外参数
     */
    var protoPayload: MessageLite? = null
        private set
        get() {
            if (field == null) {
                field = decode()
            }
            return field
        }

    /**
     * 消息数据，主要针对语音、视频消息，获取原始数据
     */
    val payload: ByteBuffer
        get() {
            return ByteBuffer.wrap(body, commandSize, payloadSize)
        }

    /**
     * 获取消息整体长度，包含header和payload
     */
    val size: Int get() = commandSize + payloadSize

    /**
     * 消息头长度
     * Cmd和Touch通道内消息头长度为8字节
     * 其他的为12字节
     */
    val commandSize: Int
        get() = when (channel) {
            MSG_CHANNEL_CMD, MSG_CHANNEL_TOUCH -> SHORT_COMMAND_SIZE
            else -> LONG_COMMAND_SIZE
        }

    /**
     * 消息体长度
     * Cmd和Touch通道内消息头两个字节为消息体长度
     * 其他的为头四个字节
     */
    var payloadSize: Int
        get() = when (channel) {
            // 对于SHORT_COMMAND_SIZE的通道，头两个字节是消息体长度，其他的是头四个字节
            MSG_CHANNEL_CMD, MSG_CHANNEL_TOUCH -> body.toShort()
            else -> body.toInt()
        }
        set(value) {
            when (channel) {
                MSG_CHANNEL_CMD, MSG_CHANNEL_TOUCH -> value.fillByteArray(body, byteSize = 2)
                else -> value.fillByteArray(body)
            }
            resizeBuffer(size)
        }

    /**
     * 消息类型
     * Cmd和Touch通道内消息，第四个字节起，长度为四个字节
     * 其他的第八个字节起，长度为四个字节
     */
    var serviceType: Int
        set(value) = when (channel) {
            MSG_CHANNEL_CMD, MSG_CHANNEL_TOUCH -> {
                value.fillByteArray(body, 4)
            }
            else -> value.fillByteArray(body, 8)
        }
        get() = when (channel) {
            MSG_CHANNEL_CMD, MSG_CHANNEL_TOUCH -> body.toInt(4)
            else -> body.toInt(8)
        }

    /**
     * 返回此消息是否支持加解密
     * MSG_CMD_HU_PROTOCOL_VERSION永远不会加密
     */
    val supportEncrypt: Boolean
        get() {
            serviceType.let {
                return it != ServiceTypes.MSG_CMD_HU_PROTOCOL_VERSION
                        && payloadSize > 0
            }
        }

    /**
     * 时间戳，用在音视频消息体中，消息头中第四个字节开始
     */
    var timestamp: Long
        set(value) {
            if (channel != MSG_CHANNEL_CMD && channel != MSG_CHANNEL_TOUCH) {
                value.toInt().fillByteArray(body, 4)
            }
        }
        get() {
            if (channel != MSG_CHANNEL_CMD && channel != MSG_CHANNEL_TOUCH) {
                return body.toInt(4).toLong()
            }
            return 0
        }

    /**
     * 增加引用计数
     */
    fun acquire() {
        refCount.incrementAndGet()
    }

    /**
     * 减少引用计数，为0则回收
     */
    fun recycle() {
        if (refCount.get() == 0) {
            // 到这里说明，一个message被重复回收了，这样会导致一个message同时被多方使用，
            // 直接抛异常
            throw IllegalStateException("this message ${hashCode()} already recycled")
        }

        val count = refCount.decrementAndGet()
        if (count == 0) {
            recycle(this)
        }
    }

    /**
     * 设置消息体，对于MediaCodec，编码输出的为ByteBuffer，使用此方法即可
     */
    fun payload(data: ByteBuffer, size: Int) {
        payloadSize = size
        data.get(body, commandSize, size)
    }

    /**
     * 设置消息体，适用于消息体为原生byte数组类型
     */
    fun payload(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        payloadSize = length
        System.arraycopy(data, offset, body, commandSize, length)
    }

    /**
     * 设置消息体，适用于Cmd消息类型
     */
    fun payload(msg: MessageLite?) {
        protoPayload = msg
        if (msg == null) {
            payloadSize = 0
            return
        }

        payloadSize = msg.serializedSize
        msg.writeTo(
            CodedOutputStream.newInstance(body, commandSize, msg.serializedSize)
        )
    }

    /**
     * 重置消息，清空状态
     * @channel 消息通道
     * @length 消息的长度，对于AOA消息，在AOA头里有消息的长度
     */
    fun reset(channel: Int, length: Int = 0, data: ByteArray? = null) {
        this.channel = channel
        protoPayload = null
        payloadSize = 0
        resizeBuffer(length)
        if (data != null) {
            System.arraycopy(data, 0, body, 0, length)
        }
    }

    fun reset() {
        val channel = header.toInt()
        val length = header.toInt(4)
        reset(channel, length)
    }

    /**
     * 消息接收完毕之后，解析消息体数据
     * 在接收完一整个消息之后，调用一次
     */
    private fun decode(): MessageLite? {
        val size = payloadSize
        return if (size == 0) {
            null
        } else PayloadDecoderFactory.decoderOf(serviceType)?.let {
            it.decode(body, commandSize, size) as MessageLite
        }
    }

    /**
     * 重新调整buffer尺寸
     */
    fun resizeBuffer(newSize: Int) {
        if (body.size < newSize) {
            val newBuffer = ByteArray(newSize)
            System.arraycopy(body, 0, newBuffer, 0, commandSize)
            body = newBuffer
        }
    }

    /**
     * 重置buffer尺寸
     */
    fun resetBuffer() {
        body = ByteArray(INITIAL_BUFFER_SIZE)
    }

    /**
     * this function does not copy payload
     */
    fun copy(): CarLifeMessage {
        val newMessage = obtain(channel, serviceType)
        newMessage.timestamp = timestamp
        return newMessage
    }

    override fun toString(): String {
        return "channel: $channel serviceType: 0x000" +
                serviceType.toString(16) +
                " header size: $commandSize  payload size: $payloadSize" +
                if (protoPayload != null) " payload: $protoPayload" else ""
    }

    override fun close() {
        recycle()
    }
}