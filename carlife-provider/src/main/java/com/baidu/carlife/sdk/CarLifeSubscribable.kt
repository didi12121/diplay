package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

/**
 * 被订阅者
 * subscribe方法用于开始向『订阅者』发送消息
 * unsubscribe方法用于停止向『订阅者』发送消息
 */
@DoNotStrip
interface CarLifeSubscribable {
     val id: Int
     var SupportFlag: Boolean

     fun subscribe()
     fun unsubscribe()
}