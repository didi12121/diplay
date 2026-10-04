package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
interface ConfigurationChangeListener {
    companion object {
        const val SCREEN_SIZE = 0
        const val ORIENTATION = 1
    }

    fun onConfigurationChanged(configuration: Int)
}