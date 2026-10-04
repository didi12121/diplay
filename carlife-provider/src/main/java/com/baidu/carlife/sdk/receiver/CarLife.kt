package com.baidu.carlife.sdk.receiver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.baidu.carlife.sdk.BuildConfig
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import com.baidu.carlife.sdk.util.isMainProcess

@DoNotStrip
object CarLife {
    private var receiver: CarLifeReceiver? = null

    /**
     * 初始化SDK模块
     * @param context: 应用context
     * @param channel: 车机申请的合法渠道号
     * @param cuid: 唯一标识符
     * @param features： 车机端支持的Feature
     * @param activityClass: 应用的主Activity
     * @param configs: 车机端的一些配置
     */
    @JvmStatic
    fun init(
        context: Context,
        channel: String,
        cuid: String,
        features: Map<String, Int>,
        activityClass: Class<out Activity>,
        configs: Map<String, Any>
    ) {

        if (context.isMainProcess()) {
            receiver = CarLifeReceiverImpl(context, channel, cuid, features.toMutableMap(), activityClass, configs)
        } else {
            throw IllegalAccessException("can't init CarLife receiver not in main process")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, CarLifeReceiverService::class.java))
        } else {
            context.startService(Intent(context, CarLifeReceiverService::class.java))
        }

        Logger.w(Constants.TAG, "carlife sdk version: ${BuildConfig.CARLIFE_SDK_VERSION}")
    }

    /**
     * 获取CarlifeReceiver，SDK模块的相关调用需通过它设置
     */
    @JvmStatic
    fun receiver(): CarLifeReceiver {
        receiver?.let { return it }

        throw RuntimeException("CarLife not init or init failed")
    }
}