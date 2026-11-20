package com.sovra.vpn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.sovra.vpn.core.ConfigGenerator
import com.sovra.vpn.core.SovraVpnService
import com.sovra.vpn.data.ServerRepository
import com.sovra.vpn.model.ServerConfig
import com.sovra.vpn.scanner.SmartCloudflareScanner
import com.sovra.vpn.security.DeviceFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {

    companion object {
        var instance: MainActivity? = null
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private var isConnected = false
    private var pendingConnect = false
    private var selectedServerUri = ""
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    fun getWebView(): WebView? = if (::webView.isInitialized) webView else null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && pendingConnect) {
            doStartVpn()
        }
        pendingConnect = false
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            fileUploadCallback?.onReceiveValue(arrayOf(uri))
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

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
                    runCleanIpScanner()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    filePickerLauncher.launch("image/*")
                    return true
                }
            }
        }

        root.addView(webView)
        setContentView(root)

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    private fun triggerHaptic() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(35)
        }
    }

    private fun runCleanIpScanner() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val res = SmartCloudflareScanner.scanBestIp()
                val cleanIp = res?.ip ?: ""
                if (cleanIp.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("if(typeof onCleanIpFound === 'function') onCleanIpFound('$cleanIp');", null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scanner error: ${e.message}")
            }
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
        val config = if (selectedServerUri.isNotEmpty()) {
            ConfigGenerator.generateFromUri(selectedServerUri)
        } else {
            ConfigGenerator.generateFromUri("vless://741fc821-4acb-4b77-9c37-8862308db928@tun.sovra.ir:2052?type=ws&security=none&path=%2Fgerman&host=german.sovra.ir#Germany-VIP")
        }

        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_CONNECT
            putExtra(SovraVpnService.EXTRA_CONFIG_JSON, config)
        }
        startService(serviceIntent)
        isConnected = true

        runOnUiThread {
            webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(true);", null)
        }
    }

    private fun doStopVpn() {
        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_DISCONNECT
        }
        startService(serviceIntent)
        isConnected = false

        runOnUiThread {
            webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(false);", null)
        }
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
        fun connectWithUri(uri: String) {
            triggerHaptic()
            selectedServerUri = uri
            runOnUiThread {
                if (isConnected) {
                    doStopVpn()
                }
                prepareAndConnect()
            }
        }

        @JavascriptInterface
        fun selectServerUri(uri: String) {
            triggerHaptic()
            selectedServerUri = uri
        }

        @JavascriptInterface
        fun testAllPing(urisJson: String) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val array = org.json.JSONArray(urisJson)
                    for (i in 0 until array.length()) {
                        val uriStr = array.getString(i)
                        val ping = measureRealPing(uriStr)
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript("if(typeof updatePing === 'function') updatePing($i, $ping);", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ping error: ${e.message}")
                }
            }
        }

        private fun measureRealPing(rawUri: String): Int {
            val start = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                // Parse host and port from URI
                var host = "tun.sovra.ir"
                var port = 443
                if (rawUri.contains("@")) {
                    val afterAt = rawUri.split("@")[1].split("?")[0].split("#")[0]
                    val hp = afterAt.split(":")
                    host = hp[0]
                    if (hp.size > 1) port = hp[1].toIntOrNull() ?: 443
                }
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2000)
                val delay = (System.currentTimeMillis() - start).toInt()
                socket.close()
                return if (delay > 0) delay else 35
            } catch (e: Exception) {
                try { socket?.close() } catch (ignored: Exception) {}
                return -1
            }
        }

        @JavascriptInterface
        fun startFreeTrial() {
            triggerHaptic()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "اشتراک تست رایگان فعال گردید!", Toast.LENGTH_SHORT).show()
                prepareAndConnect()
            }
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
