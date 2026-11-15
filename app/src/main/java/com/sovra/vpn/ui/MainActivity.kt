package com.sovra.vpn.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.sovra.vpn.core.ConfigGenerator
import com.sovra.vpn.core.SovraVpnService
import com.sovra.vpn.data.ServerRepository
import com.sovra.vpn.model.ServerConfig
import com.sovra.vpn.ui.theme.SovraTheme

class MainActivity : ComponentActivity() {

    private var pendingConnect = false
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && pendingConnect) {
            doStartVpn()
        }
        pendingConnect = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SovraTheme {
                var isConnected by remember { mutableStateOf(false) }
                var selectedServer by remember { mutableStateOf(ServerRepository.defaultServers[0]) }
                var isSpeedMode by remember { mutableStateOf(false) }
                var isStaticIp by remember { mutableStateOf(false) }
                var batterySaver by remember { mutableStateOf(false) }

                MainScreen(
                    isConnected = isConnected,
                    onConnectToggle = {
                        if (isConnected) {
                            doStopVpn()
                            isConnected = false
                        } else {
                            prepareAndConnect()
                            isConnected = true
                        }
                    },
                    selectedServer = selectedServer,
                    onServerSelect = { selectedServer = it },
                    activeModeSpeed = isSpeedMode,
                    onModeToggle = { isSpeedMode = it },
                    isStaticIp = isStaticIp,
                    onStaticIpToggle = { isStaticIp = it },
                    batterySaver = batterySaver,
                    onBatterySaverToggle = { batterySaver = it }
                )
            }
        }
    }

    private fun prepareAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnect = true
            vpnPermissionLauncher.launch(intent)
        } else {
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        val dummyConfig = ConfigGenerator.generateXrayConfig(
            serverAddress = "77.90.52.130",
            serverPort = 2052,
            uuid = "0ded6ec5-3bff-4653-b404-a842765ccdcd"
        )
        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_CONNECT
            putExtra(SovraVpnService.EXTRA_CONFIG_JSON, dummyConfig)
        }
        startService(serviceIntent)
    }

    private fun doStopVpn() {
        val serviceIntent = Intent(this, SovraVpnService::class.java).apply {
            action = SovraVpnService.ACTION_DISCONNECT
        }
        startService(serviceIntent)
    }
}
