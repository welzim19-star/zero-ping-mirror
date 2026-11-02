package com.zeroping.mirror.core.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zeroping.mirror.core.obs.KillcamLogger

class ScreenCaptureService : Service() {
    override fun onCreate() {
        super.onCreate()
        KillcamLogger.logInfo("CaptureService", "Service em Foreground spawnado.")
        val channelId = "ZeroPingChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "ZeroPing Mirror", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Zero-Ping Mirror")
            .setContentText("Stream em andamento...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
            
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
