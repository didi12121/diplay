package com.baidu.carlife.sdk

import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
abstract class CarLifeModule: TransportListener {
    protected var listener: ModuleStateChangeListener? = null

    abstract val id: Int

    open fun reader(): StreamReader {
        throw UnsupportedOperationException("module $id don't support read as stream")
    }

    var state: Int = 0
        set(value) {
            val oldState = field
            if (field != value) {
                field = value
                onModuleStateChanged(value, oldState)
            }
        }

    fun setStateChangeListener(listener: ModuleStateChangeListener?) {
        this.listener = listener
    }

    open fun onModuleStateChanged(newState: Int, oldState: Int) {
        listener?.onModuleStateChanged(this)
    }
}