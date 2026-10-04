package com.baidu.carlife.sdk.internal.protocol.encrypt

import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.AesEncryptor
import com.baidu.carlife.sdk.internal.RsaEncryptor
import com.baidu.carlife.sdk.util.Logger
import java.lang.Exception
import javax.crypto.Cipher

// if used in receiver, must pass a non null RSAEncryptor
class EncryptionTool(private var rsaEncryptor: RsaEncryptor? = null) {
    private var aesEncryptor: AesEncryptor? = null
    private val decryptExcludes = mutableSetOf<Int>()

    var encryptKey: String
        get() {
            // for sender
            if (rsaEncryptor == null || aesEncryptor == null) {
                throw RuntimeException("you must init before request encrypt key")
            }
            return rsaEncryptor!!.encryptToBase64(aesEncryptor!!.key)

        }
        set(value) {
            // for receive
            if (rsaEncryptor == null) {
                throw RuntimeException("you must pass a non null RSAEncryptor in constructor")
            }
            aesEncryptor = AesEncryptor.withKey(rsaEncryptor!!.decryptWithBase64(value))
            decryptExcludes.clear()
        }

    // for sender use
    fun prepare(publicKey: String) {
        rsaEncryptor = RsaEncryptor.withPublicKey(publicKey)
        aesEncryptor = AesEncryptor.withRandomKey()
    }

    fun reset(isSender: Boolean) {
        if (isSender) {
            rsaEncryptor = null
            aesEncryptor = null
        }
        else {
            aesEncryptor = null
        }
    }

    fun encrypt(message: CarLifeMessage): CarLifeMessage {
        if (aesEncryptor == null) {
            throw RuntimeException("encryptor does not initialized properly")
        }

        val encryptMessage = message.copy()
        val payloadSize = message.payloadSize
        val encryptSize = aesEncryptor!!.outputSize(Cipher.ENCRYPT_MODE, payloadSize)
        encryptMessage.payloadSize = encryptSize
        encryptMessage.resizeBuffer(encryptMessage.commandSize + encryptSize)
        aesEncryptor!!.encrypt(
            message.body,
            message.commandSize,
            message.payloadSize,
            encryptMessage.body,
            encryptMessage.commandSize)
        return encryptMessage
    }

    fun decrypt(message: CarLifeMessage): CarLifeMessage {
        val serviceType = message.serviceType
        if (aesEncryptor == null) {
            Logger.e(Constants.TAG, "EncryptionTool encryptor does not initialized properly")
            return message
        }
        // 如果解密失败过就不再强行解密了
        if (decryptExcludes.contains(serviceType)) {
            return message
        }

        val decryptMessage = message.copy()
        decryptMessage.resizeBuffer(message.size)
        try {
            val decryptSize = aesEncryptor!!.decrypt(
                message.body,
                message.commandSize,
                message.payloadSize,
                decryptMessage.body,
                decryptMessage.commandSize
            )
            decryptMessage.payloadSize = decryptSize
            return decryptMessage
        }
        catch (e: Exception) {
            Logger.e(Constants.TAG, "EncryptionTool decrypt exception ", e, "  ", serviceType)
            // 解密失败，加入例外列表
            decryptExcludes.add(serviceType)
            decryptMessage.recycle()
            return message
        }
    }
}