package com.sovra.vpn.core

import org.json.JSONArray
import org.json.JSONObject

object ConfigGenerator {

    fun generateXrayConfig(
        serverAddress: String,
        serverPort: Int,
        uuid: String,
        transport: String = "xhttp",
        path: String = "/xhttp",
        host: String = "",
        security: String = "reality",
        publicKey: String = "",
        shortId: String = "",
        sni: String = "",
        socksPort: Int = 10808
    ): String {
        val root = JSONObject()

        // 1. Inbounds: Socks5 for tun2socks / internal app routing
        val inbounds = JSONArray()
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", socksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply { put("http"); put("tls") })
            })
        }
        inbounds.put(socksInbound)
        root.put("inbounds", inbounds)

        // 2. Outbounds: Xray Core with reality & xhttp
        val outbounds = JSONArray()
        val mainOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                val vnext = JSONArray()
                val serverObj = JSONObject().apply {
                    put("address", serverAddress)
                    put("port", serverPort)
                    val users = JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", uuid)
                            put("encryption", "none")
                            put("flow", "")
                        })
                    }
                    put("users", users)
                }
                vnext.put(serverObj)
                put("vnext", vnext)
            })

            // StreamSettings (xhttp / reality)
            put("streamSettings", JSONObject().apply {
                put("network", transport)
                put("security", security)

                if (security == "reality") {
                    put("realitySettings", JSONObject().apply {
                        put("serverName", sni)
                        put("publicKey", publicKey)
                        put("shortId", shortId)
                        put("fingerprint", "chrome")
                    })
                }

                if (transport == "xhttp") {
                    put("xhttpSettings", JSONObject().apply {
                        put("path", path)
                        put("host", host)
                        put("mode", "auto")
                    })
                }
            })
        }
        outbounds.put(mainOutbound)
        root.put("outbounds", outbounds)

        return root.toString(2)
    }
}
