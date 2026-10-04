package com.baidu.carlife.sdk.internal.transport

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

/**
 * 此接口的两个方法里面不能进行耗时操作，否则影响整个车机互联的交互；
 * 特别是onReceiveMessage方法，如阻塞则会影响下一条消息的接收。
 */

@DoNotStrip
interface TransportListener: ConnectionChangeListener {
    /**
     * called by transport when send a message
     * Avoid time-consuming operations
     * @return 是否继续停止分发
     *
     * 特别提醒：此方法不能进行耗时操作，否则影响消息分发
     */
    fun onSendMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        return false
    }

    /**
     * called by transport when receive a message
     * Avoid time-consuming operations
     * @return 是否继续停止分发
     *
     * 特别提醒：此方法不能进行耗时操作，否则影响消息分发
     */
    fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        return false
    }
}