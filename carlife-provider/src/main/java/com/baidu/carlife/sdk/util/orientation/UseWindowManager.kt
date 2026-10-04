package com.baidu.carlife.sdk.util.orientation

import android.annotation.SuppressLint
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.IRotationWatcher
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConfigurationChangeListener
import com.baidu.carlife.sdk.ConnectionChangeListener
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import java.lang.reflect.Method

@DoNotStrip
@SuppressLint("PrivateApi")
class UseWindowManager(private val context: CarLifeContext)
    : IRotationWatcher.Stub(), ConnectionChangeListener {
    private val windowManagerService: Any

    private val watchRotation: Method
    private val removeWatcher: Method

    init {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val serviceBinder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(serviceManager, "window") as IBinder
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        windowManagerService = stub
                .getMethod("asInterface", IBinder::class.java)
                .invoke(stub, serviceBinder) as Any

        watchRotation = if (Build.VERSION.SDK_INT >= 26) {
            windowManagerService.javaClass
                    .getMethod("watchRotation", IRotationWatcher::class.java, Int::class.javaPrimitiveType)
        } else {
            windowManagerService.javaClass
                    .getMethod("watchRotation", IRotationWatcher::class.java)
        }

        removeWatcher = windowManagerService.javaClass
                .getMethod("removeRotationWatcher", IRotationWatcher::class.java)

        context.registerConnectionChangeListener(this)
        Logger.d(Constants.TAG, "OrientationMonitor UseWindowManager")
    }

    override fun onRotationChanged(rotation: Int) {
        Logger.d(Constants.TAG, "OrientationMonitor onRotationChanged $rotation")
        context.notifyConfigurationChange(ConfigurationChangeListener.ORIENTATION)
    }


    override fun onConnectionEstablished(context: CarLifeContext) {
        try {
            if (Build.VERSION.SDK_INT >= 26)
                watchRotation.invoke(windowManagerService, this, Display.DEFAULT_DISPLAY)
            else
                watchRotation.invoke(windowManagerService, this)
        }
        catch (e: Exception) {
            Log.e(Constants.TAG, "OrientationMonitor UseWindowManager watchRotation exception: $e")
        }
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        try {
            removeWatcher.invoke(windowManagerService, this)
        }
        catch (e: Exception) {
            Log.e(Constants.TAG, "OrientationMonitor UseWindowManager removeRotationWatcher exception: $e")
        }
    }
}

