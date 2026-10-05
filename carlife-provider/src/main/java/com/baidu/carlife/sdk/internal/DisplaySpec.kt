package com.baidu.carlife.sdk.internal

import android.content.Context
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
data class DisplaySpec (val context: Context, val width: Int, val height: Int, val frameRate: Int) {
    val densityDpi: Int = context.resources.displayMetrics.densityDpi

    val ratio: Float = width.toFloat() / height
}