package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

/**
 * 订阅者
 * process方法用于处理『被订阅者』发送过来的消息
 */

@DoNotStrip
interface CarLifeSubscriber: TransportListener {
    val id: Int

    fun process(context: CarLifeContext, info: Any)
}