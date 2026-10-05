package com.baidu.carlife.sdk.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

fun Context.isMainProcess(): Boolean {
    return currentProcess() == this.packageName
}

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
fun Context.currentProcess(): String {
    //1)通过Application的API获取当前进程名
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return Application.getProcessName()
    }

    //2)通过反射ActivityThread获取当前进程名
    try {
        val clazz = Class.forName(
                "android.app.ActivityThread",
                false,
                Application::class.java.classLoader)
        val method = clazz.getDeclaredMethod("currentProcessName", *arrayOfNulls(0))
        method.isAccessible = true
        val result = method.invoke(null, *arrayOfNulls(0))
        if (result is String) {
            return result
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    }

    //3)通过ActivityManager获取当前进程名
    val pid = Process.myPid()
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
    val process = activityManager?.runningAppProcesses?.find { it.pid == pid }
    return process?.processName ?: ""
}