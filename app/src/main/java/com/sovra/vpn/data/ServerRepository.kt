package com.sovra.vpn.data

import com.sovra.vpn.model.ServerConfig

object ServerRepository {

    val defaultServers = listOf(
        ServerConfig(
            id = "auto",
            name = "اتصال خودکار هوشمند (بهترین پینگ)",
            countryCode = "AUTO",
            flagEmoji = "⚡",
            protocol = "Smart",
            pingMs = 42,
            isAuto = true
        ),
        ServerConfig(
            id = "de_vip_1",
            name = "🇩🇪 آلمان - تانل VIP 💎",
            countryCode = "DE",
            flagEmoji = "🇩🇪",
            protocol = "VLESS-WS",
            pingMs = 48,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:2052?alpn=http%2F1.1&encryption=none&fp=chrome&host=german.sovra.ir&path=%2Fgermany-ws&security=tls&sni=german.sovra.ir&type=ws#Germany-Tunnel"
        ),
        ServerConfig(
            id = "de_direct",
            name = "🇩🇪 آلمان (Fastly) - مستقیم اقتصادی ⚡️",
            countryCode = "DE",
            flagEmoji = "🇩🇪",
            protocol = "HTTPUpgrade",
            pingMs = 55,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:7822?encryption=none&security=none&type=httpupgrade&host=german.sovra.ir&path=%2Fvideogames%2Fv2%2Fsk#Germany-Direct"
        ),
        ServerConfig(
            id = "tr_vip",
            name = "🇹🇷 ترکیه - تانل VIP 💎",
            countryCode = "TR",
            flagEmoji = "🇹🇷",
            protocol = "VLESS-WS",
            pingMs = 45,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:9951?encryption=none&security=none&type=ws&host=german.sovra.ir&path=%2Fturkey-ws#Turkey-Tunnel"
        ),
        ServerConfig(
            id = "nl_vip",
            name = "🇳🇱 هلند - تانل VIP 💎",
            countryCode = "NL",
            flagEmoji = "🇳🇱",
            protocol = "VLESS-WS",
            pingMs = 52,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:2052?alpn=http%2F1.1&encryption=none&fp=chrome&host=german.sovra.ir&path=%2Fgermany-ws&security=tls&sni=german.sovra.ir&type=ws#Netherland-Tunnel"
        ),
        ServerConfig(
            id = "us_vip",
            name = "🇺🇸 آمریکا (Virginia) - تانل VIP 💎",
            countryCode = "US",
            flagEmoji = "🇺🇸",
            protocol = "VLESS-Reality",
            pingMs = 110,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:2022?encryption=none&security=none&type=tcp#US-AI"
        ),
        ServerConfig(
            id = "sg_vip",
            name = "🇸🇬 سنگاپور - تانل VIP 💎",
            countryCode = "SG",
            flagEmoji = "🇸🇬",
            protocol = "VLESS-HTTPUpgrade",
            pingMs = 135,
            rawUri = "vless://0ded6ec5-3bff-4653-b404-a842765ccdcd@tun.sovra.ir:8388?encryption=none&security=none&type=httpupgrade#Singapore-Fast"
        )
    )
}
