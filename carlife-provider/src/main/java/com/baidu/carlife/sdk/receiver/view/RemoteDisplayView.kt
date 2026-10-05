package com.baidu.carlife.sdk.receiver.view

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.receiver.OnVideoSizeChangedListener
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.ScaleUtils
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
@SuppressLint("ClickableViewAccessibility")
class RemoteDisplayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null):
    SurfaceView(context, attrs),
    SurfaceHolder.Callback,
    OnVideoSizeChangedListener {

    init {
        holder.addCallback(this)
        setOnTouchListener(ViewOnTouchListener())

        CarLife.receiver().addOnVideoSizeChangedListener(this)
    }

    fun onDestroy() {
        CarLife.receiver().removeOnVideoSizeChangedListener(this)
    }

    override fun onVideoSizeChanged(videoWidth: Int, videoHeight: Int) {
        post {
            val ratio = videoWidth.toFloat() / videoHeight
            val (destWidth, destHeight) = ScaleUtils.inside(measuredWidth, measuredHeight, ratio)
            layoutParams = layoutParams.apply {
                width = destWidth
                height = destHeight
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        CarLife.receiver().onSurfaceSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // clear receiver surface
        CarLife.receiver().setSurface(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        CarLife.receiver().setSurface(holder.surface)
    }

    inner class ViewOnTouchListener(): OnTouchListener {
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            CarLife.receiver().onTouchEvent(event)
            return true
        }

    }
}