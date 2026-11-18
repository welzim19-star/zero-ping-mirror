package com.zeroping.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.zeroping.mirror.core.capture.ScreenCaptureService
import com.zeroping.mirror.core.obs.KillcamLogger

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        statusView = TextView(this).apply {
            text = "Zero-Ping Mirror Lobby\nStatus: Offline\n"
            textSize = 16f
        }
        val btnStart = Button(this).apply {
            text = "RUSH B (INICIAR STREAM)"
            setOnClickListener {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 1337)
            }
        }
        val btnStop = Button(this).apply {
            text = "GG / ENCERRAR STREAM"
            setOnClickListener {
                val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                statusView.text = "Zero-Ping Mirror Lobby\nStatus: Offline\n"
            }
        }
        layout.addView(statusView)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1337 && resultCode == RESULT_OK && data != null) {
            KillcamLogger.logInfo("MainActivity", "Permissao concedida. Spawnando Foreground Service...")
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusView.postDelayed({
                val ip = getLocalWifiIp()
                val token = ScreenCaptureService.activeToken ?: "gerando..."
                statusView.text = "Zero-Ping Mirror Lobby\nStatus: ONLINE\nURL: http://$ip:8080/?token=$token\n"
            }, 600)
        }
    }

    @Suppress("DEPRECATION")
    private fun getLocalWifiIp(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }
}
