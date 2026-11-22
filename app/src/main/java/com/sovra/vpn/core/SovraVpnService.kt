package com.sovra.vpn.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import java.util.Locale

class SovraVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.sovra.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.sovra.vpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "SovraVpnService"

        @Volatile
        var isServiceRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private var currentServerName: String = "سرور امن"

    private var totalDownloadedBytes = 0L
    private var totalUploadedBytes = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: ""
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "سرور امن"
                currentServerName = serverName
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
            Log.d(TAG, "Starting or reconnecting tunnel...")

            // Cleanly stop any running core first
            XrayBridge.stopCore()

            val builder = Builder().apply {
                setSession("SOVRA VPN")
                setMtu(1500)
                addAddress("10.10.14.1", 30)
                addRoute("0.0.0.0", 0)
                try {
                    addAddress("fc00::10:10:14:1", 126)
                    addRoute("::", 0)
                } catch (_: Exception) {}
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")
                try {
                    addDisallowedApplication(applicationContext.packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication: ${e.message}")
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

            // Close old interface to apply new parameters cleanly
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}

            val pfd = builder.establish()
            vpnInterface = pfd

            val tunFd = pfd?.fd ?: 0
            Log.d(TAG, "VPN tunnel established with tunFd=$tunFd")
            val started = XrayBridge.startCore(configJson, tunFd)
            if (!started) {
                Log.e(TAG, "Failed to start Xray core inside startTunnel!")
                stopTunnel()
                MainActivity.instance?.notifyVpnDisconnected()
                return
            }

            isServiceRunning = true
            startLiveNotificationTicker()

        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed: ${e.message}", e)
            stopTunnel()
            MainActivity.instance?.notifyVpnDisconnected()
        }
    }

    private fun stopTunnel() {
        try {
            isServiceRunning = false
            tickerJob?.cancel()
            tickerJob = null

            XrayBridge.stopCore()

            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.d(TAG, "VPN tunnel cleanly stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel: ${e.message}")
        }
    }

    private fun startLiveNotificationTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            val openAppIntent = Intent(this@SovraVpnService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pIntent = PendingIntent.getActivity(
                this@SovraVpnService,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val disconnectIntent = Intent(this@SovraVpnService, SovraVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val pDisconnectIntent = PendingIntent.getService(
                this@SovraVpnService,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()

            while (isActive && XrayBridge.isRunning) {
                val currRx = TrafficStats.getTotalRxBytes()
                val currTx = TrafficStats.getTotalTxBytes()

                var diffDown = if (lastRx > 0 && currRx >= lastRx) currRx - lastRx else 0L
                var diffUp = if (lastTx > 0 && currTx >= lastTx) currTx - lastTx else 0L

                val stats = XrayBridge.queryTrafficStats()
                if (stats.first > 0 || stats.second > 0) {
                    diffDown = stats.first
                    diffUp = stats.second
                }

                totalDownloadedBytes += diffDown
                totalUploadedBytes += diffUp

                lastRx = currRx
                lastTx = currTx

                val speedDownStr = formatSpeed(diffDown)
                val totalStr = formatBytes(totalDownloadedBytes + totalUploadedBytes)

                val notif = NotificationCompat.Builder(this@SovraVpnService, SovraApp.CHANNEL_ID)
                    .setContentTitle("اتصال امن SOVRA VPN")
                    .setContentText("$currentServerName | ↓ $speedDownStr | کل: $totalStr")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "قطع اتصال",
                        pDisconnectIntent
                    )
                    .build()

                startForeground(NOTIFICATION_ID, notif)
                nm.notify(NOTIFICATION_ID, notif)

                MainActivity.instance?.updateLiveTraffic(speedDownStr, totalStr)

                delay(1000)
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return if (bytesPerSec < 1024) {
            "${bytesPerSec} B/s"
        } else if (bytesPerSec < 1024 * 1024) {
            String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        } else {
            String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    private fun formatBytes(bytes: Long): String {
        return if (bytes < 1024 * 1024) {
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        } else if (bytes < 1024 * 1024 * 1024) {
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        } else {
            String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }
}
