package com.baidu.carlife.sdk.util

import android.content.Context
import android.graphics.Point
import android.view.WindowManager

val availableProcessors by lazy {
    return@lazy Runtime.getRuntime().availableProcessors()
}

fun Context.screenSize(): Pair<Int, Int> {
    val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val point = Point()
    manager.defaultDisplay.getRealSize(point)
    return Pair(point.x, point.y)
}

fun Context.screenSizePortrait(): Pair<Int, Int> {
    val size = screenSize()
    return Pair(
        size.first.coerceAtMost(size.second),
        size.first.coerceAtLeast(size.second)
    )
}

fun Context.screenSizeHorizontal(): Pair<Int, Int> {
    val size = screenSize()
    return Pair(
        size.first.coerceAtLeast(size.second),
        size.first.coerceAtMost(size.second)
    )
}