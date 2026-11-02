package com.zeroping.mirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.zeroping.mirror.core.capture.ScreenCaptureService
import com.zeroping.mirror.core.network.SignalingServer
import com.zeroping.mirror.core.network.WebRTCPipeline
import com.zeroping.mirror.core.obs.KillcamLogger

class MainActivity : Activity() {
    private val server = SignalingServer(8080)
    private var webRTCPipeline: WebRTCPipeline? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val textView = TextView(this).apply {
            text = "Zero-Ping Mirror Lobby\n"
            textSize = 18f
        }
        val btn = Button(this).apply {
            text = "RUSH B (INICIAR STREAM)"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 1337)
            }
        }
        layout.addView(textView)
        layout.addView(btn)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1337 && resultCode == RESULT_OK && data != null) {
            KillcamLogger.logInfo("MainActivity", "MediaProjection buffada com sucesso. GG!")
            server.start()
            webRTCPipeline = WebRTCPipeline(this, server)
            webRTCPipeline?.startCapture(data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
    }
}
