package com.zeroping.mirror

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zeroping.mirror.core.capture.ScreenCaptureService
import com.zeroping.mirror.core.obs.KillcamLogger

class MainActivity : Activity() {
    private lateinit var badgeView: TextView
    private lateinit var urlTextView: TextView
    private lateinit var urlCard: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private var currentStreamUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(56, 72, 56, 56)
        }

        val titleView = TextView(this).apply {
            text = "ZeroPing Mirror"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F3F4F6"))
        }

        val subtitleView = TextView(this).apply {
            text = "Transmissão de tela em tempo real via Wi-Fi / WebRTC"
            textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 8, 0, 48)
        }

        badgeView = TextView(this).apply {
            text = "●  OFFLINE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#9CA3AF"))
            background = createRoundedBg("#1E222B", 24f)
            setPadding(32, 14, 32, 14)
        }
        urlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg("#181B22", 28f)
            setPadding(40, 36, 40, 36)
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 36, 0, 0) }
            layoutParams = params
        }

        val cardLabel = TextView(this).apply {
            text = "ENDEREÇO DE ACESSO (NAVEGADOR PC / TV)"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#6B7280"))
        }

        urlTextView = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 12, 0, 24)
        }

        val btnCopy = Button(this).apply {
            text = "Copiar Link"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#E5E7EB"))
            background = createRoundedBg("#262B36", 18f)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ZeroPing URL", currentStreamUrl))
                Toast.makeText(this@MainActivity, "Link copiado!", Toast.LENGTH_SHORT).show()
            }
        }

        urlCard.addView(cardLabel)
        urlCard.addView(urlTextView)
        urlCard.addView(btnCopy)

        btnStart = Button(this).apply {
            text = "Iniciar Transmissão"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedBg("#2563EB", 24f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 140
            ).apply { setMargins(0, 56, 0, 24) }
            layoutParams = params
            setOnClickListener {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 1337)
            }
        }

        btnStop = Button(this).apply {
            text = "Encerrar Transmissão"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor("#F87171"))
            background = createRoundedBg("#26171A", 24f)
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 132
            )
            layoutParams = params
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                })
                updateUiState(online = false)
            }
        }

        root.addView(titleView)
        root.addView(subtitleView)
        root.addView(badgeView)
        root.addView(urlCard)
        root.addView(btnStart)
        root.addView(btnStop)
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1337 && resultCode == RESULT_OK && data != null) {
            KillcamLogger.logInfo("MainActivity", "Permissao concedida. Iniciando servico...")
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
            badgeView.postDelayed({
                val ip = getLocalWifiIp()
                val token = ScreenCaptureService.activeToken ?: ""
                currentStreamUrl = "http://$ip:8080/?token=$token"
                updateUiState(online = true)
            }, 600)
        }
    }

    private fun updateUiState(online: Boolean) {
        if (online) {
            badgeView.text = "●  TRANSMITINDO (WI-FI)"
            badgeView.setTextColor(Color.parseColor("#34D399"))
            badgeView.background = createRoundedBg("#112922", 24f)
            urlTextView.text = currentStreamUrl
            urlCard.visibility = View.VISIBLE
            btnStart.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
        } else {
            badgeView.text = "●  OFFLINE"
            badgeView.setTextColor(Color.parseColor("#9CA3AF"))
            badgeView.background = createRoundedBg("#1E222B", 24f)
            urlCard.visibility = View.GONE
            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
        }
    }

    private fun createRoundedBg(hexColor: String, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = radiusDp
        }
    }

    @Suppress("DEPRECATION")
    private fun getLocalWifiIp(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }
}
