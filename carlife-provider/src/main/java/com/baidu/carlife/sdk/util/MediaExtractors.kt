package com.baidu.carlife.sdk.util

import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * 选择对应类型的轨道
 */
fun MediaExtractor.selectTrack(mimeType: String): Int {
    for (index in 0 until trackCount) {
        val format: MediaFormat = getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime?.startsWith(mimeType) == true) {
            selectTrack(index)
            return index
        }
    }
    return -1
}