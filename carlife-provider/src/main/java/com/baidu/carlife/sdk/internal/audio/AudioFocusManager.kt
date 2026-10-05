package com.baidu.carlife.sdk.internal.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import java.util.*

@DoNotStrip
class AudioFocusManager(context: Context) {

    @DoNotStrip
    companion object {
        const val STREAM_MUSIC: Int = 1
        const val STREAM_NAVI: Int   = 2
        const val STREAM_VR: Int    = 3

        const val AUDIOFOCUS_REQUEST_FAILED = 0
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_REQUEST_DELAYED = 2
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 当前请求过焦点的请求集合
    private val requests
            = mutableMapOf<AudioManager.OnAudioFocusChangeListener, OnAudioFocusChangeListenerWrapper>()
    // 当前占用焦点的请求
    private var grantRequest: OnAudioFocusChangeListenerWrapper? = null

    // 被delay的请求集合
    private val delayedRequests = LinkedList<OnAudioFocusChangeListenerWrapper>()

    inner class OnAudioFocusChangeListenerWrapper(
            val stream: Int,
            val focusType: Int,
            val listener: AudioManager.OnAudioFocusChangeListener)
        : AudioManager.OnAudioFocusChangeListener {
        var audioFocusRequest: AudioFocusRequest? = null

        override fun onAudioFocusChange(focusChange: Int) {
            Logger.d(Constants.TAG, "AudioFocusManager onAudioFocusChange ", listener, " ", focusChange)
            listener.onAudioFocusChange(focusChange)
            if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                grantRequest = this
            }
            else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                abandonAudioFocus(listener)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other == null || other !is OnAudioFocusChangeListenerWrapper) {
                return false
            }
            return listener == other.listener
        }

        override fun hashCode(): Int {
            return listener.hashCode()
        }
    }

    @Synchronized
    fun requestAudioFocus(listener: AudioManager.OnAudioFocusChangeListener,
                          streamType: Int,
                          focusType: Int,
                          focusGrantDelayed: Boolean = false,
                          focusForceGrant: Boolean = false): Int {
        grantRequest?.let {
            if (it.listener == listener) {
                // 如果是同一个，直接返回
                return AUDIOFOCUS_REQUEST_GRANTED
            }
            // 当前已经获取到焦点，并且优先级大于待请求的
            if (it.stream > streamType && !focusForceGrant) {
                if (focusGrantDelayed) {
                    val wrapper = OnAudioFocusChangeListenerWrapper(streamType, focusType, listener)
                    enqueueDelayedRequest(wrapper)
                    Logger.d(Constants.TAG,
                            "AudioFocusManager requestAudioFocus ", listener, " ", AUDIOFOCUS_REQUEST_DELAYED)
                    return AUDIOFOCUS_REQUEST_DELAYED
                }
                Logger.d(Constants.TAG,
                        "AudioFocusManager requestAudioFocus ", listener, " ", AUDIOFOCUS_REQUEST_FAILED)
                return AUDIOFOCUS_REQUEST_FAILED
            }
        }
        val wrapper = OnAudioFocusChangeListenerWrapper(streamType, focusType, listener)
        return doSystemRequestAudioFocus(wrapper)
    }

    @Synchronized
    fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) {
        Logger.d(Constants.TAG, "AudioFocusManager abandonAudioFocus")
        val request = requests.remove(listener)
        if (request != null) {
            doSystemAbandonAudioFocus(request)

            if (delayedRequests.isNotEmpty()) {
                val delayedRequest = delayedRequests.pollFirst()!!
                val result = doSystemRequestAudioFocus(delayedRequest)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    delayedRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                }
                else {
                    delayedRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                }
            }
        }
        else {
            removeDelayedRequest(listener)
        }
    }

    private fun doSystemRequestAudioFocus(listener: OnAudioFocusChangeListenerWrapper): Int {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val focusRequest: AudioFocusRequest = AudioFocusRequest
                    .Builder(listener.focusType)
                    .setAudioAttributes(AudioAttributes
                        .Builder()
                        .setUsage(getUsage(listener.stream))
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            listener.audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                listener.focusType)
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            grantRequest = listener
            // 缓存
            requests[listener.listener] = listener
        }
        Logger.d(Constants.TAG,
                "AudioFocusManager requestAudioFocus ", listener.listener, " ", result)
        return result
    }

    private fun doSystemAbandonAudioFocus(listener: OnAudioFocusChangeListenerWrapper) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                listener.audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    return
                }
            }
            audioManager.abandonAudioFocus(listener)
        }
        finally {
            if (grantRequest == listener) {
                grantRequest = null
            }
        }
    }

    private fun enqueueDelayedRequest(request: OnAudioFocusChangeListenerWrapper) {
        // 如果有相同的，直接返回
        if (delayedRequests.contains(request)) {
            return
        }

        if (delayedRequests.isEmpty()) {
            delayedRequests.add(request)
        }
        else {
            val iterator = delayedRequests.listIterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (request.stream == next.stream || !iterator.hasNext()) {
                    // 相等插入到后边
                    iterator.add(request)
                } else if (request.stream > next.stream) {
                    iterator.set(request)
                    iterator.add(next)
                }
            }
        }
    }

    private fun removeDelayedRequest(listener: AudioManager.OnAudioFocusChangeListener) {
        val iterator = delayedRequests.listIterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.listener == listener) {
                iterator.remove()
                return
            }
        }
    }

    private fun getUsage(stream:Int) = when (stream) {
        STREAM_VR, STREAM_MUSIC -> AudioAttributes.USAGE_MEDIA
        STREAM_NAVI -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        else -> AudioAttributes.USAGE_MEDIA
    }
}