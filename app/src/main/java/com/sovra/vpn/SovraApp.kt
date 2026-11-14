package com.sovra.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

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
