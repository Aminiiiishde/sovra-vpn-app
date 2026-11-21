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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: ""
                startTunnel(config)
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configJson: String) {
        try {
            val builder = Builder().apply {
                setSession("SOVRA VPN VIP")
                setMtu(1500)
                addAddress("172.19.0.1", 30)
                addRoute("0.0.0.0", 0)
                try {
                    addAddress("fdfe:dcba:9876::1", 126)
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
        }
    }

    private fun startLiveNotificationTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(1500)
                val nowTime = System.currentTimeMillis()
                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()
                val dt = (nowTime - lastTime) / 1000.0

                var rxSpeed = if (dt > 0 && currentRx >= lastRx) ((currentRx - lastRx) / dt) / (1024 * 1024) else 0.0
                var txSpeed = if (dt > 0 && currentTx >= lastTx) ((currentTx - lastTx) / dt) / (1024 * 1024) else 0.0

                val (xDown, xUp) = XrayBridge.queryTrafficStats()
                if (xDown > 0 && dt > 0) rxSpeed = (xDown / dt) / (1024 * 1024)
                if (xUp > 0 && dt > 0) txSpeed = (xUp / dt) / (1024 * 1024)

                lastRx = currentRx
                lastTx = currentTx
                lastTime = nowTime

                val downStr = if (rxSpeed >= 0.1) String.format("%.1f MB/s", rxSpeed) else String.format("%.0f KB/s", rxSpeed * 1024)
                val upStr = if (txSpeed >= 0.1) String.format("%.1f MB/s", txSpeed) else String.format("%.0f KB/s", txSpeed * 1024)

                updateForegroundNotification("دانلود: $downStr | آپلود: $upStr")
                MainActivity.instance?.pushLiveStats(downStr, upStr)
            }
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

        val notification = NotificationCompat.Builder(this, SovraApp.CHANNEL_ID)
            .setContentTitle("سپر امنیتی SOVRA VPN فعال است")
            .setContentText(statsText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
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
