package com.baidu.carlife.sdk.receiver

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface ConnectProgressListener {
    /**
     * 当前连接进度
     * @param progress 0 -> 100
     */
    fun onProgress(progress: Int)
}