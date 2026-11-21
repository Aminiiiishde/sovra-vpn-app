package com.sovra.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import go.Seq
import libv2ray.Libv2ray

class SovraApp : Application() {

    companion object {
        const val CHANNEL_ID = "sovra_vpn_status_channel"
        lateinit var instance: SovraApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        try {
            Seq.setContext(applicationContext)
            val assetDir = getDir("assets", Context.MODE_PRIVATE)
            val assetPath = assetDir.absolutePath
            val deviceId = "sovra_xudp_device_key"
            Libv2ray.initCoreEnv(assetPath, deviceId)
            Log.i("SovraApp", "Libv2ray.initCoreEnv initialized successfully at $assetPath")
        } catch (e: Throwable) {
            Log.e("SovraApp", "Failed to init Libv2ray env: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOVRA VPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time status and live connection details of SOVRA VPN"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
