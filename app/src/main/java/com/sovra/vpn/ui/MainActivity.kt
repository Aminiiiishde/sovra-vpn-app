package com.sovra.vpn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sovra.vpn.core.ConfigGenerator
import com.sovra.vpn.core.SovraVpnService
import com.sovra.vpn.core.XrayBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "sovra_prefs"
        private const val KEY_SUB_URL = "sovra_sub_url"
        private const val KEY_STATIC_LOCKED_URI = "sovra_static_locked_uri"
        var instance: MainActivity? = null
            private set
    }

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    private var isConnected = false
    private var selectedServerUri: String = ""
    private var staticLockedUri: String = ""
    private var currentMode: String = "fastest"
    private var subscriptionAccountName: String = "کاربر SOVRA"

    private val parsedServers = mutableListOf<ServerItem>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private var pendingConnect = false
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (pendingConnect) {
                pendingConnect = false
                startVpnTunnel()
            }
        } else {
            pendingConnect = false
            Toast.makeText(this, "مجوز دسترسی VPN رد شد", Toast.LENGTH_SHORT).show()
            notifyVpnDisconnected()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback != null) {
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        staticLockedUri = prefs.getString(KEY_STATIC_LOCKED_URI, "") ?: ""

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
        setContentView(webView)

        val bridge = WebAppInterface()
        webView.addJavascriptInterface(bridge, "SovraBridge")
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("SOVRA_JS", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val savedSub = prefs.getString(KEY_SUB_URL, "") ?: ""
                if (savedSub.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            doFetchSubscription(savedSub)
                        } catch (e: Exception) {
                            Log.w(TAG, "Auto load subscription failed: ${e.message}")
                        }
                    }
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(25)
                }
            }
        } catch (_: Exception) {}
    }

    private fun prepareAndConnect() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingConnect = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnTunnel()
        }
    }

    private fun startVpnTunnel() {
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
                val serverName = targetServer?.name ?: "سرور امن SOVRA"
                val serverFlag = targetServer?.flag ?: "🌐"

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("if(typeof onSmartServerResolved === 'function') onSmartServerResolved('$serverName', -1, '$serverFlag');", null)
                }

                val configJson = ConfigGenerator.generateFromUri(targetUri)
                Log.d(TAG, "Starting VPN with generated config (Length: ${configJson.length})")

                val serviceIntent = Intent(this@MainActivity, SovraVpnService::class.java).apply {
                    action = SovraVpnService.ACTION_CONNECT
                    putExtra(SovraVpnService.EXTRA_CONFIG_JSON, configJson)
                    putExtra(SovraVpnService.EXTRA_SERVER_NAME, serverName)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                isConnected = true
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(true);", null)
                    showToast("اتصال برقرار شد: $serverName")
                }

            } catch (e: Exception) {
                Log.e(TAG, "startVpnTunnel error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showToast("خطا در اتصال: ${e.message}")
                    notifyVpnDisconnected()
                }
            }
        }
    }

    private fun restartVpnWithUri(newUri: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                selectedServerUri = newUri
                val targetServer = parsedServers.find { it.link == newUri }
                val serverName = targetServer?.name ?: "سرور جدید"
                val serverFlag = targetServer?.flag ?: "🌐"

                val configJson = ConfigGenerator.generateFromUri(newUri)
                Log.d(TAG, "Restarting VPN with server: $serverName")

                val serviceIntent = Intent(this@MainActivity, SovraVpnService::class.java).apply {
                    action = SovraVpnService.ACTION_CONNECT
                    putExtra(SovraVpnService.EXTRA_CONFIG_JSON, configJson)
                    putExtra(SovraVpnService.EXTRA_SERVER_NAME, serverName)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                isConnected = true
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("if(typeof onSmartServerResolved === 'function') onSmartServerResolved('$serverName', -1, '$serverFlag');", null)
                    webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(true);", null)
                    showToast("اتصال به $serverName برقرار شد")
                }
            } catch (e: Exception) {
                Log.e(TAG, "restartVpnWithUri failed: ${e.message}", e)
            }
        }
    }

    private fun stopVpn() {
        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_DISCONNECT
        }
        startService(serviceIntent)
        notifyVpnDisconnected()
    }

    private fun normalizeSmallCaps(input: String): String {
        val smallCaps = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ"
        val normal    = "abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        for (ch in input) {
            val idx = smallCaps.indexOf(ch)
            if (idx != -1) sb.append(normal[idx]) else sb.append(ch)
        }
        return sb.toString().lowercase()
    }

    private fun isCountryMatch(server: ServerItem, countryKeyword: String): Boolean {
        val norm = normalizeSmallCaps(server.name)
        val flag = server.flag
        return when (countryKeyword.lowercase()) {
            "germany" -> flag.contains("🇩🇪") || norm.contains("germany") || norm.contains("de") || norm.contains("آلمان")
            "sweden" -> flag.contains("🇸🇪") || norm.contains("sweden") || norm.contains("se") || norm.contains("سوئد")
            "uk" -> flag.contains("🇬🇧") || norm.contains("uk") || norm.contains("united kingdom") || norm.contains("انگلیس") || norm.contains("london")
            else -> false
        }
    }

    private suspend fun resolveServerForCurrentMode(): String = withContext(Dispatchers.IO) {
        if (parsedServers.isEmpty()) return@withContext ""

        when (currentMode) {
            "static" -> {
                // If previously locked onto a valid server, maintain that lock
                if (staticLockedUri.isNotEmpty() && parsedServers.any { it.link == staticLockedUri }) {
                    return@withContext staticLockedUri
                }

                // Prefer Germany, Sweden, or UK
                val countryCandidates = parsedServers.filter { s ->
                    isCountryMatch(s, "germany") || isCountryMatch(s, "sweden") || isCountryMatch(s, "uk")
                }.ifEmpty { parsedServers }

                // Test real delay to pick one that is actively online
                var chosen: ServerItem? = null
                for (cand in countryCandidates) {
                    val delay = measureRealDelay(cand.link)
                    if (delay > 0) {
                        cand.ping = delay.toInt()
                        chosen = cand
                        break
                    }
                }

                if (chosen == null) {
                    chosen = countryCandidates.firstOrNull() ?: parsedServers[0]
                }

                staticLockedUri = chosen.link
                prefs.edit().putString(KEY_STATIC_LOCKED_URI, staticLockedUri).apply()
                chosen.link
            }
            "gaming" -> {
                val candidates = parsedServers.take(8)
                var bestCandidate = candidates[0]
                var bestScore = Long.MAX_VALUE

                for (cand in candidates) {
                    val p1 = measureRealDelay(cand.link)
                    val p2 = measureRealDelay(cand.link)
                    if (p1 > 0 && p2 > 0) {
                        val avgPing = (p1 + p2) / 2
                        val jitter = Math.abs(p2 - p1)
                        val score = avgPing + (jitter * 3) // Penalty for jitter / packet drop
                        if (score < bestScore) {
                            bestScore = score
                            bestCandidate = cand
                        }
                    } else if (p1 > 0 && bestScore == Long.MAX_VALUE) {
                        bestCandidate = cand
                    }
                }
                bestCandidate.link
            }
            else -> {
                // "fastest" - probe candidates and select lowest delay
                val candidates = parsedServers.take(6)
                var bestCandidate = candidates[0]
                var bestDelay = Long.MAX_VALUE

                for (cand in candidates) {
                    val d = measureRealDelay(cand.link)
                    if (d in 1 until bestDelay) {
                        bestDelay = d
                        bestCandidate = cand
                    }
                }
                bestCandidate.link
            }
        }
    }

    fun notifyVpnDisconnected() {
        isConnected = false
        runOnUiThread {
            webView.evaluateJavascript("if(typeof setConnState === 'function') setConnState(false);", null)
        }
    }

    fun updateLiveTraffic(downSpeed: String, totalVolume: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(typeof updateTrafficDisplay === 'function') updateTrafficDisplay('$downSpeed', '$totalVolume');", null)
        }
    }

    private fun measureRealDelay(uri: String): Long {
        return try {
            val speedtestConfig = ConfigGenerator.generateSpeedtestConfig(uri)
            val delay = XrayBridge.measureOutboundDelay(speedtestConfig, "https://www.gstatic.com/generate_204")
            if (delay > 0) delay else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun doFetchSubscription(subInput: String) {
        val input = subInput.trim()
        var decodedText = ""

        if (input.startsWith("vless://") || input.startsWith("vmess://") || input.startsWith("trojan://") || input.startsWith("ss://")) {
            decodedText = input
        } else if (!input.startsWith("http://") && !input.startsWith("https://") && input.length > 50 && !input.contains("/") && !input.contains(" ")) {
            try {
                val bytes = Base64.decode(input, Base64.DEFAULT or Base64.NO_WRAP)
                decodedText = String(bytes)
            } catch (_: Exception) {}
        }

        if (decodedText.isEmpty()) {
            var subUrl = input
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

            decodedText = rawBody
            try {
                val bytes = Base64.decode(rawBody.trim(), Base64.DEFAULT or Base64.NO_WRAP)
                decodedText = String(bytes)
            } catch (_: Exception) {}
        }

        val lines = decodedText.lines().map { it.trim() }.filter { link ->
            link.startsWith("vless://") || link.startsWith("vmess://") || link.startsWith("trojan://") || link.startsWith("ss://")
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

            val cleanTitle = if (rawRemark.isNotBlank()) rawRemark else "سرور ${index + 1}"

            // Check if this node is an info/account header node
            val isInfoNode = link.contains("nothing.to.cash") || link.contains("127.0.0.1") ||
                    cleanTitle.contains("حجم") || cleanTitle.contains("انقضا")

            if (isInfoNode) {
                subscriptionAccountName = cleanTitle
                return@forEachIndexed
            }

            val flagMatcher = flagPattern.matcher(cleanTitle)
            val flag = if (flagMatcher.find()) flagMatcher.group() else detectFlagFromName(cleanTitle)

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
                else -> "Shadowsocks"
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

        prefs.edit().putString(KEY_SUB_URL, input).apply()

        runOnUiThread {
            val jsonStr = serverArray.toString().replace("'", "\\'")
            val safeAccountName = subscriptionAccountName.replace("'", "\\'")
            val js = "if(typeof onSubscriptionLoaded === 'function') onSubscriptionLoaded('$jsonStr', $vipCount, $directCount, '$safeAccountName');"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun detectFlagFromName(name: String): String {
        val norm = normalizeSmallCaps(name)
        return when {
            norm.contains("germany") || norm.contains("de") || norm.contains("آلمان") -> "🇩🇪"
            norm.contains("turkey") || norm.contains("tr") || norm.contains("ترکیه") -> "🇹🇷"
            norm.contains("sweden") || norm.contains("se") || norm.contains("سوئد") -> "🇸🇪"
            norm.contains("netherlands") || norm.contains("nl") || norm.contains("هلند") -> "🇳🇱"
            norm.contains("uk") || norm.contains("united kingdom") || norm.contains("انگلیس") || norm.contains("london") -> "🇬🇧"
            norm.contains("us") || norm.contains("united states") || norm.contains("آمریکا") -> "🇺🇸"
            norm.contains("france") || norm.contains("fr") || norm.contains("فرانسه") -> "🇫🇷"
            norm.contains("finland") || norm.contains("fi") || norm.contains("فنلاند") -> "🇫🇮"
            norm.contains("iran") || norm.contains("ایران") -> "🇮🇷"
            else -> "🌐"
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
                val s = parsedServers[index]
                selectedServerUri = s.link
                if (isConnected) {
                    showToast("در حال انتقال اتصال به ${s.name}...")
                    restartVpnWithUri(s.link)
                } else {
                    showToast("سرور انتخاب شد: ${s.name}")
                }
            }
        }

        @JavascriptInterface
        fun selectServerUri(uri: String) {
            triggerHaptic()
            if (selectedServerUri == uri && isConnected) return
            selectedServerUri = uri
            val s = parsedServers.find { it.link == uri }
            if (s != null) {
                if (isConnected) {
                    showToast("در حال انتقال اتصال به ${s.name}...")
                    restartVpnWithUri(uri)
                } else {
                    showToast("سرور انتخاب شد: ${s.name}")
                }
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
                        if (isConnected) {
                            withContext(Dispatchers.Main) {
                                showToast("در حال اتصال هوشمند به ${s.name}...")
                            }
                            restartVpnWithUri(resolvedUri)
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
                val semaphore = Semaphore(8)
                val jobs = parsedServers.mapIndexed { idx, s ->
                    launch {
                        semaphore.withPermit {
                            val ping = measureRealDelay(s.link)
                            s.ping = ping.toInt()
                            withContext(Dispatchers.Main) {
                                webView.evaluateJavascript("if(typeof updatePing === 'function') updatePing($idx, $ping);", null)
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
        }

        @JavascriptInterface
        fun testAllPing(urisJson: String = "") {
            pingAllServers()
        }

        @JavascriptInterface
        fun fetchSubscription(subInput: String) {
            triggerHaptic()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    doFetchSubscription(subInput)
                    withContext(Dispatchers.Main) {
                        showToast("لیست ${parsedServers.size} سرور با موفقیت بارگذاری شد")
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
                    val url = "https://sub.aminishere.shop/api/order/submit"
                    val payload = JSONObject().apply {
                        put("planTitle", planTitle)
                        put("amount", amount)
                        put("orderType", orderType)
                        put("username", targetUsername)
                        put("receiptBase64", base64Image)
                    }

                    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(url).post(body).build()
                    val response = httpClient.newCall(request).execute()

                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            showToast("رسید شما با موفقیت ثبت شد و در حال بررسی است.")
                            webView.evaluateJavascript("if(typeof onOrderSubmitted === 'function') onOrderSubmitted(true);", null)
                        } else {
                            showToast("خطا در ارسال رسید: کد ${response.code}")
                            webView.evaluateJavascript("if(typeof onOrderSubmitted === 'function') onOrderSubmitted(false);", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "uploadReceipt failed: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        showToast("خطا در ارسال: ${e.message}")
                        webView.evaluateJavascript("if(typeof onOrderSubmitted === 'function') onOrderSubmitted(false);", null)
                    }
                }
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

data class ServerItem(
    val name: String,
    val flag: String,
    val country: String,
    val category: String,
    val proto: String,
    var ping: Int,
    val link: String
)
