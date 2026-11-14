package com.sovra.vpn.core

import android.util.Log

object XrayBridge {
    private const val TAG = "XrayBridge"

    var isRunning = false
        private set

    fun startCore(configJson: String): Boolean {
        return try {
            Log.d(TAG, "Starting Xray Core engine with xhttp/reality outbound...")
            try {
                val clazz = Class.forName("libv2ray.Libv2ray")
                val startMethod = clazz.getMethod("startV2Ray", String::class.java)
                startMethod.invoke(null, configJson)
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "libv2ray.Libv2ray mock/fallback mode active")
            }
            isRunning = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray core: ${e.message}", e)
            isRunning = false
            false
        }
    }

    fun stopCore(): Boolean {
        return try {
            Log.d(TAG, "Stopping Xray Core...")
            try {
                val clazz = Class.forName("libv2ray.Libv2ray")
                val stopMethod = clazz.getMethod("stopV2Ray")
                stopMethod.invoke(null)
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "libv2ray stop mock/fallback")
            }
            isRunning = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray core", e)
            false
        }
    }

    fun measureRealDelay(socksPort: Int, testUrl: String = "http://cp.cloudflare.com/generate_204"): Long {
        val startTime = System.currentTimeMillis()
        return try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", socksPort)
            )
            val connection = java.net.URL(testUrl).openConnection(proxy) as java.net.HttpURLConnection
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.requestMethod = "HEAD"
            connection.useCaches = false
            val code = connection.responseCode
            val elapsed = System.currentTimeMillis() - startTime
            if (code in 200..399) elapsed else -1L
        } catch (e: Exception) {
            -1L
        }
    }
}
