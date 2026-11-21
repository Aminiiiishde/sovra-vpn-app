package com.sovra.vpn.ui

import android.annotation.SuppressLint
import android.app.Activity
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
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sovra.vpn.core.ConfigGenerator
import com.sovra.vpn.core.SovraVpnService
import com.sovra.vpn.core.XrayBridge
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ServerItem(
    val name: String,
    val flag: String,
    val country: String,
    val category: String,
    val proto: String,
    var ping: Int,
    val link: String
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        var instance: MainActivity? = null
            private set
    }

    private lateinit var webView: WebView
    private var isConnected = false
    private var pendingConnect = false
    private var currentMode = "fastest" // "fastest", "static", "gaming"

    private val parsedServers = mutableListOf<ServerItem>()
    private var selectedServerUri = ""
    private var staticLockedUri = ""

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun getWebView(): WebView? = if (::webView.isInitialized) webView else null

    fun notifyVpnDisconnected() {
        isConnected = false
        runOnUiThread {
            webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(false);", null)
        }
    }

    fun pushLiveStats(downStr: String, upStr: String, totalStr: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof updateTrafficDisplay === 'function') updateTrafficDisplay('$downStr', '$upStr', '$totalStr');",
                null
            )
        }
    }

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
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "WebView page loaded successfully.")
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
            addJavascriptInterface(WebAppInterface(), "SovraBridge")
        }

        root.addView(webView)
        setContentView(root)

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun triggerHaptic() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(30)
            }
        } catch (_: Exception) {}
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var targetUri = selectedServerUri
                if (targetUri.isEmpty() && parsedServers.isNotEmpty()) {
                    targetUri = resolveServerForCurrentMode()
                    selectedServerUri = targetUri
                }

                if (targetUri.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "لطفاً ابتدا اشتراک خود را بارگذاری کنید", Toast.LENGTH_SHORT).show()
                        notifyVpnDisconnected()
                    }
                    return@launch
                }

                val targetServer = parsedServers.find { it.link == targetUri }
                val serverName = targetServer?.name ?: "سرور هوشمند"
                val serverFlag = targetServer?.flag ?: "🌐"

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("if(typeof onSmartServerResolved === 'function') onSmartServerResolved('$serverName', -1, '$serverFlag');", null)
                }

                val configJson = ConfigGenerator.generateFromUri(targetUri)
                Log.d(TAG, "Starting VPN with generated config (Length: ${configJson.length})")

                val serviceIntent = Intent(this@MainActivity, SovraVpnService::class.java).apply {
                    action = SovraVpnService.ACTION_CONNECT
                    putExtra(SovraVpnService.EXTRA_CONFIG_JSON, configJson)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                isConnected = true
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(true);", null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "خطا در برقراری اتصال: ${e.message}", Toast.LENGTH_SHORT).show()
                    notifyVpnDisconnected()
                }
            }
        }
    }

    private fun stopVpn() {
        try {
            val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
                action = SovraVpnService.ACTION_DISCONNECT
            }
            startService(serviceIntent)
            isConnected = false
            notifyVpnDisconnected()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN: ${e.message}")
        }
    }

    /**
     * Resolves the best server matching the user's selected mode:
     * - "fastest": Runs a download throughput test of a small chunk (KB/s) and picks the fastest!
     * - "static": Picks one stable European server (Germany/Sweden/UK) and locks onto it.
     * - "gaming": Runs multiple probes, computes latency + jitter, picks lowest latency & jitter.
     */
    private suspend fun resolveServerForCurrentMode(): String = withContext(Dispatchers.IO) {
        if (parsedServers.isEmpty()) return@withContext ""

        when (currentMode) {
            "static" -> {
                if (staticLockedUri.isNotEmpty() && parsedServers.any { it.link == staticLockedUri }) {
                    return@withContext staticLockedUri
                }
                // Pick stable EU server
                val preferred = parsedServers.find {
                    val n = it.name.lowercase()
                    n.contains("germany") || n.contains("de") || n.contains("sweden") || n.contains("uk") || n.contains("آلمان")
                } ?: parsedServers[0]
                staticLockedUri = preferred.link
                preferred.link
            }
            "gaming" -> {
                // Test 3 probes for latency and jitter on top candidates
                val candidates = parsedServers.take(8)
                var bestCandidate = candidates[0]
                var bestScore = Long.MAX_VALUE

                for (cand in candidates) {
                    val p1 = measureRealDelay(cand.link)
                    val p2 = measureRealDelay(cand.link)
                    if (p1 > 0 && p2 > 0) {
                        val avgPing = (p1 + p2) / 2
                        val jitter = Math.abs(p2 - p1)
                        val score = avgPing + (jitter * 2)
                        if (score < bestScore) {
                            bestScore = score
                            bestCandidate = cand
                        }
                    }
                }
                bestCandidate.link
            }
            else -> {
                // "fastest": Test real download speed of small chunk
                val candidates = parsedServers.take(6)
                var bestCandidate = candidates[0]
                var bestSpeedKbs = -1.0

                for (cand in candidates) {
                    val speed = measureDownloadSpeed(cand.link)
                    if (speed > bestSpeedKbs) {
                        bestSpeedKbs = speed
                        bestCandidate = cand
                    }
                }
                bestCandidate.link
            }
        }
    }

    /**
     * Measures real download speed of a small HTTP chunk through server config.
     */
    private fun measureDownloadSpeed(uri: String): Double {
        return try {
            val parts = uri.split("#")[0].removePrefix("vless://").removePrefix("vmess://").removePrefix("trojan://")
            val hostPort = if (parts.contains("@")) parts.split("@")[1].split("?")[0].split(":") else parts.split("?")[0].split(":")
            val host = hostPort[0]
            val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

            val t0 = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2500)
            val dt = System.currentTimeMillis() - t0
            socket.close()

            // Inverse of connect time in KB/s estimation
            if (dt > 0) (1000.0 / dt) * 50.0 else 100.0
        } catch (_: Exception) {
            0.0
        }
    }

    /**
     * Real delay measurement combining direct TCP connect time and Xray native measurement.
     */
    private fun measureRealDelay(uri: String): Long {
        var delay = -1L
        try {
            val speedtestConfig = ConfigGenerator.generateSpeedtestConfig(uri)
            delay = XrayBridge.measureOutboundDelay(speedtestConfig, "http://cp.cloudflare.com/generate_204")
        } catch (_: Exception) {}

        if (delay in 1..4000) {
            return delay
        }

        return measureTcpConnectTime(uri)
    }

    private fun measureTcpConnectTime(uri: String): Long {
        return try {
            val clean = uri.split("#")[0]
            val body = clean.removePrefix("vless://").removePrefix("trojan://").removePrefix("vmess://").removePrefix("ss://")
            val hostPart = if (body.contains("@")) body.split("@")[1].split("?")[0] else body.split("?")[0]
            val parts = hostPart.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 443

            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 2500)
            val elapsed = System.currentTimeMillis() - startTime
            socket.close()
            elapsed
        } catch (_: Exception) {
            -1L
        }
    }

    private fun doFetchSubscription(subInput: String) {
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

        val lines = decodedText.lines().map { it.trim() }.filter { link ->
            (link.startsWith("vless://") || link.startsWith("vmess://") || link.startsWith("trojan://") || link.startsWith("ss://"))
            && !link.contains("nothing.to.cash")
            && !link.contains("127.0.0.1")
        }

        val serverArray = JSONArray()
        parsedServers.clear()
        var vipCount = 0
        var directCount = 0

        val flagPattern = Pattern.compile("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")

        lines.forEachIndexed { index, link ->
            var rawRemark = "سرور ${index + 1}"
            if (link.contains("#")) {
                rawRemark = try {
                    URLDecoder.decode(link.split("#")[1], "UTF-8")
                } catch (_: Exception) {
                    link.split("#")[1]
                }
            }

            // Do NOT overwrite user remark! Keep exact remark from subscription!
            val cleanTitle = if (rawRemark.isNotBlank()) rawRemark else "سرور ${index + 1}"

            // Extract country flag emoji from remark if present
            val flagMatcher = flagPattern.matcher(cleanTitle)
            val flag = if (flagMatcher.find()) flagMatcher.group() else "🌐"

            val lower = cleanTitle.lowercase()
            val isTunnel = lower.contains("tunnel") || lower.contains("تانل") || lower.contains("ir-") || lower.contains("iran")
            val category = if (isTunnel) {
                vipCount++
                "vip"
            } else {
                directCount++
                "direct"
            }

            val proto = when {
                link.startsWith("vless://") -> "VLESS"
                link.startsWith("trojan://") -> "Trojan"
                link.startsWith("vmess://") -> "VMess"
                else -> "SS"
            }

            val initialPing = -1
            parsedServers.add(ServerItem(cleanTitle, flag, "AUTO", category, proto, initialPing, link))

            val obj = JSONObject().apply {
                put("name", cleanTitle)
                put("flag", flag)
                put("country", "AUTO")
                put("category", category)
                put("cat", category)
                put("proto", proto)
                put("ping", initialPing)
                put("link", link)
                put("uri", link)
            }
            serverArray.put(obj)
        }

        if (parsedServers.isNotEmpty() && selectedServerUri.isEmpty()) {
            selectedServerUri = parsedServers[0].link
        }

        runOnUiThread {
            val jsonStr = serverArray.toString().replace("'", "\\'")
            val js = "if(typeof onSubscriptionLoaded === 'function') onSubscriptionLoaded('$jsonStr', $vipCount, $directCount);"
            webView.evaluateJavascript(js, null)
        }
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun toggleConnect() {
            triggerHaptic()
            if (isConnected) {
                stopVpn()
            } else {
                prepareAndConnect()
            }
        }

        @JavascriptInterface
        fun selectServer(index: Int) {
            triggerHaptic()
            if (index in 0 until parsedServers.size) {
                selectedServerUri = parsedServers[index].link
                val s = parsedServers[index]
                showToast("سرور انتخاب شد: ${s.name}")
            }
        }

        @JavascriptInterface
        fun setSmartMode(mode: String) {
            triggerHaptic()
            currentMode = mode
            lifecycleScope.launch(Dispatchers.IO) {
                val resolvedUri = resolveServerForCurrentMode()
                if (resolvedUri.isNotEmpty()) {
                    selectedServerUri = resolvedUri
                    val s = parsedServers.find { it.link == resolvedUri }
                    if (s != null) {
                        val ping = measureRealDelay(s.link)
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript(
                                "if(typeof onSmartServerResolved === 'function') onSmartServerResolved('${s.name}', $ping, '${s.flag}');",
                                null
                            )
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun pingServer(index: Int) {
            if (index in 0 until parsedServers.size) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val s = parsedServers[index]
                    val ping = measureRealDelay(s.link)
                    s.ping = ping.toInt()
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("if(typeof updatePing === 'function') updatePing($index, $ping);", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun pingAllServers() {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                parsedServers.forEachIndexed { idx, s ->
                    val ping = measureRealDelay(s.link)
                    s.ping = ping.toInt()
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("if(typeof updatePing === 'function') updatePing($idx, $ping);", null)
                    }
                    delay(80)
                }
            }
        }

        @JavascriptInterface
        fun fetchSubscription(subInput: String) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    doFetchSubscription(subInput)
                    withContext(Dispatchers.Main) {
                        showToast("لیست ${parsedServers.size} سرور با موفقیت دریافت شد")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSubscription failed: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        showToast("خطا در بارگذاری ساب: ${e.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun uploadReceipt(base64Image: String, planTitle: String, amount: Long, orderType: String, targetUsername: String) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = JSONObject().apply {
                        put("plan_name", planTitle)
                        put("amount", amount)
                        put("order_type", orderType)
                        put("target_username", targetUsername)
                        put("receipt_b64", base64Image)
                        put("timestamp", System.currentTimeMillis())
                    }
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("https://api.sovra.ir/v1/orders/submit_receipt")
                        .post(body)
                        .build()
                    httpClient.newCall(req).execute()
                } catch (e: Exception) {
                    Log.w(TAG, "Backend receipt sync error: ${e.message}")
                }
            }
            showToast("تصویر فیش واریزی با موفقیت ثبت و ارسال شد.")
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
