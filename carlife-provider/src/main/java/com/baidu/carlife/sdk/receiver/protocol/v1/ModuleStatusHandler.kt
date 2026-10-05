package com.baidu.carlife.sdk.receiver.protocol.v1

import com.baidu.carlife.protobuf.CarlifeModuleStatusListProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MODULE_STATUS
import com.baidu.carlife.sdk.internal.transport.TransportListener

class ModuleStatusHandler: TransportListener {
    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_MODULE_STATUS -> handleModuleStatus(context, message)
        }
        return false
    }

    private fun handleModuleStatus(context: CarLifeContext, message: CarLifeMessage) {
        val moduleStatusList = message.protoPayload
                as? CarlifeModuleStatusListProto.CarlifeModuleStatusList
        if (moduleStatusList != null) {
            for (i in 0 until moduleStatusList.cnt) {
                val moduleStatus = moduleStatusList.getModuleStatus(i)
                val module = context.findModule(moduleStatus.moduleID)
                if (module != null) {
                    module.state = moduleStatus.statusID
                }
            }
        }
    }
}