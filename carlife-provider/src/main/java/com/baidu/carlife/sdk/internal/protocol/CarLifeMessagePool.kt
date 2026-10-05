package com.baidu.carlife.sdk.internal.protocol

import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.util.Logger
import java.util.*

class CarLifeMessagePool(private val capacity: Int = 100) {
    // 消息对象缓存池
    private val recyclePoll = LinkedList<CarLifeMessage>()

    @Synchronized
    fun obtain(
        channel: Int = MSG_CHANNEL_CMD,
        serviceType: Int? = null,
        length: Int = 0
    ): CarLifeMessage {
        Logger.v(Constants.TAG, "CarLifeMessagePool recyclePoll obtain size ${recyclePoll.size}")
        val message = recyclePoll.poll() ?: run {
            CarLifeMessage(channel)
        }
        message.reset(channel, length)
        if (serviceType != null) {
            message.serviceType = serviceType
        }
        return message
    }

    @Synchronized
    fun recycle(message: CarLifeMessage) {
        if (recyclePoll.size < capacity) {
            recyclePoll.offer(message)
        }
        Logger.v(Constants.TAG, "CarLifeMessagePool recyclePoll recycle size ${recyclePoll.size}")
    }
}