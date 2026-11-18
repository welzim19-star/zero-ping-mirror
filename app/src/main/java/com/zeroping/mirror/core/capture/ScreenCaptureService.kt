package com.zeroping.mirror.core.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zeroping.mirror.core.network.SignalingServer
import com.zeroping.mirror.core.network.WebRTCPipeline
import com.zeroping.mirror.core.obs.KillcamLogger

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.zeroping.mirror.ACTION_START"
        const val ACTION_STOP = "com.zeroping.mirror.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        
        @Volatile
        var activeToken: String? = null
    }

    private var server: SignalingServer? = null
    private var pipeline: WebRTCPipeline? = null

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
            .setContentText("Stream de baixa latencia ativa na porta 8080")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START && pipeline == null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                val sigServer = SignalingServer(8080)
                sigServer.start()
                activeToken = sigServer.sessionToken
                server = sigServer

                pipeline = WebRTCPipeline(this, sigServer).apply {
                    startCapture(data)
                }
                KillcamLogger.logInfo("CaptureService", "Pipeline e Signaling Server ativos!")
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pipeline?.stop()
        server?.stop()
        pipeline = null
        server = null
        activeToken = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
