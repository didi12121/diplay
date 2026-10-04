package com.baidu.carlife.sdk.internal.protocol.decoder

class PayloadDecoder<T>(private val mapper: (ByteArray, Int, Int) -> T) {
    fun decode(payload: ByteArray, offset: Int, length: Int): T {
        return mapper.invoke(payload, offset, length)
    }
}

