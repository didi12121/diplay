package com.baidu.carlife.sdk.receiver.protocol.v1

import com.baidu.carlife.protobuf.CarlifeSubscribeMobileCarLifeInfoListProto
import com.baidu.carlife.protobuf.CarlifeVehicleInfoProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.protocol.proto
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger

class DualSubscribeHandler: TransportListener {

    override fun onSendMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        return false
    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {

        when(message.serviceType) {
            ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE_DONE -> handleCarlifeDataSubscribeResponse(context, message)
            ServiceTypes.MSG_CMD_CAR_DATA_SUBSCRIBE_REQ -> handleHUCarDataSubscribeDone(context, message)
            ServiceTypes.MSG_CMD_CAR_DATA_START_REQ -> handleMDSubscribeAction(context, message, true)
            ServiceTypes.MSG_CMD_CAR_DATA_STOP_REQ -> handleMDSubscribeAction(context, message, false)
        }
        return false
    }

    /**
     * CarLife 订阅移动设备Carlife信息的回复, 发送MSG_CMD_CARLIFE_DATA_SUBSCRIBE触发
     */
    private fun handleCarlifeDataSubscribeResponse(context: CarLifeContext, message: CarLifeMessage) {
        val carlifeInfoList = message.protoPayload as? CarlifeSubscribeMobileCarLifeInfoListProto.
        CarlifeSubscribeMobileCarLifeInfoList
        carlifeInfoList?.subscribemobileCarLifeInfoList?.forEach {
            val subscriber = context.findSubscriber(it.moduleID)
            subscriber?.process(context, it)
        }
    }

    private fun handleHUCarDataSubscribeDone(context: CarLifeContext, message: CarLifeMessage) {
        var message = CarLifeMessage.obtain(Constants.MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_CAR_DATA_SUBSCRIBE_RSP)
        message.payload(context.listSubscribable().proto())
        context.postMessage(message)
    }

    private fun handleMDSubscribeAction(context: CarLifeContext, message: CarLifeMessage, isStart: Boolean) {
        val vehicleInfo = message.protoPayload as? CarlifeVehicleInfoProto.CarlifeVehicleInfo
        vehicleInfo?.let {
            if (isStart) {
                context.findSubscribable(it.moduleID)?.subscribe()
            }
            else {
                context.findSubscribable(it.moduleID)?.unsubscribe()
            }
        }
    }
}