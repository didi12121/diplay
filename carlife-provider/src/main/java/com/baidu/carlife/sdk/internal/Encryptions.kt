package com.baidu.carlife.sdk.internal

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.Key
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class RsaEncryptor(private val key: Key, private val transformation: String) {
    companion object {
        // public key, base64 encoded
        // throw exceptions
        fun withPublicKey(
            key: String,
            transformation: String = "RSA/ECB/PKCS1Padding"
        ): RsaEncryptor {
            val keyBytes: ByteArray = Base64.decode(key, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
            return RsaEncryptor(
                publicKey,
                transformation
            )
        }

        fun withPrivateKey(
            privateKey: String,
            transformation: String = "RSA/ECB/PKCS1Padding"
        ): RsaEncryptor {

            return withPrivateKey(
                KeyFactory
                    .getInstance("RSA")
                    .generatePrivate(
                        PKCS8EncodedKeySpec(
                            Base64.decode(
                                privateKey,
                                Base64.NO_WRAP
                            )
                        )
                    ),
                transformation
            )
        }

        fun withPrivateKey(
            privateKey: PrivateKey,
            transformation: String = "RSA/ECB/PKCS1Padding"
        ): RsaEncryptor {
            return RsaEncryptor(
                privateKey,
                transformation
            )
        }
    }

    fun encrypt(data: String): ByteArray {
        return encrypt(data.toByteArray(charset("UTF-8")))
    }

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.update(data)
        return cipher.doFinal()
    }

    fun encryptSpilt(data: String): String {
        return encryptSpilt(data.toByteArray(Charsets.UTF_8))
    }

    // encryptMaxSize 由公钥长度和填充方式决定
    fun encryptSpilt(data: ByteArray, encryptMaxSize: Int = 117): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val outS = ByteArrayOutputStream()
        var offset = 0
        val size = data.size
        while (offset < size) {
            val length = min(encryptMaxSize, size - offset)
            outS.write(cipher.doFinal(data, offset, length))
            offset += length
        }
        return Base64.encode(outS.toByteArray(), Base64.NO_WRAP).toString(Charsets.UTF_8)
    }

    fun encryptToBase64(data: String): String {
        val encryptData = encrypt(data)
        return Base64.encodeToString(encryptData, Base64.NO_WRAP)
    }

    fun encryptToBase64(data: ByteArray): String {
        val encryptData = encrypt(data)
        return Base64.encodeToString(encryptData, Base64.NO_WRAP)
    }

    fun decryptWithBase64(data: String): ByteArray {
        return decrypt(Base64.decode(data, Base64.NO_WRAP))
    }

    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key)
        cipher.update(data)
        return cipher.doFinal()
    }


    fun decryptSpilt(data: ByteArray, encryptMaxSize: Int = 128): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key)
        val outS = ByteArrayOutputStream()
        var offset = 0
        val size = data.size
        while (offset < size) {
            val length = min(encryptMaxSize, size - offset)
            outS.write(cipher.doFinal(data, offset, length))
            offset += length
        }
        return outS.toByteArray()
    }
}

class AesEncryptor(val key: ByteArray) {
    companion object {
        fun withRandomKey(): AesEncryptor {
            val randomKey =
                UUID.randomUUID().toString().substring(0, 16).toByteArray(charset("UTF-8"))
            return AesEncryptor(
                randomKey
            )
        }

        fun withKey(key: ByteArray): AesEncryptor {
            return AesEncryptor(key)
        }
    }

    private val encryptCipher: Cipher
    private val decryptCipher: Cipher

    init {
        val sk: SecretKey = SecretKeySpec(key, "AES")
        encryptCipher = Cipher.getInstance("AES")
        encryptCipher.init(Cipher.ENCRYPT_MODE, sk)

        decryptCipher = Cipher.getInstance("AES")
        decryptCipher.init(Cipher.DECRYPT_MODE, sk)
    }

    fun outputSize(mode: Int, inputLength: Int): Int {
        return when (mode) {
            Cipher.ENCRYPT_MODE -> encryptCipher.getOutputSize(inputLength)
            else -> decryptCipher.getOutputSize(inputLength)
        }
    }

    fun encrypt(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        return encryptCipher.doFinal(data, offset, length)
    }

    fun encrypt(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        output: ByteArray,
        outputOffset: Int
    ) {
        encryptCipher.doFinal(data, offset, length, output, outputOffset)
    }

    fun decrypt(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        return decryptCipher.doFinal(data, offset, length)
    }

    fun decrypt(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        output: ByteArray,
        outputOffset: Int
    ): Int {
        return decryptCipher.doFinal(data, offset, length, output, outputOffset)
    }
}