package com.sovra.vpn.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sovra.vpn.R
import com.sovra.vpn.data.ServerRepository
import com.sovra.vpn.model.ServerConfig
import com.sovra.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isConnected: Boolean,
    onConnectToggle: () -> Unit,
    selectedServer: ServerConfig,
    onServerSelect: (ServerConfig) -> Unit,
    activeModeSpeed: Boolean,
    onModeToggle: (Boolean) -> Unit,
    isStaticIp: Boolean,
    onStaticIpToggle: (Boolean) -> Unit,
    batterySaver: Boolean,
    onBatterySaverToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var showServerSheet by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "shieldAnim")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )
    val neonPulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    fun triggerHaptic() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(40)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF03050C), Color(0xFF070C1A), Color(0xFF0A142A))
                )
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        // 1. Header with Brand Name and Badges
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.sovra_logo),
                    contentDescription = "Logo",
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "SOVRA VPN",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Freedom to Connect",
                        color = NeonBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(
                onClick = {
                    triggerHaptic()
                    onBatterySaverToggle(!batterySaver)
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF0E1A33))
                    .border(1.dp, Color(0xFF1E3566), CircleShape)
            ) {
                Icon(
                    imageVector = if (batterySaver) Icons.Default.BatteryChargingFull else Icons.Default.Bolt,
                    contentDescription = "Battery Saver",
                    tint = if (batterySaver) SuccessGreen else NeonBlue
                )
            }
        }

        // 2. Central 3D Metallic Shield with Official S Logo & Neon Aura
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(260.dp)
                .graphicsLayer {
                    translationY = if (!batterySaver) floatOffset else 0f
                }
                .clickable {
                    triggerHaptic()
                    onConnectToggle()
                }
        ) {
            if (isConnected && !batterySaver) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(NeonBlue.copy(alpha = 0.45f * neonPulse), Color.Transparent)
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .size(210.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF060B17))
                    .border(
                        width = if (isConnected) 3.dp else 1.5.dp,
                        brush = Brush.sweepGradient(
                            colors = if (isConnected) listOf(NeonBlue, ElectricCyan, Color(0xFF7B5CFF), NeonBlue)
                            else listOf(ShieldBorder, Color(0xFF20304C), ShieldBorder)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.sovra_logo),
                    contentDescription = "SOVRA Shield",
                    modifier = Modifier
                        .size(175.dp)
                        .scale(if (isConnected) 1.03f else 1.0f)
                )
            }
        }

        // 3. Status Display
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isConnected) "اتصال با امنیت کامل برقرار است" else "برای اتصال لمس کنید",
                color = if (isConnected) NeonBlue else TextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) SuccessGreen else WarningAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "پروتکل: ${selectedServer.protocol} | پینگ: ${selectedServer.pingMs}ms" else "آماده اتصال",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // 4. Live Speed & Usage Dashboard Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0B1426))
                    .border(1.dp, Color(0xFF1B2F55), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text("دانلود لحظه‌ای", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(if (isConnected) "4.8 MB/s" else "0.0 MB/s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0B1426))
                    .border(1.dp, Color(0xFF1B2F55), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text("اعتبار زمانی", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("۱۸ روز باقی‌مانده", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        // 5. Active Server Selector Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0C162B))
                .border(1.dp, if (isConnected) NeonBlue.copy(alpha = 0.6f) else Color(0xFF1C315A), RoundedCornerShape(18.dp))
                .clickable {
                    triggerHaptic()
                    showServerSheet = true
                }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedServer.flagEmoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(selectedServer.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${selectedServer.countryCode} • ${selectedServer.protocol}", color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedServer.pingMs}ms", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                }
            }
        }

        // 6. Connect / Disconnect Button
        Button(
            onClick = {
                triggerHaptic()
                onConnectToggle()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .shadow(
                    elevation = if (isConnected) 16.dp else 8.dp,
                    shape = RoundedCornerShape(27.dp),
                    spotColor = if (isConnected) ErrorNeonRed else NeonBlue
                ),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) Color(0xFF261019) else NeonBlue
            ),
            shape = RoundedCornerShape(27.dp),
            border = if (isConnected) androidx.compose.foundation.BorderStroke(1.5.dp, ErrorNeonRed) else null
        ) {
            Text(
                text = if (isConnected) "قطع اتصال" else "اتصال هوشمند",
                color = if (isConnected) ErrorNeonRed else Color(0xFF02131C),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp
            )
        }
    }

    if (showServerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showServerSheet = false },
            containerColor = Color(0xFF090E1D)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "لیست سرورهای اختصاصی SOVRA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(ServerRepository.defaultServers) { srv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (srv.id == selectedServer.id) Color(0xFF132242) else Color.Transparent)
                                .clickable {
                                    triggerHaptic()
                                    onServerSelect(srv)
                                    showServerSheet = false
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(srv.flagEmoji, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(srv.name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(srv.protocol, color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                            Text("${srv.pingMs}ms", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
