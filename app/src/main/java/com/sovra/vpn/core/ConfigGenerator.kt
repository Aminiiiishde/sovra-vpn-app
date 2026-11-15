package com.sovra.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object ConfigGenerator {

    fun generateFromUri(uri: String): String {
        if (!uri.startsWith("vless://")) {
            return generateXrayConfig(serverAddress = "77.90.52.130", serverPort = 2052, uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd")
        }
        return try {
            val clean = uri.removePrefix("vless://").split("#")[0]
            val parts = clean.split("@")
            val uuid = parts[0]
            val hostPortAndQuery = parts[1].split("?")
            val hostPort = hostPortAndQuery[0].split(":")
            val address = hostPort[0]
            val port = hostPort[1].toInt()
            val queryMap = mutableMapOf<String, String>()
            if (hostPortAndQuery.size > 1) {
                hostPortAndQuery[1].split("&").forEach { param ->
                    val kv = param.split("=")
                    if (kv.size == 2) queryMap[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            val type = queryMap["type"] ?: "ws"
            val security = queryMap["security"] ?: "none"
            val path = queryMap["path"] ?: ""
            val host = queryMap["host"] ?: ""
            val sni = queryMap["sni"] ?: host
            val pbk = queryMap["pbk"] ?: ""
            val sid = queryMap["sid"] ?: ""
            generateXrayConfig(
                serverAddress = address,
                serverPort = port,
                uuid = uuid,
                transport = type,
                path = path,
                host = host,
                security = security,
                sni = sni,
                publicKey = pbk,
                shortId = sid
            )
        } catch (e: Exception) {
            generateXrayConfig(serverAddress = "77.90.52.130", serverPort = 2052, uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd")
        }
    }

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

            // StreamSettings (xhttp / reality / ws)
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

                if (transport == "ws") {
                    put("wsSettings", JSONObject().apply {
                        put("path", path)
                        put("headers", JSONObject().apply {
                            if (host.isNotEmpty()) put("Host", host)
                        })
                    })
                }
            })
        }
        outbounds.put(mainOutbound)
        root.put("outbounds", outbounds)

        return root.toString(2)
    }
}
