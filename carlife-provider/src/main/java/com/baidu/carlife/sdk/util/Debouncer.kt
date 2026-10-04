package com.baidu.carlife.sdk.util

import android.os.SystemClock

class Debouncer(private val cycle: Int = 15) {
    @Volatile
    private var tick = 0L

    fun reset() {
        tick = 0L
    }

    fun filter(): Boolean {
        val interval = SystemClock.uptimeMillis() - tick
        if (tick != 0L && interval < cycle) {
            return false
        }
        tick = SystemClock.uptimeMillis()
        return true
    }
}