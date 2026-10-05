package com.baidu.carlife.sdk.internal.protocol

import com.baidu.carlife.protobuf.*
import com.baidu.carlife.sdk.CarLifeModule

fun CarLifeModule.proto(): CarlifeModuleStatusProto.CarlifeModuleStatus {
    return CarlifeModuleStatusProto.CarlifeModuleStatus
        .newBuilder()
        .setModuleID(id)
        .setStatusID(state)
        .build()
}

fun List<CarLifeModule>.proto(): CarlifeModuleStatusListProto.CarlifeModuleStatusList {
    val builder =  CarlifeModuleStatusListProto.CarlifeModuleStatusList.newBuilder()
    forEach {
        builder.addModuleStatus(it.proto())
    }
    builder.cnt = builder.moduleStatusCount
    return builder.build()
}

fun List<CarlifeFeatureConfigProto.CarlifeFeatureConfig>.proto()
        : CarlifeFeatureConfigListProto.CarlifeFeatureConfigList {
    val builder =  CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.newBuilder()
    forEach {
        builder.addFeatureConfig(it)
    }
    builder.cnt = builder.featureConfigCount
    return builder.build()
}

fun List<CarlifeFeatureConfigProto.CarlifeFeatureConfig>.builder()
        : CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.Builder {
    val builder =  CarlifeFeatureConfigListProto.CarlifeFeatureConfigList.newBuilder()
    forEach {
        builder.addFeatureConfig(it)
    }
    builder.cnt = builder.featureConfigCount
    return builder
}

fun List<CarlifeSubscribeMobileCarLifeInfoProto.CarlifeSubscribeMobileCarLifeInfo>.proto()
        : CarlifeSubscribeMobileCarLifeInfoListProto.CarlifeSubscribeMobileCarLifeInfoList {
    val builder =  CarlifeSubscribeMobileCarLifeInfoListProto.CarlifeSubscribeMobileCarLifeInfoList.newBuilder()
    forEach {
        builder.addSubscribemobileCarLifeInfo(it)
    }
    builder.cnt = builder.subscribemobileCarLifeInfoCount
    return builder.build()
}

fun List<CarlifeVehicleInfoProto.CarlifeVehicleInfo>.proto()
        : CarlifeVehicleInfoListProto.CarlifeVehicleInfoList {
    val builder =  CarlifeVehicleInfoListProto.CarlifeVehicleInfoList.newBuilder()
    forEach {
        builder.addVehicleInfo(it)
    }
    builder.cnt = builder.vehicleInfoCount
    return builder.build()
}

fun List<CarlifeFeatureConfigProto.CarlifeFeatureConfig>.toMap(): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    forEach {
        map[it.key] = it.value
    }
    return map
}