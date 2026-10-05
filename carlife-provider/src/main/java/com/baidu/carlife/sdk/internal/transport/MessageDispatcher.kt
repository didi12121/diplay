package com.baidu.carlife.sdk.internal.transport

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process.THREAD_PRIORITY_FOREGROUND
import android.util.Log
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.InvokeTimeoutMonitor
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

class MessageDispatcher(
    private val context: CarLifeContext,
    val transport: ProtocolTransport,
    private val monitorSendTimeout: Boolean = false
) : Thread(), Handler.Callback {

    private lateinit var sendHandler: Handler
    private val sender = HandlerThread("CarLife_MesssageSender", THREAD_PRIORITY_FOREGROUND)

    // 监控发送消息超时，如果发送超过3秒，则直接关闭连接
    private val sendTimeoutMonitor = InvokeTimeoutMonitor(Looper.getMainLooper(), 3000) {
        if (context.connectionType == CarLifeContext.CONNECTION_TYPE_AOA) {
            sendHandler.removeCallbacksAndMessages(null)
            context.onConnectionAttached()
        }
    }

    private var isTerminated = AtomicBoolean(false)

    init {
        name = "CarLife_MessageDispatcher"
        sender.start()
        sendHandler = Handler(sender.looper, this)
        if (monitorSendTimeout) {
            sendTimeoutMonitor.tick()
        }
    }

    override fun start() {
        if (state == State.NEW) {
            super.start()
        }
    }

    fun postMessage(message: CarLifeMessage) {
        sendHandler.sendMessage(Message.obtain(sendHandler, 0, message))
    }

    fun sendMessage(message: CarLifeMessage) {
        try {
            sendTimeoutMonitor.enter()
            if (message.supportEncrypt && context.isEncryptionEnabled) {
                // 需要先加密再传输，encrypt不是线程安全的，需要使用锁
                val encryptMessage = synchronized(this) {
                    context.encrypt(message)
                }

                try {
                    transport.write(encryptMessage)
                } finally {
                    encryptMessage.recycle()
                }
            } else {
                transport.write(message)
            }
            sendTimeoutMonitor.exit()
            // notify message send
            context.onSendMessage(context, message)
        } catch (e: Exception) {
            e.printStackTrace()
            if (!isTerminated.get()) {
                Logger.e(
                    Constants.TAG,
                    "MessageDispatcher handleMessage exception: " + Log.getStackTraceString(e)
                )
                terminate()
            }
        } finally {
            message.recycle()
        }
    }

    /**
     * this function only use for simulate receive message
     */
    fun dispatchMessage(message: CarLifeMessage) {
        context.onReceiveMessage(context, message)
    }

    fun terminate() {
        // 保证调用多次无副作用
        if (!isTerminated.getAndSet(true)) {
            sendTimeoutMonitor.release()

            transport.terminate()
            // 退出发送线程
            sender.quit()

            Logger.d(Constants.TAG, "MessageDispatcher terminate")
        }
    }

    // receive messages
    override fun run() {
        try {
            while (!isTerminated.get()) {
                val message = transport.read()
                try {
                    if (message.supportEncrypt && context.isEncryptionEnabled) {
                        // 需要先解密再分发
                        val decryptMessage = context.decrypt(message)
                        try {
                            context.onReceiveMessage(context, decryptMessage)
                        } finally {
                            if (decryptMessage !== message) {
                                decryptMessage.recycle()
                            }
                        }
                    } else {
                        // notify receive message
                        context.onReceiveMessage(context, message)
                    }
                } finally {
                    message.recycle()
                }
            }
        } catch (e: Exception) {
            if (!isTerminated.get()) {
                Logger.e(Constants.TAG, "MessageDispatcher run exception0: ", e)
            }
            Logger.e(Constants.TAG, "MessageDispatcher run exception1: ", e)
        }

        terminate()
    }

    // send message
    override fun handleMessage(msg: Message): Boolean {
        sendMessage(msg.obj as CarLifeMessage)
        return true
    }
}