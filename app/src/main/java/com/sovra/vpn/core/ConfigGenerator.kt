package com.sovra.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object ConfigGenerator {

    fun generateSpeedtestConfig(uri: String, cleanIp: String = ""): String {
        val root = JSONObject()
        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

        val outbounds = JSONArray()
        val proxyOutbound = generateProxyOutbound(uri, cleanIp)
        outbounds.put(proxyOutbound)
        root.put("outbounds", outbounds)

        return root.toString(2)
    }

    fun generateFromUri(uri: String, cleanIp: String = ""): String {
        val root = JSONObject()

        // 1. Logs
        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

        // 2. Policy & Stats
        val policy = JSONObject().apply {
            val levels = JSONObject().apply {
                val lvl8 = JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                }
                put("8", lvl8)
            }
            put("levels", levels)
            val system = JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
            put("system", system)
        }
        root.put("policy", policy)
        root.put("stats", JSONObject())

        // 3. Inbounds: socks AND tun (exact v2rayNG specification)
        val inbounds = JSONArray()

        val socksInbound = JSONObject().apply {
            put("tag", "socks")
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                    put("quic")
                })
            })
        }
        inbounds.put(socksInbound)

        val tunInbound = JSONObject().apply {
            put("tag", "tun")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("MTU", 1500)
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                    put("quic")
                })
            })
        }
        inbounds.put(tunInbound)
        root.put("inbounds", inbounds)

        // 4. Outbounds: proxy first, then direct, then block
        val outbounds = JSONArray()
        outbounds.put(generateProxyOutbound(uri, cleanIp))

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                })
            })
        }
        outbounds.put(directOutbound)

        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        }
        outbounds.put(blockOutbound)
        root.put("outbounds", outbounds)

        // 5. Routing: default routes all inbound traffic to first outbound (proxy)
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray())
        }
        root.put("routing", routing)

        // 6. DNS
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
            })
        }
        root.put("dns", dns)

        return root.toString(2)
    }

    private fun generateProxyOutbound(uri: String, cleanIp: String): JSONObject {
        return try {
            if (uri.startsWith("vless://")) {
                parseVlessOutbound(uri, cleanIp)
            } else if (uri.startsWith("trojan://")) {
                parseTrojanOutbound(uri, cleanIp)
            } else if (uri.startsWith("vmess://")) {
                parseVmessOutbound(uri, cleanIp)
            } else {
                defaultOutbound(cleanIp)
            }
        } catch (e: Exception) {
            defaultOutbound(cleanIp)
        }
    }

    private fun defaultOutbound(cleanIp: String): JSONObject {
        return buildOutboundObject(
            protocol = "vless",
            serverAddress = cleanIp.ifEmpty { "77.90.52.130" },
            serverPort = 2052,
            uuid = "741fc821-4acb-4b77-9c37-8862308db928",
            transport = "ws",
            path = "/german",
            host = "german.sovra.ir",
            security = "none",
            sni = "german.sovra.ir"
        )
    }

    private fun parseVlessOutbound(uri: String, cleanIp: String): JSONObject {
        val clean = uri.removePrefix("vless://").split("#")[0]
        val parts = clean.split("@")
        val uuid = parts[0]
        val hostPortAndQuery = parts[1].split("?")
        val hostPort = hostPortAndQuery[0].split(":")
        val rawAddress = hostPort[0]
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
        val sni = queryMap["sni"] ?: (if (host.isNotEmpty()) host else rawAddress)
        val pbk = queryMap["pbk"] ?: ""
        val sid = queryMap["sid"] ?: ""

        val finalAddress = if (cleanIp.isNotEmpty() && (host.isNotEmpty() || sni.isNotEmpty())) cleanIp else rawAddress

        return buildOutboundObject(
            protocol = "vless",
            serverAddress = finalAddress,
            serverPort = port,
            uuid = uuid,
            transport = type,
            path = path,
            host = host.ifEmpty { rawAddress },
            security = security,
            sni = sni,
            publicKey = pbk,
            shortId = sid
        )
    }

    private fun parseTrojanOutbound(uri: String, cleanIp: String): JSONObject {
        val clean = uri.removePrefix("trojan://").split("#")[0]
        val parts = clean.split("@")
        val password = parts[0]
        val hostPortAndQuery = parts[1].split("?")
        val hostPort = hostPortAndQuery[0].split(":")
        val rawAddress = hostPort[0]
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
        val sni = queryMap["sni"] ?: rawAddress

        val finalAddress = if (cleanIp.isNotEmpty()) cleanIp else rawAddress

        return buildOutboundObject(
            protocol = "trojan",
            serverAddress = finalAddress,
            serverPort = port,
            uuid = password,
            transport = type,
            security = security,
            sni = sni
        )
    }

    private fun parseVmessOutbound(uri: String, cleanIp: String): JSONObject {
        val b64 = uri.removePrefix("vmess://")
        val jsonStr = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val obj = JSONObject(jsonStr)
        val rawAddress = obj.optString("add", "77.90.52.130")
        val host = obj.optString("host", "")
        val sni = obj.optString("sni", "")

        val finalAddress = if (cleanIp.isNotEmpty() && (host.isNotEmpty() || sni.isNotEmpty())) cleanIp else rawAddress

        return buildOutboundObject(
            protocol = "vmess",
            serverAddress = finalAddress,
            serverPort = obj.optInt("port", 443),
            uuid = obj.optString("id", "741fc821-4acb-4b77-9c37-8862308db928"),
            transport = obj.optString("net", "ws"),
            path = obj.optString("path", "/"),
            host = host.ifEmpty { rawAddress },
            security = obj.optString("tls", "tls"),
            sni = sni.ifEmpty { host.ifEmpty { rawAddress } }
        )
    }

    private fun buildOutboundObject(
        protocol: String,
        serverAddress: String,
        serverPort: Int,
        uuid: String,
        transport: String = "ws",
        path: String = "/ws",
        host: String = "",
        security: String = "none",
        publicKey: String = "",
        shortId: String = "",
        sni: String = ""
    ): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", protocol)

        val settings = JSONObject()
        if (protocol == "trojan") {
            val servers = JSONArray()
            servers.put(JSONObject().apply {
                put("address", serverAddress)
                put("port", serverPort)
                put("password", uuid)
            })
            settings.put("servers", servers)
        } else {
            val vnext = JSONArray()
            val serverObj = JSONObject().apply {
                put("address", serverAddress)
                put("port", serverPort)
                val users = JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", uuid)
                        put("encryption", "none")
                        put("level", 8)
                    })
                }
                put("users", users)
            }
            vnext.put(serverObj)
            settings.put("vnext", vnext)
        }
        outbound.put("settings", settings)

        // Stream Settings
        val streamSettings = JSONObject()
        streamSettings.put("network", transport)
        streamSettings.put("security", security)

        if (transport == "ws") {
            streamSettings.put("wsSettings", JSONObject().apply {
                put("path", path.ifEmpty { "/" })
                val headers = JSONObject()
                if (host.isNotEmpty()) headers.put("Host", host)
                put("headers", headers)
            })
        }

        if (security == "tls") {
            streamSettings.put("tlsSettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { host.ifEmpty { serverAddress } })
                put("allowInsecure", true)
            })
        } else if (security == "reality") {
            streamSettings.put("realitySettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { serverAddress })
                put("publicKey", publicKey)
                put("shortId", shortId)
                put("fingerprint", "chrome")
            })
        }

        outbound.put("streamSettings", streamSettings)
        return outbound
    }
}
