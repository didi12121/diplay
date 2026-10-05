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

class WirlessConnector(
    // DiPlay host-local extension (Phase 9.2W-A): diagnostics-only probe.
    private val probe: WirlessTransportProbe? = null,
) : Communicator {
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

        /** DiPlay: exported for probe diagnostics (upstream values, verbatim). */
        fun channelPorts(): List<Pair<Int, Int>> = SUPPORT_CHANNELS.toList()
    }

    private val communicators = mutableMapOf<Int, Communicator>()
    private val messageQueue: BlockingQueue<CarLifeMessage> = LinkedBlockingQueue()

    @Volatile
    private var isTerminated = AtomicBoolean(false)

    /**
     * Opens one TCP channel set to [host].
     *
     * REAL upstream behavior (audited, Phase 9.2W-A): every
     * `SocketCommunicator(channel, host, port, ...)` opens its `Socket(host,
     * port)` IMMEDIATELY (blocking TCP connect) and starts a reader thread —
     * this is an EAGER connect of all seven channels (7240/8240/9240/9241/
     * 9242/9340/9440), NOT lazy connect-on-read/write.
     *
     * DiPlay host-local robustness (no wire change): a repeated discovery of
     * the same/another phone first tears down any previous channel set (a
     * re-discovery can never leak sockets/reader threads or deliver stale
     * messages), and a partially failed connect closes everything it opened
     * and reports failure instead of leaving half a channel set behind.
     */
    @Synchronized
    fun startConnect(host: String): Boolean {
        isTerminated.set(false)
        // DiPlay: idempotent restart - close the previous channel set first
        // (its terminate() enqueues reader wake-up sentinels), then drop those
        // sentinels so the FRESH channel set starts on a clean queue.
        closeCommunicators()
        messageQueue.clear()
        SUPPORT_CHANNELS.forEach {
            val channel = it.first
            val port = it.second
            probe?.onTcpChannelState(channel, port, "connecting", null)
            try {
                communicators[channel] =
                    SocketCommunicator(channel, host, port, messageQueue = messageQueue)
                probe?.onTcpChannelState(channel, port, "connected", null)
            } catch (e: Exception) {
                probe?.onTcpChannelState(channel, port, "failed", e.javaClass.simpleName)
                // DiPlay: never keep a partial channel set (no leaked sockets).
                closeCommunicators()
                return false
            }
        }

        return true
    }

    @Synchronized
    override fun terminate() {
        if (!isTerminated.getAndSet(true)) {
            closeCommunicators()
            messageQueue.clear()
        }
    }

    /** Closes every open channel socket + reader thread (idempotent). */
    private fun closeCommunicators() {
        communicators.forEach {
            it.value.terminate()
        }
        communicators.clear()
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
