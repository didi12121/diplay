package com.baidu.carlife.sdk.util

fun ByteArray.toShort(offset: Int = 0): Int {
    return (this[offset].toInt() and 0xFF shl 8) or
            (this[offset + 1].toInt() and 0xFF)
}

fun ByteArray.toInt(offset: Int = 0): Int {
    return (this[offset].toInt() and 0xFF shl 24) or
            (this[offset + 1].toInt() and 0xFF shl 16) or
            (this[offset + 2].toInt() and 0xFF shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}

fun Int.fillByteArray(array: ByteArray, offset: Int = 0, byteSize: Int = 4) {
    var shift = (byteSize - 1) * 8
    for (i in 0 until byteSize) {
        array[offset + i] = (this shr shift and 0xFF).toByte()
        shift -= 8
    }
}