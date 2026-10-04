package com.baidu.carlife.sdk.receiver.transport.ios

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.ProtocolTransport

/**
 * author : wangchangming
 * date : 2022/4/2 5:47 下午
 * description: ios的USB 有线连接，因为ios限制，这里仅给出类名。
 * 如有厂商愿意合作一起开发ios有线连接，则到时再来补充联调
 */
class NCMProtocolTransport(
    private val mCarLifeContext: CarLifeContext,
    connectionListener: ProtocolTransport.ConnectionListener?
) : ProtocolTransport(mCarLifeContext, connectionListener) {
    override fun connect() {
        TODO("Not yet implemented")
    }

    override fun ready() {
        TODO("Not yet implemented")
    }

    override fun listen() {
        TODO("Not yet implemented")
    }

    override fun terminate() {
        TODO("Not yet implemented")
    }

    override fun write(message: CarLifeMessage) {
        TODO("Not yet implemented")
    }

    override fun read(): CarLifeMessage {
        TODO("Not yet implemented")
    }

}