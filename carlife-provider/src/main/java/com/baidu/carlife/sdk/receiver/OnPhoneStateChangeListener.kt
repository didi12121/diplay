package com.baidu.carlife.sdk.receiver

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface OnPhoneStateChangeListener {

    /**
     * 电话当前状态
     */
    fun onStateChanged(isCalling: Boolean, isCallComing: Boolean)
}