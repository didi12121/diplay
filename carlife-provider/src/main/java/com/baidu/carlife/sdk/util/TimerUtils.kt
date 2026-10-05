package com.baidu.carlife.sdk.util

import java.util.*

object TimerUtils {
    private val timers = mutableMapOf<Runnable, Timer>()

    fun schedule(task: Runnable, delay: Long, period: Long) {
        // 如果已经有了，先cancel
        stop(task)

        val timer = Timer()
        timer.schedule(object: TimerTask() {
            override fun run() {
                task.run()
            }
        }, delay, period)
        timers[task] = timer
    }

    fun schedule(task: Runnable, delay: Long) {
        // 如果已经有了，先cancel
        stop(task)

        val timer = Timer()
        timer.schedule(object: TimerTask() {
            override fun run() {
                task.run()
            }
        }, delay)
        timers[task] = timer
    }

    fun stop(task: Runnable) {
        timers.remove(task)?.cancel()
    }
}