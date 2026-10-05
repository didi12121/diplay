package com.baidu.carlife.sdk.util

import android.view.MotionEvent
import com.baidu.carlife.sdk.Constants
import java.lang.Exception
import java.lang.reflect.Method

private var setDisplayIdMethod: Method? = null
private var getDisplayIdMethod: Method? = null

fun MotionEvent.setDisplayId(displayId: Int) {
    if (setDisplayIdMethod == null) {
        setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    try {
        setDisplayIdMethod?.invoke(this, displayId)
    } catch (e: Exception) {
        Logger.e(Constants.TAG, "RemoteControlManager setDisplayId exception:", e)
    }
}

fun MotionEvent.getDisplayId(): Int {
    if (getDisplayIdMethod == null) {
        getDisplayIdMethod = MotionEvent::class.java.getMethod("getDisplayId")
    }

    try {
        return getDisplayIdMethod?.invoke(this) as Int
    } catch (e: Exception) {
        Logger.e(Constants.TAG, "RemoteControlManager setDisplayId exception:", e)
        return 0
    }
}

