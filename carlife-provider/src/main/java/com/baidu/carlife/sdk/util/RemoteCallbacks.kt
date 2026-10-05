package com.baidu.carlife.sdk.util

import android.os.IInterface
import android.os.RemoteCallbackList
import android.os.RemoteException

fun <T: IInterface> RemoteCallbackList<T>.broadcastAll(listener: (T)->Boolean) {
    val count = this.beginBroadcast()
    for (i in 0 until count) {
        try {
            if (!listener(this.getBroadcastItem(i))) {
                break
            }
        } catch (e: RemoteException) {
        }
    }
    this.finishBroadcast();
}