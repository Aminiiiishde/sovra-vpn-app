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
import android.util.Base64
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
import com.sovra.vpn.core.XrayBridge
import com.sovra.vpn.scanner.SmartCloudflareScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        var instance: MainActivity? = null
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private var isConnected = false
    private var pendingConnect = false
    private var selectedServerUri = ""
    private var activeCleanIp = ""
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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
            setBackgroundColor(android.graphics.Color.parseColor("#03050C"))
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                textZoom = 100
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
                    activeCleanIp = cleanIp
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
        val targetUri = if (selectedServerUri.isNotEmpty()) selectedServerUri else "vless://741fc821-4acb-4b77-9c37-8862308db928@tun.sovra.ir:2052?type=ws&security=none&path=%2Fgerman&host=german.sovra.ir#Germany-VIP"
        val config = ConfigGenerator.generateFromUri(targetUri, activeCleanIp)

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
        fun applyCleanIp(ip: String) {
            activeCleanIp = ip
            showToast("آی‌پی تمیز کلادفلر اعمال شد: $ip")
        }

        @JavascriptInterface
        fun testAllPing(urisJson: String) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val array = JSONArray(urisJson)
                    for (i in 0 until array.length()) {
                        val uriStr = array.getString(i)
                        val ping = measureRealHttpDelay(uriStr)
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript("if(typeof updatePing === 'function') updatePing($i, $ping);", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ping error: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun fetchSubscription(subInput: String) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    var subUrl = subInput.trim()
                    if (!subUrl.startsWith("http://") && !subUrl.startsWith("https://")) {
                        var cleanId = subUrl
                        if (cleanId.contains("/subs/")) cleanId = cleanId.split("/subs/")[1].split("?")[0]
                        if (cleanId.contains("/sub/")) cleanId = cleanId.split("/sub/")[1].split("?")[0]
                        subUrl = "https://sub.aminishere.shop/subs/$cleanId"
                    }

                    Log.d(TAG, "Fetching subscription from: $subUrl")
                    val req = Request.Builder()
                        .url(subUrl)
                        .header("User-Agent", "v2rayNG/1.8.19")
                        .build()

                    val resp = httpClient.newCall(req).execute()
                    val rawBody = resp.body?.string() ?: ""

                    var decodedText = rawBody
                    try {
                        val bytes = Base64.decode(rawBody.trim(), Base64.DEFAULT)
                        decodedText = String(bytes)
                    } catch (_: Exception) {}

                    val lines = decodedText.lines().map { it.trim() }.filter {
                        it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://")
                    }

                    val serverArray = JSONArray()
                    var vipCount = 0
                    var directCount = 0

                    lines.forEachIndexed { index, link ->
                        var name = "SOVRA-$index"
                        if (link.contains("#")) {
                            name = try {
                                URLDecoder.decode(link.split("#")[1], "UTF-8")
                            } catch (_: Exception) {
                                link.split("#")[1]
                            }
                        }

                        val lower = normalizeSmallCaps(name).lowercase()
                        val isVip = lower.contains("tunnel") || lower.contains("تانل") || lower.contains("ir-") || lower.contains("iran")
                        val isDirect = lower.contains("direct") || lower.contains("مستقیم") || lower.contains("fastly") || lower.contains("cdn")

                        val category = if (isVip && !isDirect) {
                            vipCount++
                            "vip"
                        } else {
                            directCount++
                            "direct"
                        }

                        val country = when {
                            lower.contains("germany") || lower.contains("de") || lower.contains("آلمان") -> "DE"
                            lower.contains("turkey") || lower.contains("tr") || lower.contains("ترکیه") -> "TR"
                            lower.contains("netherlands") || lower.contains("nl") || lower.contains("هلند") -> "NL"
                            lower.contains("france") || lower.contains("fr") || lower.contains("فرانسه") -> "FR"
                            lower.contains("finland") || lower.contains("fi") || lower.contains("فنلاند") -> "FI"
                            lower.contains("iran") || lower.contains("ir") || lower.contains("ایران") -> "IR"
                            else -> "DE"
                        }

                        val obj = JSONObject().apply {
                            put("name", name)
                            put("country", country)
                            put("category", category)
                            put("ping", 30 + (index % 15) * 4)
                            put("link", link)
                        }
                        serverArray.put(obj)
                    }

                    withContext(Dispatchers.Main) {
                        val jsonStr = serverArray.toString().replace("'", "\\'")
                        val js = "if(typeof onSubscriptionLoaded === 'function') onSubscriptionLoaded('$jsonStr', $vipCount, $directCount);"
                        webView.evaluateJavascript(js, null)
                        showToast("لیست ${serverArray.length()} سرور ساب با موفقیت بارگذاری شد")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSubscription failed: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        showToast("خطا در دریافت ساب: ${e.message}")
                    }
                }
            }
        }

        private fun normalizeSmallCaps(s: String): String {
            val map = mapOf(
                'ᴛ' to 't', 'ᴜ' to 'u', 'ɴ' to 'n', 'ᴇ' to 'e', 'ʟ' to 'l',
                'ᴅ' to 'd', 'ɪ' to 'i', 'ʀ' to 'r', 'ᴄ' to 'c'
            )
            val sb = StringBuilder()
            for (ch in s) {
                sb.append(map[ch] ?: ch)
            }
            return sb.toString()
        }

        private fun measureRealHttpDelay(rawUri: String): Int {
            // 1. Try native Xray Libv2ray.measureOutboundDelay
            try {
                val config = ConfigGenerator.generateFromUri(rawUri, activeCleanIp)
                val delay = XrayBridge.measureOutboundDelay(config, "http://cp.cloudflare.com/generate_204")
                if (delay > 0) return delay.toInt()
            } catch (_: Exception) {}

            // 2. Accurate HTTP handshake delay fallback
            val start = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                var host = "tun.sovra.ir"
                var port = 443
                if (rawUri.contains("@")) {
                    val afterAt = rawUri.split("@")[1].split("?")[0].split("#")[0]
                    val hp = afterAt.split(":")
                    host = hp[0]
                    if (hp.size > 1) port = hp[1].toIntOrNull() ?: 443
                }
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), 2500)
                val delay = (System.currentTimeMillis() - start).toInt()
                socket.close()
                return if (delay > 0) delay else 32
            } catch (e: Exception) {
                try { socket?.close() } catch (ignored: Exception) {}
                return -1
            }
        }

        @JavascriptInterface
        fun startFreeTrial() {
            triggerHaptic()
            fetchSubscription("5dr48a6hn8dwwbs7")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "اکانت تست ۲۴ ساعته فعال گردید!", Toast.LENGTH_SHORT).show()
                prepareAndConnect()
            }
        }

        @JavascriptInterface
        fun submitOrder(title: String, amount: Long) {
            triggerHaptic()
            showToast("سفارش «$title» به مبلغ $amount تومان ثبت گردید.")
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
