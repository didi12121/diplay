package com.baidu.carlife.sdk.internal.transport.communicator

import android.bluetooth.BluetoothSocket
import android.util.Log
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.blockRead
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothCommunicator(private val socket: BluetoothSocket,
                            private val callbacks: Communicator.Callbacks? = null): Communicator {
    private var isTerminated = AtomicBoolean(false)

    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    override fun write(message: CarLifeMessage) {
        if (isTerminated.get()) {
            throw IOException("socket has been closed")
        }

        try {
            output.write(message.body, 0, message.size)
            Logger.d(Constants.TAG, "BluetoothCommunicator onSendMessage ", message)
        }
        catch (e: Exception) {
            // 如果发生异常，则主动调用terminate
            terminate()
            throw e
        }
    }

    override fun read(): CarLifeMessage {
        if (isTerminated.get()) {
            throw IOException("socket has been closed")
        }

        val message = CarLifeMessage.obtain(Constants.MSG_CHANNEL_CMD)
        try {
            input.blockRead(message.body, 0, message.commandSize)
            message.resizeBuffer(message.size)
            input.blockRead(message.body, message.commandSize, message.payloadSize)
            Logger.d(Constants.TAG, "BluetoothCommunicator onReceiveMessage ", message)
            return message
        }
        catch (e: Exception) {
            // 如果出现异常，需要自己回收，并同时把异常重新抛出
            message.recycle()
            // 如果发生异常，则主动调用terminate
            terminate()
            throw e
        }
    }

    override fun terminate() {
        if (!isTerminated.getAndSet(true)) {
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.d(Constants.TAG, "BluetoothCommunicator terminate exception: ", e)
            }
            callbacks?.onTerminated(Constants.MSG_CHANNEL_CMD)
            Logger.d(Constants.TAG, "BluetoothCommunicator terminated ", this)
        }
    }
}