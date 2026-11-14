package com.sovra.vpn.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceFingerprint {

    @SuppressLint("HardwareIds")
    fun getHardwareId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val raw = "${Build.BOARD}|${Build.BRAND}|${Build.DEVICE}|${Build.MANUFACTURER}|${Build.MODEL}|$androidId"
            sha256(raw)
        } catch (e: Exception) {
            sha256("GENERIC_FALLBACK_DEVICE_ID")
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
