package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface StreamReader {
    /**
     * 判断流是否打开
     */
    fun isOpen(): Boolean

    /**
     * 打开传输流
     */
    fun open()

    /**
     * 关闭传输流
     */
    fun close()

    /**
     * 读取流
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeout: Long = 0): Int
}