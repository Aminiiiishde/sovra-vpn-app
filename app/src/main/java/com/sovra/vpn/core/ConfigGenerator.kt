package com.sovra.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object ConfigGenerator {

    fun generateFromUri(uri: String): String {
        return try {
            if (uri.startsWith("vless://")) {
                parseVless(uri)
            } else if (uri.startsWith("trojan://")) {
                parseTrojan(uri)
            } else if (uri.startsWith("vmess://")) {
                parseVmess(uri)
            } else {
                generateXrayConfig(serverAddress = "77.90.52.130", serverPort = 2052, uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd")
            }
        } catch (e: Exception) {
            generateXrayConfig(serverAddress = "77.90.52.130", serverPort = 2052, uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd")
        }
    }

    private fun parseVless(uri: String): String {
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
        return generateXrayConfig(
            protocol = "vless",
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
    }

    private fun parseTrojan(uri: String): String {
        val clean = uri.removePrefix("trojan://").split("#")[0]
        val parts = clean.split("@")
        val password = parts[0]
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
        val type = queryMap["type"] ?: "tcp"
        val security = queryMap["security"] ?: "tls"
        val sni = queryMap["sni"] ?: address
        return generateXrayConfig(
            protocol = "trojan",
            serverAddress = address,
            serverPort = port,
            uuid = password,
            transport = type,
            security = security,
            sni = sni
        )
    }

    private fun parseVmess(uri: String): String {
        val b64 = uri.removePrefix("vmess://")
        val jsonStr = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val obj = JSONObject(jsonStr)
        return generateXrayConfig(
            protocol = "vmess",
            serverAddress = obj.optString("add", "77.90.52.130"),
            serverPort = obj.optInt("port", 443),
            uuid = obj.optString("id", "0ded6ec5-3bff-4653-b404-a842765ccdcd"),
            transport = obj.optString("net", "ws"),
            path = obj.optString("path", "/"),
            host = obj.optString("host", ""),
            security = obj.optString("tls", "tls"),
            sni = obj.optString("sni", "")
        )
    }

    fun generateXrayConfig(
        protocol: String = "vless",
        serverAddress: String,
        serverPort: Int,
        uuid: String,
        transport: String = "ws",
        path: String = "/ws",
        host: String = "",
        security: String = "none",
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

        // 2. Outbounds
        val outbounds = JSONArray()
        val mainOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", protocol)
            put("settings", JSONObject().apply {
                val servers = JSONArray()
                if (protocol == "trojan") {
                    servers.put(JSONObject().apply {
                        put("address", serverAddress)
                        put("port", serverPort)
                        put("password", uuid)
                    })
                    put("servers", servers)
                } else {
                    val vnext = JSONArray()
                    val serverObj = JSONObject().apply {
                        put("address", serverAddress)
                        put("port", serverPort)
                        val users = JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", uuid)
                                put("encryption", "none")
                            })
                        }
                        put("users", users)
                    }
                    vnext.put(serverObj)
                    put("vnext", vnext)
                }
            })

            // Stream Settings
            val streamSettings = JSONObject().apply {
                put("network", transport)
                put("security", security)
                
                if (transport == "ws") {
                    put("wsSettings", JSONObject().apply {
                        put("path", path.ifEmpty { "/" })
                        val headers = JSONObject()
                        if (host.isNotEmpty()) headers.put("Host", host)
                        put("headers", headers)
                    })
                }

                if (security == "tls") {
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", sni.ifEmpty { serverAddress })
                        put("allowInsecure", true)
                    })
                } else if (security == "reality") {
                    put("realitySettings", JSONObject().apply {
                        put("serverName", sni.ifEmpty { serverAddress })
                        put("publicKey", publicKey)
                        put("shortId", shortId)
                        put("fingerprint", "chrome")
                    })
                }
            }
            put("streamSettings", streamSettings)
        }
        outbounds.put(mainOutbound)

        // Direct outbound
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }
        outbounds.put(directOutbound)

        // DNS configuration
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https+local://1.1.1.1/dns-query")
                put("8.8.8.8")
                put("localhost")
            })
        }
        root.put("dns", dns)
        root.put("outbounds", outbounds)

        return root.toString(2)
    }
}
