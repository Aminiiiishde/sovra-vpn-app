package com.sovra.vpn.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class ScanResult(
    val ip: String,
    val pingMs: Long,
    val downloadSpeedMbps: Double,
    val isClean: Boolean
)

object SmartCloudflareScanner {

    // Handpicked clean CIDR IP samples proven effective across MCI, Irancell and Fixed DSL
    private val CANDIDATE_IPS = listOf(
        "104.16.132.229", "104.17.210.9", "162.159.135.42",
        "172.67.180.12", "104.21.48.11", "104.26.12.18",
        "104.19.150.30", "172.64.150.28", "162.159.36.2"
    )

    suspend fun scanBestIp(targetHost: String = "speed.cloudflare.com", modeSpeed: Boolean = false): ScanResult? = coroutineScope {
        val jobs = CANDIDATE_IPS.map { ip ->
            async(Dispatchers.IO) {
                testCandidate(ip, targetHost, modeSpeed)
            }
        }
        val results = jobs.awaitAll().filter { it.isClean }
        if (modeSpeed) {
            results.maxByOrNull { it.downloadSpeedMbps }
        } else {
            results.minByOrNull { it.pingMs }
        }
    }

    private fun testCandidate(ip: String, host: String, testSpeed: Boolean): ScanResult {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, 443), 2000)
            val ping = System.currentTimeMillis() - start
            socket.close()

            var speed = 0.0
            if (testSpeed) {
                speed = measureThroughput(ip, host)
            }
            return ScanResult(ip, ping, speed, true)
        } catch (e: Exception) {
            try { socket?.close() } catch (ignored: Exception) {}
            return ScanResult(ip, -1L, 0.0, false)
        }
    }

    private fun measureThroughput(ip: String, host: String): Double {
        return try {
            val url = URL("https://$ip/__down?bytes=200000") // 200KB quick burst
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Host", host)
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val t0 = System.currentTimeMillis()
            val stream = conn.inputStream
            val buffer = ByteArray(1024)
            var totalRead = 0
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                totalRead += read
            }
            stream.close()
            val durationSec = (System.currentTimeMillis() - t0) / 1000.0
            if (durationSec > 0) (totalRead * 8.0) / (durationSec * 1024 * 1024) else 0.0
        } catch (e: Exception) {
            0.0
        }
    }
}
