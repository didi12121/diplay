package com.baidu.carlife.sdk.receiver.transport.aoa

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.internal.transport.ProtocolTransport
import com.baidu.carlife.sdk.util.Logger

class AOAProtocolTransport(context: CarLifeContext, connectionListener: ConnectionListener)
    : ProtocolTransport(context, connectionListener) {

    private var communicator: Communicator? = null
    private val scanner = UsbAccessoryScanner(context) {
        communicator = it
        onConnectionAttached()
    }

    override fun connect() {
        Logger.d(Constants.TAG, "AOAProtocolTransport connect")
        scanner.scan()
    }

    override fun ready() {
        Logger.d(Constants.TAG, "AOAProtocolTransport ready")
        scanner.ready()
    }

    override fun listen() {
    }

    override fun terminate() {
        // 如果被terminate，一般是因为通过其他方式连接成功了，这里处理一下，忽略USB事件的处理，
        // 车机端要保证只能保持一种连接方式
        scanner.stop()

        communicator?.let {
            it.terminate()
            communicator = null

            onConnectionDetached()
        }
        Logger.d(Constants.TAG, "AOAProtocolTransport terminate")
    }

    override fun write(message: CarLifeMessage) {
        communicator?.write(message)
    }

    override fun read(): CarLifeMessage {
        return communicator?.read() ?: throw IllegalStateException("read from unattached transport")
    }
}