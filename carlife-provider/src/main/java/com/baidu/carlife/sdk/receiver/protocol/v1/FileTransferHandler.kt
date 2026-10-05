package com.baidu.carlife.sdk.receiver.protocol.v1

import com.baidu.carlife.protobuf.CarlifeFileTransferBeginProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.receiver.FileTransferListener
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class FileTransferHandler(context: CarLifeContext,
                          private val listener: FileTransferListener
): TransportListener {
    companion object {
        const val FILE_TRANSFERRING_NAME = "CarLifeTransferringFile.tmp"
    }

    private var isTransferring = false

    private var totalSize = 0L
    private var transferredSize = 0L

    private var transferringFile = File(context.cacheDir, FILE_TRANSFERRING_NAME)
    private var transferringStream: OutputStream? = null

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            ServiceTypes.MSG_DATA_MD_TRANSFER_START -> onTransferBegin(context, message)
            ServiceTypes.MSG_DATA_MD_TRANSFER_SEND -> onTransferData(context, message)
            ServiceTypes.MSG_DATA_MD_TRANSFER_END -> onTransferEnd(context, message)
        }
        return false
    }

    private fun onTransferBegin(context: CarLifeContext, message: CarLifeMessage) {
        val fileInfo = message.protoPayload as? CarlifeFileTransferBeginProto.CarlifeFileTransferBegin
        fileInfo?.let {
            isTransferring = true
            totalSize = it.fileSize
            transferredSize = 0

            if (transferringFile.exists()) {
                transferringFile.delete()
            }
            // 先close文件流
            transferringStream?.close()
            transferringStream = FileOutputStream(transferringFile)
        }
    }

    private fun onTransferData(context: CarLifeContext, message: CarLifeMessage) {
        if (isTransferring) {
            // TODO 可能需要创建单独的线程处理文件写入，避免拖慢数据传输
            val packetSize = message.payloadSize
            transferredSize += packetSize
            transferringStream?.write(message.body, message.commandSize, packetSize)
        }
    }

    private fun onTransferEnd(context: CarLifeContext, message: CarLifeMessage) {
        if (totalSize == transferredSize) {
            // 进行尺寸校验，如果不对，不回调
            listener.onReceiveFile(transferringFile)
        }

        resetState()
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        resetState()
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        resetState()
    }

    private fun resetState() {
        transferredSize = 0
        totalSize = 0L
        isTransferring = false

        transferringStream?.let {
            it.close()
            transferringStream = null
        }
    }
}