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

        // 3. Inbounds: SOCKS (port 10808) & TUN (xray0) matching official v2rayNG / V2Box
        val inbounds = JSONArray()

        val socksInbound = JSONObject().apply {
            put("tag", "socks")
            put("port", 10808)
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

        // 4. Outbounds: proxy (1st priority), direct, block, and dns-out
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

        // 4.3 Block outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        }
        outbounds.put(blockOutbound)

        // 4.4 DNS outbound (essential for intercepting port 53 DNS queries)
        val dnsOutbound = JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        }
        outbounds.put(dnsOutbound)

        root.put("outbounds", outbounds)

        // 5. Built-in DNS module (resolves queries securely through remote proxy)
        val dns = JSONObject().apply {
            put("tag", "dns_tag")
            put("servers", JSONArray().apply {
                put("https+local://1.1.1.1/dns-query")
                put("1.1.1.1")
                put("8.8.8.8")
            })
        }
        root.put("dns", dns)

        // 6. Routing rules
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()

            // Rule 1 (Top Priority): Hijack all port 53 DNS queries to internal dns-out
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().apply {
                    put("tun")
                    put("socks")
                })
                put("port", "53")
                put("outboundTag", "dns-out")
            })

            // Rule 2: Route all DNS engine queries (dns_tag) through the remote proxy
            rules.put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().apply {
                    put("dns_tag")
                })
                put("outboundTag", "proxy")
            })

            // Rule 3: Direct local / private IP networks
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

            // Rule 4: Catch-all to proxy
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
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
            clean.startsWith("ss://") -> parseShadowsocks(clean, cleanIp)
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
        val decoded = String(Base64.decode(raw, Base64.DEFAULT or Base64.NO_WRAP))
        val vmessJson = JSONObject(decoded)

        val rawAddress = vmessJson.getString("add")
        val serverAddress = if (cleanIp.isNotEmpty()) cleanIp else rawAddress
        val port = vmessJson.getInt("port")
        val uuid = vmessJson.getString("id")
        val aid = vmessJson.optInt("aid", 0)
        val transport = vmessJson.optString("net", "tcp")
        val security = vmessJson.optString("tls", "none")
        val path = vmessJson.optString("path", "/")
        val host = vmessJson.optString("host", "")
        val sni = vmessJson.optString("sni", host)
        val alpn = vmessJson.optString("alpn", "")
        val fp = vmessJson.optString("fp", "chrome")

        return buildOutboundJson(
            protocol = "vmess",
            serverAddress = serverAddress,
            serverPort = port,
            uuid = uuid,
            alterId = aid,
            transport = transport,
            security = security,
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

    private fun parseShadowsocks(uri: String, cleanIp: String = ""): JSONObject {
        val raw = uri.removePrefix("ss://")
        val mainPart = raw.split("#")[0]

        val decodedPart: String
        val serverPart: String

        if (mainPart.contains("@")) {
            val atSplit = mainPart.split("@")
            decodedPart = try {
                String(Base64.decode(atSplit[0], Base64.DEFAULT or Base64.NO_WRAP))
            } catch (_: Exception) {
                atSplit[0]
            }
            serverPart = atSplit[1]
        } else {
            val fullDecoded = String(Base64.decode(mainPart, Base64.DEFAULT or Base64.NO_WRAP))
            val atSplit = fullDecoded.split("@")
            decodedPart = atSplit[0]
            serverPart = atSplit[1]
        }

        val methodPassword = decodedPart.split(":")
        val method = methodPassword[0]
        val password = methodPassword[1]

        val hostPort = serverPart.split(":")
        val rawAddress = hostPort[0]
        val port = hostPort[1].toInt()
        val serverAddress = if (cleanIp.isNotEmpty()) cleanIp else rawAddress

        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", "shadowsocks")
        val settings = JSONObject().apply {
            val servers = JSONArray()
            servers.put(JSONObject().apply {
                put("address", serverAddress)
                put("port", port)
                put("method", method)
                put("password", password)
                put("uot", true)
            })
            put("servers", servers)
        }
        outbound.put("settings", settings)
        return outbound
    }

    private fun buildOutboundJson(
        protocol: String,
        serverAddress: String,
        serverPort: Int,
        uuid: String,
        alterId: Int = 0,
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
            })
            settings.put("servers", servers)
        } else {
            val vnext = JSONArray()
            val server = JSONObject().apply {
                put("address", serverAddress)
                put("port", serverPort)
                val users = JSONArray()
                val user = JSONObject().apply {
                    put("id", uuid)
                    if (protocol == "vless") {
                        put("encryption", encryption)
                        if (flow.isNotBlank()) put("flow", flow)
                    } else if (protocol == "vmess") {
                        put("alterId", alterId)
                        put("security", "auto")
                    }
                }
                users.put(user)
                put("users", users)
            }
            vnext.put(server)
            settings.put("vnext", vnext)
        }
        outbound.put("settings", settings)

        // Stream Settings
        val stream = JSONObject()
        stream.put("network", transport)
        stream.put("security", security)

        // Security configuration
        if (security == "reality") {
            val realitySettings = JSONObject().apply {
                put("show", false)
                if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
                if (sni.isNotBlank()) put("serverName", sni)
                if (publicKey.isNotBlank()) put("publicKey", publicKey)
                if (shortId.isNotBlank()) put("shortId", shortId)
                if (spiderX.isNotBlank()) put("spiderX", spiderX)
            }
            stream.put("realitySettings", realitySettings)
        } else if (security == "tls") {
            val tlsSettings = JSONObject().apply {
                put("allowInsecure", false)
                if (sni.isNotBlank()) put("serverName", sni)
                if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
                if (alpn.isNotBlank()) {
                    val alpnArray = JSONArray()
                    alpn.split(",").forEach { alpnArray.put(it.trim()) }
                    put("alpn", alpnArray)
                }
            }
            stream.put("tlsSettings", tlsSettings)
        }

        // Transport configuration
        when (transport) {
            "ws" -> {
                val wsSettings = JSONObject().apply {
                    put("path", if (path.startsWith("/")) path else "/$path")
                    val headers = JSONObject()
                    if (host.isNotBlank()) headers.put("Host", host)
                    put("headers", headers)
                }
                stream.put("wsSettings", wsSettings)
            }
            "grpc" -> {
                val grpcSettings = JSONObject().apply {
                    val actualService = if (serviceName.isNotBlank()) serviceName else path
                    put("serviceName", actualService)
                    put("multiMode", mode == "multi")
                }
                stream.put("grpcSettings", grpcSettings)
            }
            "http", "h2" -> {
                val httpSettings = JSONObject().apply {
                    put("path", if (path.startsWith("/")) path else "/$path")
                    if (host.isNotBlank()) {
                        val hostArray = JSONArray()
                        host.split(",").forEach { hostArray.put(it.trim()) }
                        put("host", hostArray)
                    }
                }
                stream.put("httpSettings", httpSettings)
            }
        }

        outbound.put("streamSettings", stream)
        return outbound
    }
}
