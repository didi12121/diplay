package com.baidu.carlife.sdk.util.orientation

import android.util.Log
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants

/**
 * 此类用于监控车机屏幕方便的旋转，应用根据旋转做相应事情
 * 有需要的车厂可以使用，不需要的车厂可以删除此代码
 */
class OrientationMonitor(context: CarLifeContext) {
    init {
        try {
            UseWindowManager(context)
        }
        catch (e: Exception) {
            Log.e(Constants.TAG, "OrientationMonitor create UseWindowManager exception: $e")
            UseOrientationEventListener(context)
        }
    }
}