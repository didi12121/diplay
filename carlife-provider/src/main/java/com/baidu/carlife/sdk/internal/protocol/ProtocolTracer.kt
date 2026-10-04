package com.baidu.carlife.sdk.internal.protocol

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_VIDEO
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MEDIA_PROGRESS_BAR
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_MEDIA_DATA
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_MEDIA_DATA_ENCODER
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_NAV_TTS_DATA
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_VIDEO_DATA
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_VIDEO_HEARTBEAT
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_VR_AUDIO_DATA
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_VR_DATA
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger

class ProtocolTracer(private val isReceiver: Boolean) : TransportListener {
    private var tick = 0L

    private var frameCount = 0
    private var byteCount = 0L
    private var mIsDiscardMsg = true;

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        val serviceType = message.serviceType
        when (serviceType) {
            ServiceTypes.MSG_CMD_HU_PROTOCOL_VERSION -> mIsDiscardMsg = false
        }

        if (!isReceiver && mIsDiscardMsg) {
            return true
        }

        if (serviceType != MSG_VIDEO_DATA
            && serviceType != MSG_VR_DATA
            && serviceType != MSG_CMD_MEDIA_PROGRESS_BAR
        ) {
            if (
                serviceType == MSG_MEDIA_DATA
                || serviceType == MSG_MEDIA_DATA_ENCODER
                || serviceType == MSG_VIDEO_HEARTBEAT
                || serviceType == MSG_VR_AUDIO_DATA
                || serviceType == MSG_NAV_TTS_DATA
            ) {
                Logger.v(
                    Constants.TAG, "ProtocolTracer onReceiveMessage ", message,
                    " @" + ServiceTypes.getMsgName(serviceType)
                )
            } else {
                Logger.d(
                    Constants.TAG, "ProtocolTracer onReceiveMessage ", message,
                    " @" + ServiceTypes.getMsgName(serviceType)
                )
            }
        }

        monitorTraffic(message)

        return false
    }

    override fun onSendMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        val serviceType = message.serviceType
        if (serviceType != MSG_VIDEO_DATA
            && serviceType != MSG_MEDIA_DATA
            && serviceType != MSG_VR_DATA
            && serviceType != MSG_VR_AUDIO_DATA
            && serviceType != MSG_CMD_MEDIA_PROGRESS_BAR
        ) {
            if (serviceType == MSG_VIDEO_HEARTBEAT) {
                Logger.v(
                    Constants.TAG, "ProtocolTracer onSendMessage ", message,
                    " @MSG_VIDEO_HEARTBEAT"
                )
            } else {
                Logger.d(
                    Constants.TAG, "ProtocolTracer onSendMessage ", message,
                    " @" + ServiceTypes.getMsgName(serviceType)
                )
            }
        }else {
            Logger.v(
                Constants.TAG, "ProtocolTracer onSendMessage ", message,
                " @" + ServiceTypes.getMsgName(serviceType)
            )
        }

        return false
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        Logger.d(Constants.TAG, "ProtocolTracer onConnectionDetached ", RuntimeException())
    }

    override fun onConnectionAttached(context: CarLifeContext) {
        Logger.d(Constants.TAG, "ProtocolTracer onConnectionAttached ", RuntimeException())
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        Logger.d(Constants.TAG, "ProtocolTracer onConnectionReattached ", RuntimeException())
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        Logger.d(Constants.TAG, "ProtocolTracer onConnectionEstablished ", RuntimeException())
    }

    private fun monitorTraffic(message: CarLifeMessage) {
        if (message.channel == MSG_CHANNEL_VIDEO) {
            ++frameCount
        }
        byteCount += message.size + CarLifeMessage.HEADER_SIZE

        if (tick == 0L) {
            tick = System.currentTimeMillis()
        }

        val now = System.currentTimeMillis()
        val interval = now - tick
        if (interval > 1000) {
            // 五秒钟计算一次bitrate和frame rate
            val bitrate = byteCount * 8 * 1000 / interval
            val frameRate = frameCount * 1000 / interval
            Logger.v(Constants.TAG, "ProtocolTracer bitrate ", bitrate, " frame rate ", frameRate)

            tick = now
            frameCount = 0
            byteCount = 0
        }
    }
}