package com.baidu.carlife.sdk.receiver.protocol.v1

import android.util.Base64
import android.util.Log
import com.baidu.carlife.protobuf.*
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.*
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_CARLIFE_DATA_SUBSCRIBE_DONE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_HU_CARLIFE_DATA_SUBSCRIBE_DONE_RESPONSE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_HU_FEATURE_CONFIG_RESPONSE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_AES_KEY_SEND_REQUEST
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_CARLIFE_DATA_REQ
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_ENCRYPT_READY
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_ENCRYPT_READY_DONE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_FEATURE_CONFIG_REQUEST
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST
import com.baidu.carlife.sdk.internal.protocol.builder
import com.baidu.carlife.sdk.internal.protocol.encrypt.EncryptionTool
import com.baidu.carlife.sdk.internal.protocol.proto
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger
import java.lang.Exception
import java.security.PublicKey

class FeaturesHandler(private val encryptionTool: EncryptionTool?,
                      private val publicKey: PublicKey?): TransportListener {

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_MD_FEATURE_CONFIG_REQUEST -> handleFeatureRequest(context, message)
            MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST -> handleRequestPublicKey(context, message)
            MSG_CMD_MD_AES_KEY_SEND_REQUEST -> handleAESKeyRequest(context, message)
            MSG_CMD_MD_ENCRYPT_READY -> handleEncryptReady(context, message)
            MSG_CMD_MD_CARLIFE_DATA_REQ -> handleCarlifeDataRequest(context, message)
            MSG_CMD_CARLIFE_DATA_SUBSCRIBE_DONE -> handleMDCarlifeDataSubscribeDone(context, message)
        }
        return false
    }

    private fun handleFeatureRequest(context: CarLifeContext, message: CarLifeMessage) {
        val featureConfigs = message.protoPayload as? CarlifeFeatureConfigListProto.CarlifeFeatureConfigList

        for (feature in featureConfigs?.featureConfigList ?: listOf()) {
            context.setFeature(feature.key, feature.value, false)
        }

        val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_CMD_HU_FEATURE_CONFIG_RESPONSE)
        response.payload(context.listFeatures().builder()
            .setHuBtName(context.getConfig(CONFIG_HU_BT_NAME, ""))
            .setHuBtMAC(context.getConfig(CONFIG_HU_BT_MAC, ""))
            .build())
        context.postMessage(response)
    }

    // 以下方法与传输协议的加解密有关系
    private fun handleRequestPublicKey(context: CarLifeContext, message: CarLifeMessage) {
        if (encryptionTool == null || publicKey == null) {
            Logger.e(Constants.TAG, "can not request public key when CONTENT_ENCRYPTION not enabled")
            return
        }

        val publicKeyString = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_CMD_HU_RSA_PUBLIC_KEY_RESPONSE)
        response.payload(CarlifeHuRsaPublicKeyResponseProto.CarlifeHuRsaPublicKeyResponse
            .newBuilder()
            .setRsaPublicKey(publicKeyString)
            .build())
        context.postMessage(response)
    }

    private fun handleAESKeyRequest(context: CarLifeContext, message: CarLifeMessage) {
        if (encryptionTool == null || publicKey == null) {
            Logger.e(Constants.TAG, "can not handle aes key when CONTENT_ENCRYPTION not enabled")
            return
        }

        val aesKeyRequest = message.protoPayload as? CarlifeMdAesKeyRequestProto.CarlifeMdAesKeyRequest
        aesKeyRequest?.let {
            try {
                encryptionTool.encryptKey = it.aesKey
                var response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_HU_AES_REC_RESPONSE)
                context.postMessage(response)
            }
            catch (e: Exception) {
                Logger.e(Constants.TAG, "handleAESKeyRequest exception: ", e)
            }
        }
    }

    private fun handleEncryptReady(context: CarLifeContext, message: CarLifeMessage) {
        context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_MD_ENCRYPT_READY_DONE)
        if (encryptionTool != null && publicKey != null) {
            context.isEncryptionEnabled = true
        }
    }

    private fun handleCarlifeDataRequest(context: CarLifeContext, message: CarLifeMessage) {
        var message = CarLifeMessage.obtain(MSG_CHANNEL_CMD, MSG_CMD_CARLIFE_DATA_SUBSCRIBE)
        message.payload(context.listSubscriber().proto())
        context.postMessage(message)
    }

    private fun handleMDCarlifeDataSubscribeDone(context: CarLifeContext, message: CarLifeMessage) {
        context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_HU_CARLIFE_DATA_SUBSCRIBE_DONE_RESPONSE)
    }
}
