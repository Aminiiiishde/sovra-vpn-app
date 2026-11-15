package com.sovra.vpn.model

data class ServerConfig(
    val id: String,
    val name: String,
    val countryCode: String,
    val flagEmoji: String,
    val protocol: String,
    val pingMs: Long = -1,
    val isAuto: Boolean = false,
    val rawUri: String = ""
)
