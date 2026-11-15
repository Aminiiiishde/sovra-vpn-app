package com.sovra.vpn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.sovra.vpn.core.ConfigGenerator
import com.sovra.vpn.core.SovraVpnService
import com.sovra.vpn.data.ServerRepository

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var isConnected = false
    private var pendingConnect = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && pendingConnect) {
            doStartVpn()
        }
        pendingConnect = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity full screen dark cyberpunk
        window.statusBarColor = android.graphics.Color.parseColor("#03050C")
        window.navigationBarColor = android.graphics.Color.parseColor("#03050C")

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#03050C"))
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            addJavascriptInterface(WebAppBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    syncServersToWeb()
                }
            }
            webChromeClient = WebChromeClient()
        }

        root.addView(webView)
        setContentView(root)

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun triggerHaptic() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(35)
        }
    }

    private fun syncServersToWeb() {
        val servers = ServerRepository.defaultServers
        val jsArray = StringBuilder("[")
        servers.forEachIndexed { i, s ->
            jsArray.append("['${s.countryCode}','${s.name}','${s.protocol}',${s.pingMs}]")
            if (i < servers.size - 1) jsArray.append(",")
        }
        jsArray.append("]")
        val script = "if(typeof SV !== 'undefined'){ SV = $jsArray; if(typeof draw === 'function') draw(); }"
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun prepareAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnect = true
            vpnPermissionLauncher.launch(intent)
        } else {
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        val config = ConfigGenerator.generateXrayConfig(
            serverAddress = "77.90.52.130",
            serverPort = 2052,
            uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd"
        )
        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_CONNECT
            putExtra(SovraVpnService.EXTRA_CONFIG_JSON, config)
        }
        startService(serviceIntent)
        isConnected = true
    }

    private fun doStopVpn() {
        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_DISCONNECT
        }
        startService(serviceIntent)
        isConnected = false
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun toggleConnect() {
            triggerHaptic()
            runOnUiThread {
                if (isConnected) {
                    doStopVpn()
                } else {
                    prepareAndConnect()
                }
            }
        }

        @JavascriptInterface
        fun selectServer(index: Int) {
            triggerHaptic()
        }

        @JavascriptInterface
        fun testAllPing() {
            triggerHaptic()
        }

        @JavascriptInterface
        fun submitOrder(planName: String, amount: Long) {
            triggerHaptic()
        }
    }
}
