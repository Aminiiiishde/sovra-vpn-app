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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private var lockedStaticServerUri = ""
    private var activeCleanIp = ""
    private var currentSmartMode = "fastest" // fastest, static, gaming
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val parsedServers = mutableListOf<ServerItem>()

    data class ServerItem(
        val name: String,
        val flag: String,
        val country: String,
        val category: String,
        val proto: String,
        var ping: Int,
        val link: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun getWebView(): WebView? = if (::webView.isInitialized) webView else null

    fun pushLiveStats(downStr: String, upStr: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(typeof updateTrafficDisplay === 'function') updateTrafficDisplay('$downStr', '$upStr');", null)
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
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            addJavascriptInterface(WebAppBridge(), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView page loaded: $url")
                    runCleanIpScanner()
                    // Auto-load subscription
                    WebAppBridge().fetchSubscription("5dr48a6hn8dwwbs7")
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

            loadUrl("file:///android_asset/index.html")
        }

        root.addView(webView)
        setContentView(root)
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
        lifecycleScope.launch(Dispatchers.IO) {
            var targetUri = selectedServerUri
            if (targetUri.isEmpty() && parsedServers.isNotEmpty()) {
                targetUri = resolveServerForCurrentMode()
            }

            if (targetUri.isEmpty()) {
                targetUri = "vless://32391f04-ab3a-47b4-a235-d9716a601c44@tun.sovra.ir:7822?type=httpupgrade&security=tls&path=%2Fvideogames%2Fv2%2Fsk&host=german.sovra.ir&sni=german.sovra.ir&fp=chrome#Germany-VIP"
            }

            Log.d(TAG, "Starting VPN with targetUri: $targetUri")
            val config = ConfigGenerator.generateFromUri(targetUri, activeCleanIp)

            val serviceIntent = Intent(this@MainActivity, SovraVpnService::class.java).apply {
                action = SovraVpnService.ACTION_CONNECT
                putExtra(SovraVpnService.EXTRA_CONFIG_JSON, config)
            }
            startService(serviceIntent)
            isConnected = true

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(true);", null)
            }
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

    private fun resolveServerForCurrentMode(): String {
        if (parsedServers.isEmpty()) return ""

        return when (currentSmartMode) {
            "static" -> {
                if (lockedStaticServerUri.isNotEmpty()) {
                    lockedStaticServerUri
                } else {
                    val staticCandidates = parsedServers.filter {
                        val c = it.country.uppercase()
                        c == "DE" || c == "SE" || c == "GB"
                    }
                    val pool = if (staticCandidates.isNotEmpty()) staticCandidates else parsedServers
                    var bestUri = pool[0].link
                    var bestDelay = Int.MAX_VALUE
                    for (srv in pool.take(6)) {
                        val d = measureRealDelay(srv.link)
                        if (d in 1 until bestDelay) {
                            bestDelay = d
                            bestUri = srv.link
                        }
                    }
                    lockedStaticServerUri = bestUri
                    bestUri
                }
            }
            "gaming" -> {
                var bestUri = parsedServers[0].link
                var bestPing = Int.MAX_VALUE
                val candidates = parsedServers.take(8)
                for (srv in candidates) {
                    val d1 = measureRealDelay(srv.link)
                    if (d1 > 0) {
                        val d2 = measureRealDelay(srv.link)
                        if (d2 > 0) {
                            val avg = (d1 + d2) / 2
                            if (avg < bestPing) {
                                bestPing = avg
                                bestUri = srv.link
                            }
                        }
                    }
                }
                bestUri
            }
            else -> {
                // fastest
                var bestUri = parsedServers[0].link
                var minPing = Int.MAX_VALUE
                for (srv in parsedServers.take(10)) {
                    val d = measureRealDelay(srv.link)
                    if (d in 1 until minPing) {
                        minPing = d
                        bestUri = srv.link
                    }
                }
                bestUri
            }
        }
    }

    private fun measureTcpConnectTime(rawUri: String): Int {
        return try {
            val clean = rawUri.split("#")[0].trim()
            val host: String
            val port: Int

            if (clean.startsWith("vmess://")) {
                val b64 = clean.removePrefix("vmess://")
                val pad = (4 - b64.length % 4) % 4
                val jsonStr = String(Base64.decode(b64 + "=".repeat(pad), Base64.DEFAULT))
                val obj = JSONObject(jsonStr)
                host = obj.optString("add", "tun.sovra.ir")
                port = obj.optString("port", "443").split("/")[0].toIntOrNull() ?: 443
            } else if (clean.startsWith("ss://")) {
                val body = clean.removePrefix("ss://")
                val hp = if (body.contains("@")) body.split("@")[1].split("?")[0].split(":") else body.split("?")[0].split(":")
                host = hp[0]
                port = hp[1].split("/")[0].toIntOrNull() ?: 8388
            } else {
                // vless or trojan
                val body = clean.substringAfter("://")
                val hp_q = body.split("@")[1].split("?")[0].split(":")
                host = hp_q[0]
                port = hp_q[1].split("/")[0].toIntOrNull() ?: 443
            }

            val t0 = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 1500)
            val elapsed = (System.currentTimeMillis() - t0).toInt()
            socket.close()
            if (elapsed > 0) elapsed else 1
        } catch (e: Exception) {
            -1
        }
    }

    private fun measureRealDelay(rawUri: String): Int {
        val tcpTime = measureTcpConnectTime(rawUri)
        if (tcpTime > 0) {
            try {
                val speedtestConfig = ConfigGenerator.generateSpeedtestConfig(rawUri, activeCleanIp)
                val delay = XrayBridge.measureOutboundDelay(speedtestConfig, "http://cp.cloudflare.com/generate_204")
                if (delay in 1..3000) {
                    return delay.toInt()
                }
            } catch (_: Exception) {}
            return tcpTime
        }
        return -1
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

        lines.forEachIndexed { index, link ->
            var rawRemark = "SOVRA-$index"
            if (link.contains("#")) {
                rawRemark = try {
                    URLDecoder.decode(link.split("#")[1], "UTF-8")
                } catch (_: Exception) {
                    link.split("#")[1]
                }
            }

            val normalized = normalizeSmallCaps(rawRemark).lowercase()
            val isVip = normalized.contains("tunnel") || normalized.contains("تانل") || normalized.contains("ir-") || normalized.contains("iran")

            val category = if (isVip) {
                vipCount++
                "vip"
            } else {
                directCount++
                "direct"
            }

            val (flag, country, faCountry) = when {
                normalized.contains("germany") || normalized.contains("de") || normalized.contains("آلمان") -> Triple("🇩🇪", "DE", "آلمان")
                normalized.contains("sweden") || normalized.contains("se") || normalized.contains("سوئد") -> Triple("🇸🇪", "SE", "سوئد")
                normalized.contains("england") || normalized.contains("uk") || normalized.contains("gb") || normalized.contains("انگلستان") || normalized.contains("انگلیس") -> Triple("🇬🇧", "GB", "انگلستان")
                normalized.contains("turkey") || normalized.contains("tr") || normalized.contains("ترکیه") -> Triple("🇹🇷", "TR", "ترکیه")
                normalized.contains("netherlands") || normalized.contains("nl") || normalized.contains("هلند") -> Triple("🇳🇱", "NL", "هلند")
                normalized.contains("france") || normalized.contains("fr") || normalized.contains("فرانسه") -> Triple("🇫🇷", "FR", "فرانسه")
                normalized.contains("finland") || normalized.contains("fi") || normalized.contains("فنلاند") -> Triple("🇫🇮", "FI", "فنلاند")
                normalized.contains("iran") || normalized.contains("ir") || normalized.contains("ایران") -> Triple("🇮🇷", "IR", "ایران")
                normalized.contains("us") || normalized.contains("usa") || normalized.contains("آمریکا") || normalized.contains("kansas") -> Triple("🇺🇸", "US", "آمریکا")
                else -> Triple("🇩🇪", "DE", "آلمان")
            }

            val typeDesc = if (isVip) "تانل VIP" else "مستقیم فستلی"
            val cleanTitle = "$faCountry - $typeDesc (${index + 1})"
            val proto = if (link.startsWith("vless://")) "VLESS" else if (link.startsWith("trojan://")) "Trojan" else if (link.startsWith("vmess://")) "VMess" else "SS"

            val initialPing = -1
            parsedServers.add(ServerItem(cleanTitle, flag, country, category, proto, initialPing, link))

            val obj = JSONObject().apply {
                put("name", cleanTitle)
                put("flag", flag)
                put("country", country)
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

    private fun normalizeSmallCaps(s: String): String {
        val map = mapOf(
            'ᴛ' to 't', 'ᴜ' to 'u', 'ɴ' to 'n', 'ᴇ' to 'e', 'ʟ' to 'l',
            'ᴅ' to 'd', 'ɪ' to 'i', 'ʀ' to 'r', 'ᴄ' to 'c', 'ꜰ' to 'f',
            'ꜱ' to 's', 'ᴀ' to 'a', 'ᴏ' to 'o', 'ᴠ' to 'v', 'ɢ' to 'g',
            'ᴋ' to 'k', 'ʏ' to 'y', 'ʙ' to 'b', 'ᴍ' to 'm', 'ᴡ' to 'w'
        )
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(map[ch] ?: ch)
        }
        return sb.toString()
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
        fun selectServer(idx: Int) {
            triggerHaptic()
            if (idx in 0 until parsedServers.size) {
                selectedServerUri = parsedServers[idx].link
                Log.d(TAG, "Selected server idx=$idx: ${parsedServers[idx].name}")
            }
        }

        @JavascriptInterface
        fun selectServerUri(uri: String) {
            triggerHaptic()
            selectedServerUri = uri
            Log.d(TAG, "Selected server uri: $uri")
        }

        @JavascriptInterface
        fun setSmartMode(mode: String) {
            triggerHaptic()
            currentSmartMode = mode
            lifecycleScope.launch(Dispatchers.IO) {
                val modeDesc = when (mode) {
                    "static" -> "آی‌پی تمیز و ثابت (آلمان / سوئد / انگلیس)"
                    "gaming" -> "گیمینگ (تست پکت‌لاست و کمترین پینگ)"
                    else -> "سریع‌ترین سرور (پایین‌ترین پینگ)"
                }

                if (parsedServers.isNotEmpty()) {
                    val winnerUri = resolveServerForCurrentMode()
                    selectedServerUri = winnerUri
                    val winnerServer = parsedServers.find { it.link == winnerUri } ?: parsedServers[0]
                    val ping = measureRealDelay(winnerServer.link)
                    if (ping > 0) winnerServer.ping = ping

                    withContext(Dispatchers.Main) {
                        showToast("$modeDesc: ${winnerServer.name} (${if (ping > 0) "$ping ms" else "متصل"})")
                        webView.evaluateJavascript(
                            "if(typeof onSmartServerResolved === 'function') onSmartServerResolved('${winnerServer.name}', ${winnerServer.ping}, '${winnerServer.flag}');",
                            null
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("حالت اتصال: $modeDesc انتخاب شد")
                    }
                }
            }
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
                        val ping = measureRealDelay(uriStr)
                        if (i in 0 until parsedServers.size) {
                            parsedServers[i].ping = ping
                        }
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
        fun testSinglePing(idx: Int, uri: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ping = measureRealDelay(uri)
                if (idx in 0 until parsedServers.size) {
                    parsedServers[idx].ping = ping
                }
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("if(typeof updatePing === 'function') updatePing($idx, $ping);", null)
                }
            }
        }

        @JavascriptInterface
        fun fetchSubscription(subInput: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    doFetchSubscription(subInput)
                    withContext(Dispatchers.Main) {
                        showToast("لیست ${parsedServers.size} سرور فعال بارگذاری شد")
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
        fun startFreeTrial() {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        showToast("در حال راه‌اندازی اکانت تست ۲۴ ساعته...")
                    }
                    doFetchSubscription("5dr48a6hn8dwwbs7")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "اکانت تست ۲۴ ساعته فعال گردید!", Toast.LENGTH_SHORT).show()
                        prepareAndConnect()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showToast("خطا در فعال‌سازی اکانت تست: ${e.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun submitOrder(title: String, amount: Long) {
            triggerHaptic()
            showToast("سفارش «$title» به مبلغ $amount تومان ثبت گردید.")
        }

        @JavascriptInterface
        fun uploadReceipt(base64Image: String, planTitle: String, amount: Long) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = JSONObject().apply {
                        put("plan_name", planTitle)
                        put("amount", amount)
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
