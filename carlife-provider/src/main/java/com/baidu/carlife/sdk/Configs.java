package com.baidu.carlife.sdk;

import com.baidu.carlife.sdk.util.annotations.DoNotStrip;

@DoNotStrip
public class Configs {
    /**
     * 本地设置参数
     */
    public static final String CONFIG_MAX_LOG_FILE_SIZE = "CONFIG_MAX_LOG_FILE_SIZE";
    public static final String CONFIG_MAX_LOG_FILE_COUNT = "CONFIG_MAX_LOG_FILE_COUNT";
    public static final String CONFIG_LOG_LEVEL = "CONFIG_LOG_LEVEL";
    /**
     * 是否对传输的数据进行加密
     */
    public static final String CONFIG_CONTENT_ENCRYPTION = "CONFIG_CONTENT_ENCRYPTION";

    /**
     * statistics info，receiver初始化时需要传入，必须传入
     */
    public static final String CONFIG_STATISTICS_INFO = "CONFIG_STATISTICS_INFO";

    public static final String CONFIG_LOCK_ORIENTATION = "ScreenCaptureDisplay_LOCK_ORIENTATION";

    /**
     * 是否保存音频数据到文件
     */
    public static final String CONFIG_SAVE_AUDIO_FILE = "CONFIG_SAVE_AUDIO_FILE";
    /**
     * 是否保存视频数据到文件
     */
    public static final String CONFIG_SAVE_VIDEO_FILE = "CONFIG_SAVE_VIDEO_FILE";

    public static final String CONFIG_WIFI_DIRECT_NAME = "CONFIG_WIFI_DIRECT_NAME";
    public static final String CONFIG_TARGET_BLUETOOTH_NAME = "CONFIG_TARGET_BLUETOOTH_NAME";

    public static final String CONFIG_ENABLE_SYSTEM_SCREEN_CAPTURE = "CONFIG_ENABLE_SYSTEM_SCREEN_CAPTURE";
    public static final String CONFIG_DEFAULT_DISPLAY = "CONFIG_DEFAULT_DISPLAY";
    public static final String CONFIG_TEST_DISPLAY_SPEC = "CONFIG_TEST_DISPLAY_SPEC";
    public static final String CONFIG_DISPLAY_FACTORY_CREATOR = "CONFIG_DISPLAY_FACTORY_CREATOR";

    public static final String CONFIG_USE_IMAGE_READER = "EncoderFactory_USE_IMAGE_READER";

    public static final String CONFIG_USE_SANDBOX = "CONFIG_USE_SANDBOX";

    public static final String CONFIG_VENDOR_ID = "CONFIG_VENDOR_ID";
    public static final String CONFIG_VENDOR_DEVICE_ID = "CONFIG_VENDOR_DEVICE_ID";
    public static final String CONFIG_PRESET_CHANNELS = "CONFIG_PRESET_CHANNELS";

    public static final String CONFIG_USE_ASYNC_USB_MODE = "CONFIG_USE_ASYNC_USB_MODE";

    /**
     * 配置蓝牙名称及MAC地址
     */
    public static final String CONFIG_HU_BT_NAME = "CONFIG_HU_BT_NAME";
    public static final String CONFIG_HU_BT_MAC = "CONFIG_HU_BT_MAC";

    /**
     * 配置车机协议版本
     */
    public static final String CONFIG_PROTOCOL_VERSION = "VEHICLE_PROTOCOL_VERSION";

    /**
     * 配置车机协议版本
     */
    public static final String CONFIG_CARLIFE_VERSION = "CARLIFE_PROTOCOL_VERSION";

    /**
     * 设置无线连接类型以及wifi频率
     */
    public static final String CONFIG_WIRLESS_TYPE = "CONFIG_WIRLESS_TYPE";
    public static final String CONFIG_WIRLESS_FREQUENCY = "CONFIG_WIRLESS_FREQUENCY";

    /**
     * 车机端订阅移动设备Carlife设备数据
     */
    public static final int CONFIG_CARLIFE_DATA_TURNBYTURN = 0;
    public static final int CONFIG_CARLIFE_DATA_ASSISTANTGUIDE = 1;

    /**
     * 车机端设置参数
     */
    public static final String FEATURE_CONFIG_VOICE_MIC = "VOICE_MIC";
    public static final String FEATURE_CONFIG_VOICE_WAKEUP = "VOICE_WAKEUP";
    public static final String FEATURE_CONFIG_BLUETOOTH_AUTO_PAIR = "BLUETOOTH_AUTO_PAIR";
    public static final String FEATURE_CONFIG_BLUETOOTH_INTERNAL_UI = "BLUETOOTH_INTERNAL_UI";
    public static final String FEATURE_CONFIG_FOCUS_UI = "FOCUS_UI";
    public static final String FEATURE_CONFIG_FOCUS_AREA_AUTO_SET = "FOCUS_AREA_AUTO_SET";
    public static final String FEATURE_CONFIG_MEDIA_SAMPLE_RATE = "MEDIA_SAMPLE_RATE";

    /**
     *  0:carlife audio path ; 1:BT audio path
     */
    public static final String FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE = "AUDIO_TRANSMISSION_MODE";

    public static final String FEATURE_CONFIG_CONTENT_ENCRYPTION = "CONTENT_ENCRYPTION";
    public static final String FEATURE_CONFIG_ENGINE_TYPE = "ENGINE_TYPE";
    public static final String FEATURE_CONFIG_INPUT_DISABLE = "INPUT_DISABLE";
    public static final String FEATURE_CONFIG_AAC_SUPPORT = "AAC_SUPPORT";
    public static final String FEATURE_CONFIG_MUSIC_HUD = "MUSIC_HUD";

    /**
     * 车机当前连接类型 USB:0x0002 WIFI:0x0005 WIFI DIRECT:0x0009
     */
    public static final String FEATURE_CONFIG_CONNECT_TYPE = "CONNECT_TYPE";

    public static final String FEATURE_CONFIG_MULTI_TOUCH = "MULTI_TOUCH";

    /**
     * 是这USB传输的MTU
     */
    public static final String FEATURE_CONFIG_USB_MTU = "USB_MTU";
    /**
     * 设置I帧间隔，可以由服务器指定
     */
    public static final String FEATURE_CONFIG_I_FRAME_INTERVAL = "I_FRAME_INTERVAL";
}

