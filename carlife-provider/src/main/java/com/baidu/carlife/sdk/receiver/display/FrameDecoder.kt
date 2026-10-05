package com.baidu.carlife.sdk.receiver.display

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.baidu.carlife.protobuf.CarlifeVideoEncoderInfoProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.CONFIG_SAVE_VIDEO_FILE
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.formatISO8601
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.experimental.and

class FrameDecoder(
    private val context: CarLifeContext,
    private val outSurface: Surface,
    private val encodeInfo: CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo
) : Thread() {
    companion object {
        const val WAIT_DECODE_TIMEOUT = 10000L
    }

    private val messageQueue = LinkedBlockingQueue<CarLifeMessage>()

    private val decoder: MediaCodec

    @Volatile
    private var isStopped = false

    @Volatile
    private var isFirstDataFrame = false;

    // for debug
    private var videoOutput: OutputStream? = null

    private val period: Int

    init {
        name = "FrameDecoder"
        decoder = createDecoder()
        period = 1000.div(encodeInfo.frameRate)

        if (context.getConfig(CONFIG_SAVE_VIDEO_FILE, false)) {
            val dir = File(context.cacheDir, "FrameDecoder")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            videoOutput = FileOutputStream(File(dir, Date().formatISO8601() + ".h264"))
        }

        start()
    }

    fun setSurface(surface: Surface) {
        if (!isStopped) {
            decoder.setOutputSurface(surface)
        }
    }

    fun feedFrame(message: CarLifeMessage) {
        if (!isStopped) {
            message.acquire()

            videoOutput?.write(message.body, message.commandSize, message.payloadSize)

            messageQueue.offer(message)
            Logger.v(Constants.TAG, "FrameDecoder messageQueue size ${messageQueue.size}")
            if (!isFirstDataFrame) {
                isFirstDataFrame = true
                Logger.d(Constants.TAG, "FrameDecoder isFirstDataFrame: $isFirstDataFrame")
            }
        }
    }

    fun release() {
        isStopped = true
        isFirstDataFrame = false
        Logger.d(Constants.TAG, "FrameDecoder call release, isFirstDataFrame: $isFirstDataFrame")
    }

    override fun run() {
        Logger.d(Constants.TAG, "FrameDecoder started ", this)
//        var frameCount = 0L
        val bufferInfo = MediaCodec.BufferInfo()

        decoder.start()

        var decodeStartTime: Long

        while (!isStopped) {
            decodeStartTime = System.currentTimeMillis()
            if (messageQueue.size > 60) {
                skipToKeyFrame()
            }
            val frame = messageQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

            var remaining = frame.payloadSize
            var consumed = 0

            while (!isStopped && remaining > 0) {
                var index: Int = decoder.dequeueInputBuffer(WAIT_DECODE_TIMEOUT)
                while (!isStopped && index < 0) {
                    // should not be here
                    flushBufferedQueue(bufferInfo)
                    index = decoder.dequeueInputBuffer(WAIT_DECODE_TIMEOUT)
                }

                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder.getInputBuffer(index)
                } else {
                    decoder.inputBuffers[index]
                }

                val offset = inputBuffer!!.position()
                val inputSize = inputBuffer.remaining().coerceAtMost(remaining)
                inputBuffer.put(frame.body, frame.commandSize + consumed, inputSize)
                decoder.queueInputBuffer(index, offset, inputSize, System.nanoTime() / 1000, 0)

                remaining -= inputSize
                consumed += inputSize
            }

            frame.recycle()

            flushBufferedQueue(bufferInfo)

//            ++frameCount

            val costTime = System.currentTimeMillis() - decodeStartTime
            if (costTime < period) {
                sleep(period - costTime)
                Logger.v(Constants.TAG, "FrameDecoder delay: ${period - costTime}")
            }
        }

        decoder.release()
        videoOutput?.let {
            it.flush()
            it.close()
        }

        while (messageQueue.isNotEmpty()) {
            messageQueue.poll()?.recycle()
        }

        Logger.d(Constants.TAG, "FrameDecoder released ", this)
    }

    private fun flushBufferedQueue(bufferInfo: MediaCodec.BufferInfo) {
        var index: Int = decoder.dequeueOutputBuffer(bufferInfo, 0)
        while (index >= 0) {
            decoder.releaseOutputBuffer(index, true)
            index = decoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun createDecoder(): MediaCodec {
        var decoder: MediaCodec? = null
        try {
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                encodeInfo.width,
                encodeInfo.height
            )
            decoder.configure(format, outSurface, null, 0)
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "FrameDecoder createEncoder exception: $e")
            decoder?.release()
            throw e
        }
        return decoder
    }

    private fun skipToKeyFrame() {
        while (true) {
            if (isKeyFrame(messageQueue.peek()?.body)) {
                Logger.v(Constants.TAG, "FrameDecoder message size ${messageQueue.peek()?.size}")
                messageQueue.poll()?.recycle()
            } else {
                Logger.v(
                    Constants.TAG,
                    "FrameDecoder message key size ${messageQueue.peek()?.size}"
                )
                break
            }
        }
        Logger.v(
            Constants.TAG,
            "FrameDecoder messageQueue after filter size ${messageQueue.size}"
        )
    }

    // 是否是关键帧
    private fun isKeyFrame(buffer: ByteArray?): Boolean {

        if (buffer == null || buffer.size < 5) {
            return false
        }

        // 00 00 00 01
        if (
            buffer[0] == 0.toByte()
            && buffer[1] == 0.toByte()
            && buffer[2] == 0.toByte()
            && buffer[3] == 1.toByte()
        ) {
            val nalType = buffer[4] and 0x1f
            if (nalType == 0x07.toByte() || nalType == 0x05.toByte() || nalType == 0x08.toByte()) {
                return true
            }
        }

        // 00 00 01
        if (
            buffer[0] == 0.toByte()
            && buffer[1] == 0.toByte()
            && buffer[2] == 1.toByte()
        ) {
            val nalType = buffer[3] and 0x1f
            if (nalType == 0x07.toByte() || nalType == 0x05.toByte() || nalType == 0x08.toByte()) {
                return true
            }
        }
        return false
    }

}