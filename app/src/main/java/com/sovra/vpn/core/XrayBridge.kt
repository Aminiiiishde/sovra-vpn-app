package com.sovra.vpn.core

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

object XrayBridge {
    private const val TAG = "XrayBridge"

    var isRunning = false
        private set

    private var coreController: CoreController? = null

    fun initEnv(context: Context) {
        try {
            Seq.setContext(context.applicationContext)
            val assetPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            Libv2ray.initCoreEnv(assetPath, "sovra_vpn_device_id_key")
            Log.i(TAG, "initEnv success at $assetPath")
        } catch (e: Throwable) {
            Log.w(TAG, "initEnv error: ${e.message}")
        }
    }

    fun startCore(configJson: String, tunFd: Int = 0): Boolean {
        return try {
            Log.d(TAG, "Starting Xray Core engine with tunFd=$tunFd...")
            stopCore()

            val handler = object : CoreCallbackHandler {
                override fun startup(): Long {
                    Log.i(TAG, "CoreCallbackHandler startup")
                    return 0L
                }
                override fun shutdown(): Long {
                    Log.i(TAG, "CoreCallbackHandler shutdown")
                    return 0L
                }
                override fun onEmitStatus(status: Long, msg: String?): Long {
                    return 0L
                }
            }

            val controller = Libv2ray.newCoreController(handler)
            coreController = controller

            controller.startLoop(configJson, tunFd)
            Log.i(TAG, "Xray Core startLoop executed successfully!")
            isRunning = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start Xray core: ${e.message}", e)
            isRunning = false
            false
        }
    }

    fun stopCore(): Boolean {
        return try {
            Log.d(TAG, "Stopping Xray Core...")
            coreController?.let { controller ->
                try {
                    controller.stopLoop()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error stopping controller loop: ${e.message}")
                }
            }
            coreController = null
            isRunning = false
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping core", e)
            false
        }
    }

    fun measureOutboundDelay(speedtestConfigJson: String, testUrl: String = "http://cp.cloudflare.com/generate_204"): Long {
        return try {
            val delay = Libv2ray.measureOutboundDelay(speedtestConfigJson, testUrl)
            if (delay > 0) delay else -1L
        } catch (e: Throwable) {
            Log.w(TAG, "Native measureOutboundDelay failed: ${e.message}")
            -1L
        }
    }

    fun queryTrafficStats(): Pair<Long, Long> {
        coreController?.let { controller ->
            try {
                val res = controller.queryAllOutboundTrafficStats() ?: ""
                var down = 0L
                var up = 0L
                res.split(";").forEach { item ->
                    val parts = item.split(":")
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().toLongOrNull() ?: 0L
                        if (key.contains("downlink")) down += value
                        if (key.contains("uplink")) up += value
                    }
                }
                return Pair(down, up)
            } catch (e: Throwable) {
                // ignore
            }
        }
        return Pair(0L, 0L)
    }
}
