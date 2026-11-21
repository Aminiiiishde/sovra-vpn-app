package com.sovra.vpn.core

import android.util.Base64
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

        // 3. Inbounds: socks (local port 10808) AND tun (xray0)
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

        // 4. Outbounds: proxy (1st priority), direct, block
        val outbounds = JSONArray()

        // 4.1 Proxy outbound (1st priority)
        outbounds.put(generateProxyOutbound(uri, cleanIp))

        // 4.2 Direct outbound for LAN & direct bypass
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                    put("happyEyeballs", JSONObject().apply {
                        put("tryDelayMs", 250)
                        put("interleave", 2)
                    })
                })
            })
        }
        outbounds.put(directOutbound)

        // 4.3 Block outbound for ad/malware
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        }
        outbounds.put(blockOutbound)
        root.put("outbounds", outbounds)

        // 5. Routing: Direct LAN private IPs, all other traffic flows through proxy
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            val rules = JSONArray()

            // Direct private IPs (LAN bypass)
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray().apply {
                    put("10.0.0.0/8")
                    put("172.16.0.0/12")
                    put("192.168.0.0/16")
                    put("127.0.0.0/8")
                })
            })
            put("rules", rules)
        }
        root.put("routing", routing)

        return root.toString(2)
    }

    private fun generateProxyOutbound(uri: String, cleanIp: String = ""): JSONObject {
        val clean = uri.trim()
        return when {
            clean.startsWith("vless://") -> parseVless(clean, cleanIp)
            clean.startsWith("vmess://") -> parseVmess(clean, cleanIp)
            clean.startsWith("trojan://") -> parseTrojan(clean, cleanIp)
            else -> throw IllegalArgumentException("Unsupported protocol: $clean")
        }
    }

    private fun parseVless(uri: String, cleanIp: String = ""): JSONObject {
        val raw = uri.removePrefix("vless://")
        val mainPart = raw.split("#")[0]
        val atSplit = mainPart.split("@")
        val uuid = atSplit[0]
        val hostAndQuery = atSplit[1].split("?")
        val hostPort = hostAndQuery[0].split(":")
        val rawAddress = hostPort[0]
        val port = hostPort[1].toInt()

        val serverAddress = if (cleanIp.isNotEmpty()) cleanIp else rawAddress

        val queryMap = mutableMapOf<String, String>()
        if (hostAndQuery.size > 1) {
            hostAndQuery[1].split("&").forEach {
                val kv = it.split("=")
                if (kv.size == 2) queryMap[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
            }
        }

        val transport = queryMap["type"] ?: "tcp"
        val security = queryMap["security"] ?: "none"
        val path = queryMap["path"] ?: "/"
        val host = queryMap["host"] ?: ""
        val sni = queryMap["sni"] ?: host
        val fp = queryMap["fp"] ?: "chrome"
        val alpn = queryMap["alpn"] ?: ""
        val pbk = queryMap["pbk"] ?: ""
        val sid = queryMap["sid"] ?: ""
        val spx = queryMap["spx"] ?: ""
        val flow = queryMap["flow"] ?: ""
        val encryption = queryMap["encryption"] ?: "none"
        val serviceName = queryMap["serviceName"] ?: ""
        val mode = queryMap["mode"] ?: "auto"

        return buildOutboundJson(
            protocol = "vless",
            serverAddress = serverAddress,
            serverPort = port,
            uuid = uuid,
            transport = transport,
            security = security,
            path = path,
            host = host,
            publicKey = pbk,
            shortId = sid,
            spiderX = spx,
            sni = sni,
            fingerprint = fp,
            alpn = alpn,
            flow = flow,
            encryption = encryption,
            serviceName = serviceName,
            mode = mode
        )
    }

    private fun parseVmess(uri: String, cleanIp: String = ""): JSONObject {
        val raw = uri.removePrefix("vmess://")
        val decoded = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        val json = JSONObject(decoded)

        val rawAddress = json.optString("add", "")
        val port = json.optInt("port", 443)
        val uuid = json.optString("id", "")
        val aid = json.optInt("aid", 0)
        val transport = json.optString("net", "tcp")
        val security = json.optString("tls", "none")
        val path = json.optString("path", "/")
        val host = json.optString("host", "")
        val sni = json.optString("sni", host)
        val alpn = json.optString("alpn", "")
        val fp = json.optString("fp", "chrome")
        val type = json.optString("type", "none")

        val serverAddress = if (cleanIp.isNotEmpty()) cleanIp else rawAddress

        return buildOutboundJson(
            protocol = "vmess",
            serverAddress = serverAddress,
            serverPort = port,
            uuid = uuid,
            transport = transport,
            security = if (security == "tls") "tls" else "none",
            path = path,
            host = host,
            sni = sni,
            fingerprint = fp,
            alpn = alpn
        )
    }

    private fun parseTrojan(uri: String, cleanIp: String = ""): JSONObject {
        val raw = uri.removePrefix("trojan://")
        val mainPart = raw.split("#")[0]
        val atSplit = mainPart.split("@")
        val password = atSplit[0]
        val hostAndQuery = atSplit[1].split("?")
        val hostPort = hostAndQuery[0].split(":")
        val rawAddress = hostPort[0]
        val port = hostPort[1].toInt()

        val serverAddress = if (cleanIp.isNotEmpty()) cleanIp else rawAddress

        val queryMap = mutableMapOf<String, String>()
        if (hostAndQuery.size > 1) {
            hostAndQuery[1].split("&").forEach {
                val kv = it.split("=")
                if (kv.size == 2) queryMap[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
            }
        }

        val transport = queryMap["type"] ?: "tcp"
        val security = queryMap["security"] ?: "tls"
        val path = queryMap["path"] ?: "/"
        val host = queryMap["host"] ?: ""
        val sni = queryMap["sni"] ?: host
        val fp = queryMap["fp"] ?: "chrome"
        val alpn = queryMap["alpn"] ?: ""

        return buildOutboundJson(
            protocol = "trojan",
            serverAddress = serverAddress,
            serverPort = port,
            uuid = password,
            transport = transport,
            security = security,
            path = path,
            host = host,
            sni = sni,
            fingerprint = fp,
            alpn = alpn
        )
    }

    private fun buildOutboundJson(
        protocol: String,
        serverAddress: String,
        serverPort: Int,
        uuid: String,
        transport: String = "tcp",
        security: String = "none",
        path: String = "/",
        host: String = "",
        publicKey: String = "",
        shortId: String = "",
        spiderX: String = "",
        sni: String = "",
        fingerprint: String = "chrome",
        alpn: String = "",
        flow: String = "",
        encryption: String = "none",
        serviceName: String = "",
        mode: String = "auto"
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
                put("level", 8)
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
                        put("encryption", encryption.ifEmpty { "none" })
                        if (flow.isNotEmpty()) {
                            put("flow", flow)
                        }
                        if (protocol == "vmess") {
                            put("alterId", 0)
                            put("security", "auto")
                        }
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
        if (security.isNotEmpty() && security != "none") {
            streamSettings.put("security", security)
        }

        // Transport Settings
        when (transport) {
            "ws" -> {
                streamSettings.put("wsSettings", JSONObject().apply {
                    put("path", path.ifEmpty { "/" })
                    if (host.isNotEmpty()) {
                        put("host", host)
                        val headers = JSONObject()
                        headers.put("Host", host)
                        put("headers", headers)
                    }
                })
            }
            "httpupgrade" -> {
                streamSettings.put("httpupgradeSettings", JSONObject().apply {
                    put("path", path.ifEmpty { "/" })
                    if (host.isNotEmpty()) {
                        put("host", host)
                    }
                })
            }
            "xhttp" -> {
                streamSettings.put("xhttpSettings", JSONObject().apply {
                    put("path", path.ifEmpty { "/" })
                    if (host.isNotEmpty()) {
                        put("host", host)
                    }
                    put("mode", mode.ifEmpty { "auto" })
                })
            }
            "tcp" -> {
                streamSettings.put("tcpSettings", JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("type", "none")
                    })
                })
            }
            "grpc" -> {
                streamSettings.put("grpcSettings", JSONObject().apply {
                    put("serviceName", serviceName.ifEmpty { path })
                    put("multiMode", mode == "multi")
                })
            }
        }

        // Security Settings - STRICTLY NO "allowInsecure" for Xray v26+!
        if (security == "tls") {
            streamSettings.put("tlsSettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { host.ifEmpty { serverAddress } })
                put("fingerprint", fingerprint.ifEmpty { "chrome" })
                if (alpn.isNotEmpty()) {
                    val alpnArray = JSONArray()
                    alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        alpnArray.put(it)
                    }
                    put("alpn", alpnArray)
                }
            })
        } else if (security == "reality") {
            streamSettings.put("realitySettings", JSONObject().apply {
                put("serverName", sni.ifEmpty { serverAddress })
                put("fingerprint", fingerprint.ifEmpty { "chrome" })
                put("publicKey", publicKey)
                put("shortId", shortId)
                if (spiderX.isNotEmpty()) {
                    put("spiderX", spiderX)
                }
            })
        }

        outbound.put("streamSettings", streamSettings)
        return outbound
    }
}
