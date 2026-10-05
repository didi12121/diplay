package com.baidu.carlife.sdk.util.wifip2p

import android.net.wifi.p2p.WifiP2pManager
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

class WifiP2pOperationSequence(private val listener: WifiP2pManager.ActionListener)
    : WifiP2pManager.ActionListener {

    private val sequence = mutableListOf<WifiP2pOperation>()

    private val executing = AtomicBoolean(false)
    private var currentIndex = 0

    fun add(operation: WifiP2pOperation) {
        sequence.add(operation)
    }

    fun execute() {
        if (!executing.getAndSet(true)) {
            currentIndex = 0
            sequence[currentIndex].execute(this)
        }
    }

    override fun onSuccess() {
        ++currentIndex
        if (currentIndex == sequence.size) {
            // 全部执行成功
            executing.set(false)
            currentIndex = 0
            listener.onSuccess()
        }
        else {
            sequence[currentIndex].execute(this)
        }
    }

    override fun onFailure(reason: Int) {
        Logger.d(Constants.TAG, "WifiP2pOperationSequence onFailure ", sequence[currentIndex],
            ", currentIndex: ", currentIndex, ", reason: ", reason)

        executing.set(false)
        listener.onFailure(reason)
        currentIndex = 0
    }
}