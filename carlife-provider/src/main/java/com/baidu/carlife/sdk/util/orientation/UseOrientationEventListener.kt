package com.baidu.carlife.sdk.util.orientation

import android.content.Context
import android.view.OrientationEventListener
import android.view.WindowManager
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConfigurationChangeListener
import com.baidu.carlife.sdk.ConnectionChangeListener

class UseOrientationEventListener(private val context: CarLifeContext)
    : OrientationEventListener(context.applicationContext), ConnectionChangeListener {
    private val appContext = context.applicationContext
    private val windows: WindowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rotation: Int = windows.defaultDisplay.rotation

    init {
        context.registerConnectionChangeListener(this)
    }

    override fun onOrientationChanged(degree: Int) {
        val newRotation = windows.defaultDisplay.rotation
        if (newRotation != rotation && (rotation + newRotation) % 2 != 0) {
            rotation = newRotation
            context.notifyConfigurationChange(ConfigurationChangeListener.ORIENTATION)
        }
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        enable()
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        disable()
    }
}