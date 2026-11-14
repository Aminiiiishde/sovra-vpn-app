package com.sovra.vpn.core

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sovra.vpn.SovraApp
import com.sovra.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
                setSession("SOVRA VPN")
                setMtu(1500)
                addAddress("26.26.26.1", 24)
                addRoute("0.0.0.0", 0)
                addDnsServer("1.1.1.1")
                addDnsServer("8.8.8.8")
                setBlocking(true)
            }

            vpnInterface = builder.establish()
            showNotification("متصل شد | ترافیک ایمن و پرسرعت")
            XrayBridge.startCore(configJson)

        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed: ${e.message}", e)
            stopTunnel()
        }
    }

    private fun stopTunnel() {
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
            .setContentTitle("SOVRA VPN")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", disconnectIntent)
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
