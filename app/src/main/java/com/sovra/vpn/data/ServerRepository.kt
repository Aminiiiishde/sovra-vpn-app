package com.sovra.vpn.data

import com.sovra.vpn.model.ServerConfig

object ServerRepository {

    val defaultServers = listOf(
        ServerConfig(
            id = "auto",
            name = "اتصال خودکار هوشمند (سریع‌ترین سرور)",
            countryCode = "AUTO",
            flagEmoji = "⚡",
            protocol = "Smart",
            pingMs = 42,
            isAuto = true
        ),
        ServerConfig(
            id = "de_tunnel_1",
            name = "آلمان 🇩🇪 - تونل اختصاصی SOVRA",
            countryCode = "DE",
            flagEmoji = "🇩🇪",
            protocol = "VLESS-WS",
            pingMs = 52,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:2052?alpn=http%2F1.1&encryption=none&fp=chrome&host=german.sovra.ir&path=%2Fgermany-ws&security=tls&sni=german.sovra.ir&type=ws#Germany-Tunnel"
        ),
        ServerConfig(
            id = "de_secure",
            name = "آلمان امن 🇩🇪 - پروتکل ضد فیلتر",
            countryCode = "DE",
            flagEmoji = "🇩🇪",
            protocol = "HTTPUpgrade",
            pingMs = 58,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:7822?encryption=none&security=none&type=httpupgrade&host=german.sovra.ir&path=%2Fvideogames%2Fv2%2Fsk#Germany-Secure"
        ),
        ServerConfig(
            id = "tr_tunnel",
            name = "ترکیه 🇹🇷 - پینگ پایین گیمینگ",
            countryCode = "TR",
            flagEmoji = "🇹🇷",
            protocol = "VLESS-WS",
            pingMs = 45,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:9951?encryption=none&security=none&type=ws&host=german.sovra.ir&path=%2Fturkey-ws#Turkey-Tunnel"
        ),
        ServerConfig(
            id = "us_ai",
            name = "آمریکا 🇺🇸 - مخصوص هوش مصنوعی و ترید",
            countryCode = "US",
            flagEmoji = "🇺🇸",
            protocol = "VLESS-Reality",
            pingMs = 120,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:2022?encryption=none&security=none&type=tcp#US-AI"
        ),
        ServerConfig(
            id = "fr_fast",
            name = "فرانسه 🇫🇷 - استریم پرسرعت",
            countryCode = "FR",
            flagEmoji = "🇫🇷",
            protocol = "VLESS-HTTPUpgrade",
            pingMs = 65,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:8388?encryption=none&security=none&type=httpupgrade#France-Fast"
        ),
        ServerConfig(
            id = "se_aws",
            name = "سوئد 🇸🇪 - کلود آمازون",
            countryCode = "SE",
            flagEmoji = "🇸🇪",
            protocol = "VLESS-Tunnel",
            pingMs = 72,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:4525?encryption=none&security=none&type=tcp#Sweden-AWS"
        )
    )
}
