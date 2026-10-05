package com.baidu.carlife.sdk.internal.transport

import android.content.Intent
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage

abstract class ProtocolTransport(
    protected val context: CarLifeContext,
    protected val connectionListener: ConnectionListener? = null
) {

    interface ConnectionListener {
        fun onConnectionAttached(transport: ProtocolTransport) {}
        fun onConnectionDetached(transport: ProtocolTransport) {}
    }
    /**
     * 连接服务端
     * 当transport作为client端时使用
     * 比如车机端，或者度小盒作为车机端连接手机时使用
     */
    abstract fun connect()


    /**
     * 准备就绪，监听usb广播
     * 当transport作Service端时使用
     * 比如CarLife App
     */
    abstract fun ready()

    /**
     * 等待连接
     * 当transport作Service端时使用
     * 比如CarLife App
     */
    abstract fun listen()

    abstract fun terminate()

    /**
     * 发送消息
     */
    abstract fun write(message: CarLifeMessage)

    abstract fun read(): CarLifeMessage

    open fun onNewIntent(intent: Intent) { }

    fun onConnectionAttached() {
        connectionListener?.onConnectionAttached(this)
    }

    fun onConnectionDetached() {
        connectionListener?.onConnectionDetached(this)
    }
}