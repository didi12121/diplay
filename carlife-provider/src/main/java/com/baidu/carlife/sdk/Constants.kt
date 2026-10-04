package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
object Constants {
    const val TAG = "CarLife_SDK"

    const val BLUETOOH_TAG = "Bluetooth_CarLife_SDK"

    const val AOA_MESSAGE_MAX_SIZE = 64 * 1024 * 1024

    const val BLUETOOTH_COMMUNICATE_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    const val MSG_CHANNEL_CMD = 1
    const val MSG_CHANNEL_VIDEO = 2
    const val MSG_CHANNEL_AUDIO = 3 // Media通道
    const val MSG_CHANNEL_AUDIO_TTS = 4 // TTS通道
    const val MSG_CHANNEL_AUDIO_VR = 5 // VR通道
    const val MSG_CHANNEL_TOUCH = 6
    const val MSG_CHANNEL_UPDATE = 7

    const val VALUE_DISCONNECT = 0 // CarLife断开

    const val VALUE_CONNECTED = 1 // CarLife连接成功

    const val VALUE_NO_WIFI = 2 // 没打开wifi

    const val VALUE_LOW_POWER = 3 // 低电量

    // modules
    /**
     * 电话模块id
     */
    const val PHONE_MODULE_ID = 1

    // 空闲状态
    const val PHONE_STATUS_IDLE = 0

    // 来电
    const val PHONE_STATUS_INCOMING = 1

    // 去电
    const val PHONE_STATUS_OUTGOING = 2

    /**
     * 导航模块id
     *
     * 保持与iOS、接入文档同步，只保留0和1
     */
    const val NAVI_MODULE_ID = 2

    // 导航空闲状态
    const val NAVI_STATUS_IDLE = 0

    // 导航开始
    const val NAVI_STATUS_START = 1

    /**
     * 音乐模块id
     */
    const val MUSIC_MODULE_ID = 3

    // 空闲状态
    const val MUSIC_STATUS_IDLE = 0

    // 播放中
    const val MUSIC_STATUS_RUNNING = 1

    /**
     * 录音模块id
     */
    const val VR_MODULE_ID = 4

    // 空闲状态
    const val VR_STATUS_IDLE = 0

    // 录音中
    const val VR_STATUS_RUNNING = 1

    // 车机端MIC不支持
    const val VR_STATUS_MIC_NOT_SUPPORTED = 2

    /**
     * 连接类型id 当前仅ios手机用到
     */
    const val CONNECT_MODULE_ID = 5

    /**
     * MIC模块id
     */
    const val MIC_MODULE_ID = 6

    // 使用车机端MIC
    const val MIC_STATUS_USE_VEHICLE_MIC = 0

    // 录音用手机原生MIC
    const val MIC_STATUS_USE_MOBILE_MIC = 1

    // 车机端MIC不支持
    const val MIC_STATUS_NOT_SUPPORTED = 2

    // 车机端支持回声消噪，排列方式是先左声道\右声道
    const val MIC_STATUS_VEHICLE_SUPPORT_AES_MIC_LEFT = 3

    // 车机端支持回声消噪，排列方式是先右声道\左声道
    const val MIC_STATUS_VEHICLE_SUPPORT_AES_MIC_RIGHT = 4

    /**
     * 电子狗模块
     */
    const val CRUISE_MODULE_ID = 8

    // 电子狗空闲状态
    const val CRUISE_STATUS_IDLE = 0

    // 电子狗开始
    const val CRUISE_STATUS_START = 1

    /**
     * 巡航模式模块
     */
    const val CRUISE_FOLLOW_MODULE_ID = 9

    // 巡航模式空闲状态
    const val CRUISE_FOLLOW_STATUS_IDLE = 0

    // 巡航模式开始
    const val CRUISE_FOLLOW_STATUS_START = 1

    //response状态
    const val MSG_RESPONSE_SUCCESS = 200;

    //是否上传到协议工具
    const val ISUPLOAD = false;

    //连接进度
    const val VALUE_PROGRESS_0 = 0
    const val VALUE_PROGRESS_30 = 30
    const val VALUE_PROGRESS_70 = 70
    const val VALUE_PROGRESS_100 = 100

    // message define
    const val VIDEO_ENCODER_EROOR = 1
    const val VIDEO_PERMISSION_DENIED = 2
    const val VIDEO_PAUSE_BY_SCREENSHARE_REQUEST = 3
    const val PERMISSION_CODE = 0x1101
    const val SDK_VERSION_CODE = "2.0"
}
