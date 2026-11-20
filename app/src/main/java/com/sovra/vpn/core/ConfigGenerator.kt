package com.sovra.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object ConfigGenerator {

    fun generateFromUri(uri: String, cleanIp: String = ""): String {
        return try {
            if (uri.startsWith("vless://")) {
                parseVless(uri, cleanIp)
            } else if (uri.startsWith("trojan://")) {
                parseTrojan(uri, cleanIp)
            } else if (uri.startsWith("vmess://")) {
                parseVmess(uri, cleanIp)
            } else {
                generateXrayConfig(
                    serverAddress = cleanIp.ifEmpty { "77.90.52.130" },
                    serverPort = 2052,
                    uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd"
                )
            }
        } catch (e: Exception) {
            generateXrayConfig(
                serverAddress = cleanIp.ifEmpty { "77.90.52.130" },
                serverPort = 2052,
                uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd"
            )
        }
    }

    private fun parseVless(uri: String, cleanIp: String): String {
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

        return generateXrayConfig(
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

    private fun parseTrojan(uri: String, cleanIp: String): String {
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

        return generateXrayConfig(
            protocol = "trojan",
            serverAddress = finalAddress,
            serverPort = port,
            uuid = password,
            transport = type,
            security = security,
            sni = sni
        )
    }

    private fun parseVmess(uri: String, cleanIp: String): String {
        val b64 = uri.removePrefix("vmess://")
        val jsonStr = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val obj = JSONObject(jsonStr)
        val rawAddress = obj.optString("add", "77.90.52.130")
        val host = obj.optString("host", "")
        val sni = obj.optString("sni", "")

        val finalAddress = if (cleanIp.isNotEmpty() && (host.isNotEmpty() || sni.isNotEmpty())) cleanIp else rawAddress

        return generateXrayConfig(
            protocol = "vmess",
            serverAddress = finalAddress,
            serverPort = obj.optInt("port", 443),
            uuid = obj.optString("id", "0ded6ec5-3bff-4653-b404-a842765ccdcd"),
            transport = obj.optString("net", "ws"),
            path = obj.optString("path", "/"),
            host = host.ifEmpty { rawAddress },
            security = obj.optString("tls", "tls"),
            sni = sni.ifEmpty { host.ifEmpty { rawAddress } }
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

        // 1. Logs & Policy
        val log = JSONObject().apply {
            put("loglevel", "warning")
        }
        root.put("log", log)

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

        // 2. Inbounds: socks-in AND real TUN inbound
        val inbounds = JSONArray()

        // Inbound 1: Socks5
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", socksPort)
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

        // Inbound 2: Real TUN inbound for Android VPN
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

        // 3. Outbounds
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
                        put("serverName", sni.ifEmpty { host.ifEmpty { serverAddress } })
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

        // Block outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        }
        outbounds.put(blockOutbound)
        root.put("outbounds", outbounds)

        // 4. Routing: Inbounds ["tun", "socks-in"] -> Outbound "proxy"
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            val rules = JSONArray().apply {
                val r1 = JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply {
                        put("tun")
                        put("socks-in")
                    })
                    put("outboundTag", "proxy")
                }
                put(r1)
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        // 5. DNS configuration
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("localhost")
            })
        }
        root.put("dns", dns)

        return root.toString(2)
    }
}
