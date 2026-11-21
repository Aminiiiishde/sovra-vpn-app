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

        // 4. Outbounds: proxy, dns-out, direct, block
        val outbounds = JSONArray()

        // 4.1 Proxy outbound (1st priority)
        outbounds.put(generateProxyOutbound(uri, cleanIp))

        // 4.2 DNS Outbound (CRITICAL: intercepts port 53 and resolves via encrypted proxy)
        val dnsOutbound = JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
            put("settings", JSONObject().apply {
                put("network", "tcp")
            })
        }
        outbounds.put(dnsOutbound)

        // 4.3 Direct outbound for LAN & direct bypass
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

        // 4.4 Block outbound for ad/malware
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        }
        outbounds.put(blockOutbound)
        root.put("outbounds", outbounds)

        // 5. Routing: Send port 53 to dns-out, private IP to direct, all other to proxy
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()

            // DNS rule: Route port 53 from tun and socks to dns-out
            val dnsRule = JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().apply {
                    put("tun")
                    put("socks")
                })
                put("outboundTag", "dns-out")
                put("port", "53")
            }
            rules.put(dnsRule)

            // Private network bypass
            val privateRule = JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray().apply {
                    put("geoip:private")
                })
            }
            rules.put(privateRule)

            put("rules", rules)
        }
        root.put("routing", routing)

        // 6. DNS: Clean upstream DNS servers queried through the proxy
        val dns = JSONObject().apply {
            put("hosts", JSONObject())
            val servers = JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
            }
            put("servers", servers)
        }
        root.put("dns", dns)

        return root.toString(2)
    }

    private fun generateProxyOutbound(uri: String, cleanIp: String): JSONObject {
        return try {
            val clean = uri.split("#")[0].trim()
            when {
                clean.startsWith("vless://") -> parseVlessOutbound(clean, cleanIp)
                clean.startsWith("trojan://") -> parseTrojanOutbound(clean, cleanIp)
                clean.startsWith("vmess://") -> parseVmessOutbound(clean, cleanIp)
                clean.startsWith("ss://") -> parseSsOutbound(clean, cleanIp)
                else -> defaultOutbound(cleanIp)
            }
        } catch (e: Exception) {
            defaultOutbound(cleanIp)
        }
    }

    private fun defaultOutbound(cleanIp: String): JSONObject {
        return buildOutboundObject(
            protocol = "vless",
            serverAddress = cleanIp.ifEmpty { "tun.sovra.ir" },
            serverPort = 7822,
            uuid = "741fc821-4acb-4b77-9c37-8862308db928",
            transport = "httpupgrade",
            path = "/videogames/v2/sk",
            host = "german.sovra.ir",
            security = "tls",
            sni = "german.sovra.ir"
        )
    }

    private fun parseVlessOutbound(clean: String, cleanIp: String): JSONObject {
        val body = clean.removePrefix("vless://")
        val parts = body.split("@")
        val uuid = parts[0]
        val hp_q = parts[1].split("?", limit = 2)
        val hostPort = hp_q[0].split(":")
        val rawAddress = hostPort[0]
        val port = hostPort[1].split("/")[0].toIntOrNull() ?: 443

        val queryMap = mutableMapOf<String, String>()
        if (hp_q.size > 1) {
            hp_q[1].split("&").forEach { param ->
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) queryMap[kv[0]] = try { URLDecoder.decode(kv[1], "UTF-8") } catch (_: Exception) { kv[1] }
            }
        }

        val type = queryMap["type"] ?: "tcp"
        var security = queryMap["security"] ?: "none"
        val path = queryMap["path"] ?: "/"
        val host = queryMap["host"] ?: ""
        val sni = queryMap["sni"] ?: (if (host.isNotEmpty()) host else rawAddress)
        val pbk = queryMap["pbk"] ?: ""
        val sid = queryMap["sid"] ?: ""
        val spx = queryMap["spx"] ?: ""
        val fp = queryMap["fp"] ?: "chrome"
        val alpn = queryMap["alpn"] ?: ""
        val flow = queryMap["flow"] ?: ""
        val encryption = queryMap["encryption"] ?: "none"
        val serviceName = queryMap["serviceName"] ?: path
        val mode = queryMap["mode"] ?: "auto"

        // Xray v26 safety: Auto upgrade to TLS if sni/port suggests
        if (security == "none" && encryption == "none") {
            if (port == 443 || port == 8443 || port == 2053 || port == 2083 || port == 2087 || port == 2096 || sni.isNotEmpty()) {
                security = "tls"
            }
        }

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
            shortId = sid,
            spiderX = spx,
            fingerprint = fp,
            alpn = alpn,
            flow = flow,
            encryption = encryption,
            serviceName = serviceName,
            mode = mode
        )
    }

    private fun parseTrojanOutbound(clean: String, cleanIp: String): JSONObject {
        val body = clean.removePrefix("trojan://")
        val parts = body.split("@")
        val password = parts[0]
        val hp_q = parts[1].split("?", limit = 2)
        val hostPort = hp_q[0].split(":")
        val rawAddress = hostPort[0]
        val port = hostPort[1].split("/")[0].toIntOrNull() ?: 443

        val queryMap = mutableMapOf<String, String>()
        if (hp_q.size > 1) {
            hp_q[1].split("&").forEach { param ->
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) queryMap[kv[0]] = try { URLDecoder.decode(kv[1], "UTF-8") } catch (_: Exception) { kv[1] }
            }
        }

        val type = queryMap["type"] ?: "tcp"
        val security = queryMap["security"] ?: "tls"
        val host = queryMap["host"] ?: ""
        val sni = queryMap["sni"] ?: (if (host.isNotEmpty()) host else rawAddress)
        val path = queryMap["path"] ?: "/"
        val fp = queryMap["fp"] ?: "chrome"
        val alpn = queryMap["alpn"] ?: ""

        val finalAddress = if (cleanIp.isNotEmpty() && (host.isNotEmpty() || sni.isNotEmpty())) cleanIp else rawAddress

        return buildOutboundObject(
            protocol = "trojan",
            serverAddress = finalAddress,
            serverPort = port,
            uuid = password,
            transport = type,
            path = path,
            host = host.ifEmpty { rawAddress },
            security = security,
            sni = sni,
            fingerprint = fp,
            alpn = alpn
        )
    }

    private fun parseVmessOutbound(clean: String, cleanIp: String): JSONObject {
        val b64 = clean.removePrefix("vmess://")
        val pad = (4 - b64.length % 4) % 4
        val paddedB64 = b64 + "=".repeat(pad)
        val jsonStr = String(Base64.decode(paddedB64, Base64.DEFAULT))
        val obj = JSONObject(jsonStr)

        val rawAddress = obj.optString("add", "tun.sovra.ir")
        val portStr = obj.optString("port", "443").split("/")[0]
        val port = portStr.toIntOrNull() ?: 443
        val uuid = obj.optString("id", "")
        val net = obj.optString("net", "ws")
        val host = obj.optString("host", "")
        val path = obj.optString("path", "/")
        val tls = obj.optString("tls", "none")
        val sni = obj.optString("sni", if (host.isNotEmpty()) host else rawAddress)

        val finalAddress = if (cleanIp.isNotEmpty() && (host.isNotEmpty() || sni.isNotEmpty())) cleanIp else rawAddress

        return buildOutboundObject(
            protocol = "vmess",
            serverAddress = finalAddress,
            serverPort = port,
            uuid = uuid,
            transport = net,
            path = path,
            host = host.ifEmpty { rawAddress },
            security = tls,
            sni = sni
        )
    }

    private fun parseSsOutbound(clean: String, cleanIp: String): JSONObject {
        val body = clean.removePrefix("ss://")
        val userHost: String
        val hostPort: String
        if (body.contains("@")) {
            val parts = body.split("@", limit = 2)
            val pad = (4 - parts[0].length % 4) % 4
            val userPass = String(Base64.decode(parts[0] + "=".repeat(pad), Base64.DEFAULT))
            userHost = userPass
            hostPort = parts[1]
        } else {
            val pad = (4 - body.length % 4) % 4
            val decoded = String(Base64.decode(body + "=".repeat(pad), Base64.DEFAULT))
            val parts = decoded.split("@", limit = 2)
            userHost = parts[0]
            hostPort = parts[1]
        }

        val upParts = userHost.split(":", limit = 2)
        val method = upParts[0]
        val password = if (upParts.size > 1) upParts[1] else ""

        val hp = hostPort.split("?")[0].split(":")
        val rawAddress = hp[0]
        val port = hp[1].split("/")[0].toIntOrNull() ?: 8388

        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "shadowsocks")
            put("settings", JSONObject().apply {
                val servers = JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", rawAddress)
                        put("port", port)
                        put("method", method)
                        put("password", password)
                        put("level", 8)
                    })
                }
                put("servers", servers)
            })
            put("streamSettings", JSONObject().apply {
                put("network", "tcp")
            })
        }
        return outbound
    }

    private fun buildOutboundObject(
        protocol: String,
        serverAddress: String,
        serverPort: Int,
        uuid: String,
        transport: String = "ws",
        path: String = "/",
        host: String = "",
        security: String = "none",
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
