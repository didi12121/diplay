package com.baidu.carlife.sdk.receiver.display

import android.view.Surface
import com.baidu.carlife.protobuf.CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage.Companion.obtain
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT_DONE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_PAUSE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_START
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_VIDEO_DATA
import com.baidu.carlife.sdk.receiver.OnVideoSizeChangedListener
import com.baidu.carlife.sdk.internal.DisplaySpec
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.receiver.SurfaceRequestCallback
import com.baidu.carlife.sdk.util.Logger

class RemoteDisplayRenderer(private val context: CarLifeContext,
                            private val listener: OnVideoSizeChangedListener
): TransportListener {
    var encoderInfo: CarlifeVideoEncoderInfo? = null
        private set
    private var frameDecoder: FrameDecoder? = null
    private var surface: Surface? = null

    var displaySpec: DisplaySpec

    var surfaceRequester: SurfaceRequestCallback? = null


    init {
        val screenWidth = context.applicationContext.resources.displayMetrics.widthPixels
        val screenHeight = context.applicationContext.resources.displayMetrics.heightPixels
        displaySpec = DisplaySpec(context.applicationContext, screenWidth, screenHeight, 30)
    }

    @Synchronized
    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface == null) {
            Logger.d(Constants.TAG, "RemoteDisplayRenderer setSurface to null")
            frameDecoder?.let {
                it.release()
                frameDecoder = null
                context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_PAUSE)
            }
            return
        }

        if (encoderInfo != null && frameDecoder == null) {
            // 说明收到MSG_CMD_VIDEO_ENCODER_INIT_DONE的时候，还没有surface
            // 此时需要创建decoder，并发送MSG_CMD_VIDEO_ENCODER_START
            frameDecoder = FrameDecoder(context, surface, encoderInfo!!)
            context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_START)
            Logger.d(Constants.TAG, "RemoteDisplayRenderer create decoder delayed")
        }
        else {
            frameDecoder?.let {
                Logger.d(Constants.TAG, "RemoteDisplayRenderer decoder set new surface")
                it.setSurface(surface)
            }
        }
    }

    private fun stop() {
        frameDecoder?.let {
            it.release()
            frameDecoder = null
        }
        encoderInfo = null
    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_VIDEO_ENCODER_INIT_DONE -> {
                synchronized(this) {
                    encoderInfo = message.protoPayload as CarlifeVideoEncoderInfo
                    listener.onVideoSizeChanged(encoderInfo!!.width, encoderInfo!!.height)
                    if (surface != null) {
                        frameDecoder = FrameDecoder(context, surface!!, encoderInfo!!)
                        var messgae = obtain(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_START)
                        context.postMessage(messgae)
                        Logger.d(Constants.TAG, "RemoteDisplayRenderer postMessage MSG_CMD_VIDEO_ENCODER_START 1")
                        return true
                    }
                }
                encoderInfo?.let {
                    surfaceRequester?.requestSurface(it.width, it.height)
                    Logger.d(Constants.TAG, "RemoteDisplayRenderer decoder surface null")
                }
                return true
            }
            MSG_VIDEO_DATA -> {
                if (message.payloadSize != 0) {
                    frameDecoder?.feedFrame(message)
                }
                return true
            }
        }
        return false
    }

    fun onActivityStarted() {
        if (frameDecoder != null) {
            context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_START)
        }
    }

    fun onActivityStopped() {
        if (frameDecoder != null) {
            context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_PAUSE)
        }
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        stop()
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        stop()
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        handleSendVideoInit()
    }

    private fun handleSendVideoInit() {
        // 发送Encoder Init
        val message = obtain(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT, 0)
        message.payload(CarlifeVideoEncoderInfo.newBuilder()
            .setWidth(displaySpec.width)
            .setHeight(displaySpec.height)
            .setFrameRate(displaySpec.frameRate)
            .build())
        context.postMessage(message)
    }
}