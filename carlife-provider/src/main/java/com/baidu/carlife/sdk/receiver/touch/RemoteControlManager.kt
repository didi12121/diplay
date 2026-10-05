package com.baidu.carlife.sdk.receiver.touch

import android.graphics.Point
import android.util.Log
import android.view.MotionEvent
import com.baidu.carlife.protobuf.CarlifeTouchActionProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_TOUCH
import com.baidu.carlife.sdk.FeatureConfigChangeListener
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_TOUCH_ACTION
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_TOUCH_ACTION_3
import com.baidu.carlife.sdk.receiver.OnVideoSizeChangedListener
import com.baidu.carlife.sdk.util.fillByteArray

class RemoteControlManager(private val context: CarLifeContext) : OnVideoSizeChangedListener,
    FeatureConfigChangeListener {
    companion object {
        private const val POINTER_DATA_SIZE = 5
    }

    // 新的反控事件service type，不使用protobuf封装了，采用新的格式
    // action   pointerId1 x1       y1        pointerId2 x2       y2        .......
    // 4 bytes  1 byte     2 bytes  2 bytes   1 byte     2 bytes  2 bytes
    private var videoWidth = 0
    private var videoHeight = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var enableMultiTouch = 0
    private val point = Point()
    private val pointerCoords =
        MotionEvent.PointerCoords().apply {
            orientation = 0f
            size = 1f
        }

    init {
        context.addFeatureConfigChangeListener(this)
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    fun onSurfaceSizeChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    fun onTouchEvent(event: MotionEvent) {
        if (enableMultiTouch == 1) {
            sendTouchEvent(event)
        } else {
            // 为了支持老版本客户端。同时发送protobuf数据
            sendTouchEventLegacy(event)
        }
    }

    override fun onFeatureConfigChanged() {
        enableMultiTouch = context.getFeature(Configs.FEATURE_CONFIG_MULTI_TOUCH, 0)
    }

    /**
     * 支持双指触控
     */
    private fun sendTouchEventLegacy(event: MotionEvent) {
        val message = CarLifeMessage.obtain(MSG_CHANNEL_TOUCH, MSG_TOUCH_ACTION)
        message.payload(
            CarlifeTouchActionProto.CarlifeTouchAction.newBuilder()
                .setAction(event.action)
                .setX(transformX(event.x.toInt()))
                .setY(transformY(event.y.toInt()))
                .setPointerx(getPointerX(event))
                .setPointery(getPointerY(event))
                .build()
        )
        message.tag = System.currentTimeMillis()
        context.postMessage(message)
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val message = CarLifeMessage.obtain(
            MSG_CHANNEL_TOUCH,
            MSG_TOUCH_ACTION_3,
            CarLifeMessage.SHORT_COMMAND_SIZE + 4 + event.pointerCount * POINTER_DATA_SIZE
        )
        message.tag = System.currentTimeMillis()
        message.payloadSize = serializeEvent(event, message.body, message.commandSize)
        context.postMessage(message)
    }

    private fun serializeEvent(event: MotionEvent, buffer: ByteArray, offset: Int): Int {
        // 先填充action
        event.action.fillByteArray(buffer, offset, 4)
        // 填充每个手指的数据
        var index = offset + 4
        for (i in 0 until event.pointerCount) {
            val pointId = event.getPointerId(i)
            event.getPointerCoords(i, pointerCoords)
            transformLocation(pointerCoords.x.toInt(), pointerCoords.y.toInt(), point)
            pointId.fillByteArray(buffer, index, 1)
            index += 1
            point.x.fillByteArray(buffer, index, 2)
            index += 2
            point.y.fillByteArray(buffer, index, 2)
            index += 2
        }
        return index - offset
    }

    private fun transformLocation(x: Int, y: Int, outPoint: Point) {
        if (videoWidth == 0 || videoHeight == 0 || surfaceWidth == 0 || surfaceHeight == 0) {
            outPoint.x = x
            outPoint.y = y
        } else {
            outPoint.x = (x * videoWidth / surfaceWidth)
            outPoint.y = (y * videoHeight / surfaceHeight)
        }
    }

    private fun transformX(x: Int): Int {
        return if (videoWidth == 0 || videoHeight == 0 || surfaceWidth == 0 || surfaceHeight == 0) {
            x
        } else {
            (x * videoWidth / surfaceWidth)
        }
    }

    private fun transformY(y: Int): Int {
        return if (videoWidth == 0 || videoHeight == 0 || surfaceWidth == 0 || surfaceHeight == 0) {
            y
        } else {
            (y * videoHeight / surfaceHeight)
        }
    }

    private fun getPointerX(event: MotionEvent): Int {
        return if (event.pointerCount > 1) {
            transformX(event.getX(1).toInt())
        } else {
            0
        }
    }

    private fun getPointerY(event: MotionEvent): Int {
        return if (event.pointerCount > 1) {
            transformY(event.getY(1).toInt())
        } else {
            0
        }
    }

}