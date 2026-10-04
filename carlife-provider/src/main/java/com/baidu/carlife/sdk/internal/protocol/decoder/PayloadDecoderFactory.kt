package com.baidu.carlife.sdk.internal.protocol.decoder

import com.baidu.carlife.protobuf.*
import com.baidu.carlife.protobuf.CarlifeBTPairInfoProto.CarlifeBTPairInfo
import com.baidu.carlife.protobuf.CarlifeTouchSinglePointProto.CarlifeTouchSinglePoint
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.google.protobuf.CodedInputStream

object PayloadDecoderFactory {
    private val decoders = HashMap<Int, PayloadDecoder<*>>()

    fun decoderOf(serviceType: Int): PayloadDecoder<*>? {
        return decoders[serviceType]
    }

    init {
        decoders[ServiceTypes.MSG_CMD_BOX_ACTIVE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeActiveRequestProto.CarlifeActiveRequest
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_HU_PROTOCOL_VERSION] =
            PayloadDecoder { payload, offset, length ->
                CarlifeProtocolVersionProto.CarlifeProtocolVersion
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_HU_INFO] =
            PayloadDecoder { payload, offset, length ->
                CarlifeDeviceInfoProto.CarlifeDeviceInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_MD_INFO] =
            PayloadDecoder { payload, offset, length ->
                CarlifeDeviceInfoProto.CarlifeDeviceInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_STATISTIC_INFO] =
            PayloadDecoder { payload, offset, length ->
                CarlifeStatisticsInfoProto.CarlifeStatisticsInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_MD_AUTH_RESULT] =
            PayloadDecoder { payload, offset, length ->
                CarlifeAuthenResultProto.CarlifeAuthenResult
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        val featureConfigDecoder = PayloadDecoder { payload, offset, length ->
            CarlifeFeatureConfigListProto.CarlifeFeatureConfigList
                .parseFrom(CodedInputStream.newInstance(payload, offset, length))
        }
        decoders[ServiceTypes.MSG_CMD_MD_FEATURE_CONFIG_REQUEST] = featureConfigDecoder
        decoders[ServiceTypes.MSG_CMD_HU_FEATURE_CONFIG_RESPONSE] = featureConfigDecoder

        decoders[ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_MODULE_CONTROL] =
            PayloadDecoder { payload, offset, length ->
                CarlifeModuleStatusProto.CarlifeModuleStatus
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_MODULE_STATUS] =
            PayloadDecoder { payload, offset, length ->
                CarlifeModuleStatusListProto.CarlifeModuleStatusList
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_DATA_SUBSCRIBE_RSP] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVehicleInfoListProto.CarlifeVehicleInfoList
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_DATA_START_REQ] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVehicleInfoProto.CarlifeVehicleInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_DATA_STOP_REQ] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVehicleInfoProto.CarlifeVehicleInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeSubscribeMobileCarLifeInfoListProto.CarlifeSubscribeMobileCarLifeInfoList
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE_DONE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeSubscribeMobileCarLifeInfoListProto.CarlifeSubscribeMobileCarLifeInfoList
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE_START] =
            PayloadDecoder { payload, offset, length ->
                CarlifeSubscribeMobileCarLifeInfoListProto.CarlifeSubscribeMobileCarLifeInfoList
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE_STOP] =
            PayloadDecoder { payload, offset, length ->
                CarlifeSubscribeMobileCarLifeInfoListProto.CarlifeSubscribeMobileCarLifeInfoList
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_NAV_ASSISTANT_GUIDE_INFO] =
            PayloadDecoder { payload, offset, length ->
                CarlifeNaviAssitantGuideInfoProto.CarlifeNaviAssitantGuideInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_NAV_NEXT_TURN_INFO] =
            PayloadDecoder { payload, offset, length ->
                CarlifeNaviNextTurnInfoProto.CarlifeNaviNextTurnInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_TOUCH_ACTION] =
            PayloadDecoder { payload, offset, length ->
                CarlifeTouchActionProto.CarlifeTouchAction
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_TIME_SYNC] =
                PayloadDecoder { payload, offset, length ->
                    CarlifeConnectTimeSyncProto.CarlifeConnectTimeSync
                            .parseFrom(CodedInputStream.newInstance(payload, offset, length))
                }
        decoders[ServiceTypes.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS] =
                PayloadDecoder { payload, offset, length ->
                    CarlifeProtocolVersionMatchStatusProto.CarlifeProtocolVersionMatchStatus
                            .parseFrom(CodedInputStream.newInstance(payload, offset, length))
                }
        val touchSinglePointDecoder =
            PayloadDecoder { payload, offset, length ->
                CarlifeTouchSinglePoint
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_TOUCH_ACTION_DOWN] = touchSinglePointDecoder
        decoders[ServiceTypes.MSG_TOUCH_ACTION_MOVE] = touchSinglePointDecoder
        decoders[ServiceTypes.MSG_TOUCH_ACTION_UP] = touchSinglePointDecoder
        decoders[ServiceTypes.MSG_TOUCH_SINGLE_CLICK] = touchSinglePointDecoder

        decoders[ServiceTypes.MSG_CMD_HU_BT_OOB_INFO] =
            PayloadDecoder { payload, offset, length ->
                CarlifeBTPairInfo.parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeHuRsaPublicKeyResponseProto.CarlifeHuRsaPublicKeyResponse
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT_DONE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVideoFrameRateProto.CarlifeVideoFrameRate
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVideoFrameRateProto.CarlifeVideoFrameRate
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_MEDIA_INIT] =
            PayloadDecoder { payload, offset, length ->
                CarlifeMusicInitProto.CarlifeMusicInit
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }

        val ttsInitDecoder = PayloadDecoder { payload, offset, length ->
            CarlifeTTSInitProto.CarlifeTTSInit
                .parseFrom(CodedInputStream.newInstance(payload, offset, length))
        }
        decoders[ServiceTypes.MSG_NAV_TTS_INIT] = ttsInitDecoder
        decoders[ServiceTypes.MSG_VR_AUDIO_INIT] = ttsInitDecoder

        decoders[ServiceTypes.MSG_TOUCH_CAR_HARD_KEY_CODE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeCarHardKeyCodeProto.CarlifeCarHardKeyCode
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_WIRELESS_INFO_RESPONSE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeWirlessInfoProto.CarlifeWirlessInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_WIRELESS_TARGET_INFO_RESPONSE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeWirlessTargetProto.CarlifeWirlessTarget
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_WIRELESS_MD_STATUS] =
            PayloadDecoder { payload, offset, length ->
                CarlifeWirlessStatusProto.CarlifeWirlessStatus
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_HU_AUTH_REQUEST] =
            PayloadDecoder { payload, offset, length ->
                CarlifeAuthenRequestProto.CarlifeAuthenRequest
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_MD_AUTH_RESPONSE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeAuthenResponseProto.CarlifeAuthenResponse
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_HU_AUTH_RESULT] =
            PayloadDecoder { payload, offset, length ->
                CarlifeAuthenResultProto.CarlifeAuthenResult
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_DATA_MD_TRANSFER_START] =
            PayloadDecoder { payload, offset, length ->
                CarlifeFileTransferBeginProto.CarlifeFileTransferBegin
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_WIRELESS_RESPONSE_IP] =
            PayloadDecoder { payload, offset, length ->
                CarlifeWirlessIpProto.CarlifeWirlessIp
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_MD_AES_KEY_SEND_REQUEST] =
                PayloadDecoder { payload, offset, length ->
                    CarlifeMdAesKeyRequestProto.CarlifeMdAesKeyRequest
                            .parseFrom(CodedInputStream.newInstance(payload, offset, length))
                }
        decoders[ServiceTypes.MSG_CMD_VEHICLE_CONTROL] =
            PayloadDecoder { payload, offset, length ->
                CarlifeVehicleControlProto.CarlifeVehicleControl
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_GPS] =
            PayloadDecoder { payload, offset, length ->
                CarlifeCarGpsProto.CarlifeCarGps
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_VELOCITY] =
            PayloadDecoder { payload, offset, length ->
                CarlifeCarSpeedProto.CarlifeCarSpeed
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_GYROSCOPE] =
            PayloadDecoder { payload, offset, length ->
                CarlifeGyroscopeProto.CarlifeGyroscope
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_ACCELERATION] =
            PayloadDecoder { payload, offset, length ->
                CarlifeAccelerationProto.CarlifeAcceleration
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_GEAR] =
            PayloadDecoder { payload, offset, length ->
                CarlifeGearInfoProto.CarlifeGearInfo
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
        decoders[ServiceTypes.MSG_CMD_CAR_OIL] =
            PayloadDecoder { payload, offset, length ->
                CarlifeOilProto.CarlifeOil
                    .parseFrom(CodedInputStream.newInstance(payload, offset, length))
            }
    }
}