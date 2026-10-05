package com.baidu.carlife.sdk.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/**
 * 支持同步方法Timeout的工具类，精度为100ms，如果对精度要求非常高的话，不建议使用
 */
class InvokeTimeoutMonitor(looper: Looper,
                           timeout: Long,
                           private val onTimeout: ()->Unit) {
    companion object {
        const val TICK_INTERVAL = 100L
    }

    // timeout单位是毫秒，handler tick的时间单位是100ms，这里要转换
    private val threshold = timeout / TICK_INTERVAL

    private var isReleased: Boolean = false
    @Volatile
    private var monitoring: Boolean = false

    private var count = AtomicLong(0)

    private val handler = Handler(looper, Handler.Callback {
        if (!isReleased) {
            if (monitoring) {
                if (count.incrementAndGet() >= threshold) {
                    exit()
                    onTimeout()
                }
            }
            tick()
        }
        return@Callback true
    })

    fun tick() {
        handler.sendEmptyMessageDelayed(0, TICK_INTERVAL)
    }

    fun enter() {
        count.set(0)
        monitoring = true

    }

    fun exit() {
        monitoring = false
        count.set(0)
    }

    fun monitor(runnable: Runnable) {
        enter()
        runnable.run()
        exit()
    }

    fun release() {
        isReleased = true
        handler.removeMessages(0)
    }
}