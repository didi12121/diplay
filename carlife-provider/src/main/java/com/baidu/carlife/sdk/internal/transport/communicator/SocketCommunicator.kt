package com.baidu.carlife.sdk.internal.transport.communicator

import android.util.Log
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.blockRead
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("LeakingThis")
open class SocketCommunicator(
    private val channel: Int,
    private val socket: Socket,
    private val callbacks: Communicator.Callbacks? = null,
    private val messageQueue: BlockingQueue<CarLifeMessage>? = null
) : Communicator {
    companion object {
        const val MAX_PACKET_SIZE = 10 * 1024
    }

    constructor(
        channel: Int,
        host: String,
        port: Int,
        callbacks: Communicator.Callbacks? = null,
        messageQueue: BlockingQueue<CarLifeMessage>? = null
    ) : this(channel, Socket(host, port), callbacks, messageQueue)

    private var isTerminated = AtomicBoolean(false)

    private var reader: Reader? = null

    protected val input: InputStream = socket.getInputStream()
    protected val output: OutputStream = socket.getOutputStream()

    init {
        messageQueue?.let {
            reader = Reader(channel, it, this)
        }
    }

    override fun write(message: CarLifeMessage) {
        if (isTerminated.get()) {
            throw IOException("socket has been closed")
        }

        try {
            val messageSize = message.size
            var remaining = messageSize
            while (remaining > 0) {
                val packetSize = if (remaining > MAX_PACKET_SIZE) {
                    MAX_PACKET_SIZE
                } else {
                    remaining
                }
                output.write(message.body, messageSize - remaining, packetSize)
                remaining -= packetSize
            }
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "socket $channel write exception: ", e)
            throw e
        }
    }

    override fun read(): CarLifeMessage {
        if (isTerminated.get()) {
            throw IOException("socket has been closed")
        }

        val message = CarLifeMessage.obtain(channel)
        try {
            input.blockRead(message.body, 0, message.commandSize)
            message.resizeBuffer(message.size)
            input.blockRead(message.body, message.commandSize, message.payloadSize)
            return message
        } catch (e: Exception) {
            // 如果出现异常，需要自己回收，并同时把异常重新抛出
            message.recycle()
            Logger.e(Constants.TAG, "socket $channel read exception: ", e)
            throw e
        }
    }

    override fun terminate() {
        if (!isTerminated.getAndSet(true)) {
            try {
                socket.shutdownInput()
                socket.shutdownOutput()
                socket.close()
            } catch (e: Exception) {
                Logger.d(Constants.TAG, "Communicator ", channel, " terminate exception: ", e)
            }
            // 触发read线程退出
            messageQueue?.put(CarLifeMessage.NULL)

            reader?.interrupt()
            callbacks?.onTerminated(channel)
            Logger.d(Constants.TAG, "Communicator ", channel, " terminated ", this)
        }
    }

    private class Reader(
        private val channel: Int,
        private val messageQueue: BlockingQueue<CarLifeMessage>,
        private val communicator: Communicator
    ) : Thread() {
        init {
            name = "SocketReader_$channel"
            start()
        }

        override fun run() {
            while (true) {
                try {
                    val message = communicator.read()
                    // 将完整的消息写入到缓存buffer
                    messageQueue.put(message)
                } catch (e: Exception) {
                    // 如果断连了，放入一个channel为-1对象，使上面的read方法可以退出
                    Logger.e(
                        Constants.TAG,
                        "SocketReader_$channel read thread exception: " + Log.getStackTraceString(e)
                    )
                    if (!interrupted()) {
                        // 如果不做判断会导致抛出InterruptedException
                        messageQueue.clear()
                        messageQueue.put(CarLifeMessage.NULL)
                    }
                    break
                }
            }
        }
    }
}