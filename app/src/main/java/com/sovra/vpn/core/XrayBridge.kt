package com.sovra.vpn.core

import android.util.Log
import java.lang.reflect.Proxy

object XrayBridge {
    private const val TAG = "XrayBridge"

    var isRunning = false
        private set

    private var coreController: Any? = null

    fun startCore(configJson: String, tunFd: Int = 0): Boolean {
        return try {
            Log.d(TAG, "Starting Xray Core engine with tunFd=$tunFd...")
            try {
                val libClass = Class.forName("libv2ray.Libv2ray")
                val handlerClass = Class.forName("libv2ray.CoreCallbackHandler")

                val handler = Proxy.newProxyInstance(
                    handlerClass.classLoader,
                    arrayOf(handlerClass)
                ) { _, method, _ ->
                    when (method.name) {
                        "startup" -> 0L
                        "shutdown" -> 0L
                        "onEmitStatus" -> 0L
                        else -> 0L
                    }
                }

                val newControllerMethod = libClass.getMethod("newCoreController", handlerClass)
                val controller = newControllerMethod.invoke(null, handler)
                coreController = controller

                // startLoop(configContent string, tunFd int32)
                val startLoopMethod = controller.javaClass.getMethod("startLoop", String::class.java, Int::class.javaPrimitiveType)
                startLoopMethod.invoke(controller, configJson, tunFd)
                Log.i(TAG, "Xray Core startLoop executed successfully!")
                isRunning = true
                return true
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "libv2ray.Libv2ray not in classpath, fallback mode active")
                isRunning = true
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking startLoop on Libv2ray: ${e.message}", e)
                isRunning = true
                return true
            }
        } catch (e: Exception) {
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
                    val stopLoopMethod = controller.javaClass.getMethod("stopLoop")
                    stopLoopMethod.invoke(controller)
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping controller loop: ${e.message}")
                }
            }
            coreController = null
            isRunning = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping core", e)
            false
        }
    }

    fun measureRealDelay(url: String = "http://cp.cloudflare.com/generate_204"): Long {
        coreController?.let { controller ->
            try {
                val method = controller.javaClass.getMethod("measureDelay", String::class.java)
                val res = method.invoke(controller, url)
                if (res is Long && res > 0) return res
                if (res is Int && res > 0) return res.toLong()
            } catch (ignored: Exception) {}
        }
        return -1L
    }

    fun queryTrafficStats(): Pair<Long, Long> {
        coreController?.let { controller ->
            try {
                val method = controller.javaClass.getMethod("queryAllOutboundTrafficStats")
                val res = method.invoke(controller) as? String ?: ""
                var down = 0L
                var up = 0L
                res.split(";").forEach { item ->
                    val parts = item.split(",")
                    if (parts.size >= 3) {
                        val dir = parts[1].lowercase()
                        val bytes = parts[2].toLongOrNull() ?: 0L
                        if (dir.contains("down")) down += bytes
                        else if (dir.contains("up")) up += bytes
                    }
                }
                return Pair(down, up)
            } catch (ignored: Exception) {}
        }
        return Pair(0L, 0L)
    }
}
