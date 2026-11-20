package com.sovra.vpn.core

import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
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
                addAddress("26.26.26.1", 24)
                addRoute("0.0.0.0", 0)
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")
                try {
                    addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
                }
                setBlocking(true)
            }

            val pfd = builder.establish()
            vpnInterface = pfd
            
            var tunFd = 0
            try {
                pfd?.fileDescriptor?.let { fdObj ->
                    val field = fdObj.javaClass.getDeclaredField("descriptor")
                    field.isAccessible = true
                    tunFd = field.getInt(fdObj)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract raw fd: ${e.message}")
            }

            Log.d(TAG, "VPN tunnel established with tunFd=$tunFd")
            XrayBridge.startCore(configJson, tunFd)

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
                delay(2000)
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

                val downStr = String.format("%.2f MB/s", rxSpeed)
                val upStr = String.format("%.2f MB/s", txSpeed)
                showNotification("⚡ SOVRA متصل است | دانلود: $downStr | آپلود: $upStr")

                withContext(Dispatchers.Main) {
                    val js = "if(typeof updateRealSpeed === 'function') updateRealSpeed('${downStr}', '${upStr}');"
                    MainActivity.instance?.getWebView()?.evaluateJavascript(js, null)
                }
            }
        }
    }

    private fun stopTunnel() {
        tickerJob?.cancel()
        tickerJob = null
        XrayBridge.stopCore()
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (ignored: Exception) {}
        stopForeground(true)
        stopSelf()
    }

    private fun showNotification(statusText: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SovraVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SovraApp.CHANNEL_ID)
            .setContentTitle("SOVRA VPN (سپر امن فعال)")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال فوری", disconnectIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
