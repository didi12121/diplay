package com.baidu.carlife.sdk.internal.transport.communicator

import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import java.io.IOException

interface Communicator {
    interface Callbacks {
        fun onTerminated(channel: Int)
    }

    fun terminate()

    /**
     * 发送消息
     */
    @Throws(IOException::class)
    fun write(message: CarLifeMessage)

    @Throws(IOException::class)
    fun read(): CarLifeMessage
}