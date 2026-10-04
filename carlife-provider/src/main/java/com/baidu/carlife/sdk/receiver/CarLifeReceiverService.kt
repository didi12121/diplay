package com.baidu.carlife.sdk.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.R
import com.baidu.carlife.sdk.util.Logger

class CarLifeReceiverService : Service(), SurfaceRequestCallback {
    companion object {
        const val CHANNEL_ID = "CARLIFE_RECEIVER_SERVICE"
        const val CHANNEL_NAME = "CARLIFE_RECEIVER_SERVICE"
        const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()

        CarLife.receiver().setSurfaceRequestCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        CarLife.receiver().setSurfaceRequestCallback(null)
    }

    override fun requestSurface(width: Int, height: Int) {
        val intent = Intent(this, CarLife.receiver().activityClass)
        intent.putExtra(SurfaceRequestCallback.KEY_SURFACE_WIDTH, width)
        intent.putExtra(SurfaceRequestCallback.KEY_SURFACE_HEIGHT, height)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        Logger.d(Constants.TAG, "CarLifeReceiverService start ", CarLife.receiver().activityClass)
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("百度CarLife+")
                .setContentText("“百度CarLife+”投屏服务正在运行")
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}