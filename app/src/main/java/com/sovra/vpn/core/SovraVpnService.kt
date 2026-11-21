package com.sovra.vpn.core

import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sovra.vpn.R
import com.sovra.vpn.SovraApp
import com.sovra.vpn.ui.MainActivity
import kotlinx.coroutines.*

class SovraVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.sovra.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.sovra.vpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "SovraVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var tickerJob: Job? = null
    private var totalDownloadedBytes = 0L
    private var totalUploadedBytes = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: ""
                startTunnel(config)
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
                MainActivity.instance?.notifyVpnDisconnected()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configJson: String) {
        try {
            totalDownloadedBytes = 0L
            totalUploadedBytes = 0L

            val builder = Builder().apply {
                setSession("SOVRA VPN VIP")
                setMtu(1500)
                addAddress("10.10.14.1", 30)
                addRoute("0.0.0.0", 0)
                try {
                    addAddress("fc00::10:10:14:1", 126)
                    addRoute("::", 0)
                } catch (_: Exception) {}
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")
                setBlocking(false)
                try {
                    addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    builder.setMetered(false)
                } catch (_: Exception) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    setUnderlyingNetworks(null)
                } catch (_: Exception) {}
            }

            val pfd = builder.establish()
            vpnInterface = pfd

            val tunFd = pfd?.fd ?: 0
            Log.d(TAG, "VPN tunnel established with tunFd=$tunFd")
            val started = XrayBridge.startCore(configJson, tunFd)
            if (!started) {
                Log.e(TAG, "Failed to start Xray core inside startTunnel!")
            }

            startLiveNotificationTicker()

        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed: ${e.message}", e)
            stopTunnel()
            MainActivity.instance?.notifyVpnDisconnected()
        }
    }

    private fun startLiveNotificationTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(1200)
                val nowTime = System.currentTimeMillis()
                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()
                val dt = (nowTime - lastTime) / 1000.0

                var rxSpeed = if (dt > 0 && currentRx >= lastRx) (currentRx - lastRx) / dt else 0.0
                var txSpeed = if (dt > 0 && currentTx >= lastTx) (currentTx - lastTx) / dt else 0.0

                val (xDown, xUp) = XrayBridge.queryTrafficStats()
                if (xDown > 0 && dt > 0) rxSpeed = xDown / dt
                if (xUp > 0 && dt > 0) txSpeed = xUp / dt

                if (dt > 0) {
                    totalDownloadedBytes += (rxSpeed * dt).toLong()
                    totalUploadedBytes += (txSpeed * dt).toLong()
                }

                lastRx = currentRx
                lastTx = currentTx
                lastTime = nowTime

                val downStr = formatSpeed(rxSpeed)
                val upStr = formatSpeed(txSpeed)
                val totalStr = formatBytes(totalDownloadedBytes + totalUploadedBytes)

                updateForegroundNotification("↓ $downStr  ↑ $upStr • مصرف: $totalStr")
                MainActivity.instance?.pushLiveStats(downStr, upStr, totalStr)
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024)
            bytesPerSec > 0 -> String.format("%.0f B/s", bytesPerSec)
            else -> "0 KB/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes.toDouble() / (1024L * 1024L * 1024L))
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes.toDouble() / (1024L * 1024L))
            bytes >= 1024L -> String.format("%.0f KB", bytes.toDouble() / (1024L * 1024L))
            else -> "$bytes B"
        }
    }

    private fun updateForegroundNotification(statsText: String) {
        val notifyIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // One-click Disconnect Action inside notification
        val disconnectIntent = Intent(this, SovraVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SovraApp.CHANNEL_ID)
            .setContentTitle("سپر امنیتی SOVRA VPN (متصل)")
            .setContentText(statsText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", disconnectPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopTunnel() {
        try {
            tickerJob?.cancel()
            XrayBridge.stopCore()
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "VPN tunnel stopped cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }
}
