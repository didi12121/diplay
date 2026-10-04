package com.baidu.carlife.sdk.receiver.transport.wirless

import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_TTS
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_VR
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_TOUCH
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_UPDATE
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.internal.transport.communicator.SocketCommunicator
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class WirlessConnector : Communicator {
    companion object {
        private const val SERVER_CMD_SOCKET_PORT = 7240
        private const val SERVER_VIDEO_SOCKET_PORT = 8240
        private const val SERVER_AUDIO_SOCKET_PORT = 9240
        private const val SERVER_AUDIO_TTS_SOCKET_PORT = 9241
        private const val SERVER_AUDIO_VR_SOCKET_PORT = 9242
        private const val SERVER_TOUCH_SOCKET_PORT = 9340
        private const val SERVER_UPDATE_SOCKET_PORT = 9440

        private val SUPPORT_CHANNELS = arrayOf(
            Pair(MSG_CHANNEL_CMD, SERVER_CMD_SOCKET_PORT),
            Pair(MSG_CHANNEL_VIDEO, SERVER_VIDEO_SOCKET_PORT),
            Pair(MSG_CHANNEL_AUDIO, SERVER_AUDIO_SOCKET_PORT),
            Pair(MSG_CHANNEL_AUDIO_TTS, SERVER_AUDIO_TTS_SOCKET_PORT),
            Pair(MSG_CHANNEL_AUDIO_VR, SERVER_AUDIO_VR_SOCKET_PORT),
            Pair(MSG_CHANNEL_TOUCH, SERVER_TOUCH_SOCKET_PORT),
            Pair(MSG_CHANNEL_UPDATE, SERVER_UPDATE_SOCKET_PORT)
        )
    }

    private val communicators = mutableMapOf<Int, Communicator>()
    private val messageQueue: BlockingQueue<CarLifeMessage> = LinkedBlockingQueue()

    @Volatile
    private var isTerminated = AtomicBoolean(false)

    fun startConnect(host: String): Boolean {
        isTerminated.set(false)
        messageQueue.clear()
        SUPPORT_CHANNELS.forEach {
            communicators[it.first] =
                SocketCommunicator(it.first, host, it.second, messageQueue = messageQueue)
        }

        return true
    }

    override fun terminate() {

        if (!isTerminated.getAndSet(true)) {
            communicators.forEach {
                it.value.terminate()
            }

            messageQueue.clear()
            communicators.clear()
        }
    }

    override fun write(message: CarLifeMessage) {
        if (isTerminated.get()) {
            throw IOException("sockets closed")
        }
        communicators[message.channel]?.write(message)
    }

    override fun read(): CarLifeMessage {
        if (isTerminated.get()) {
            throw IOException("sockets closed")
        }
        val message = messageQueue.take()
        if (message == CarLifeMessage.NULL) {
            throw IOException("socket closed")
        }
        return message
    }
}