package com.sovra.vpn.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sovra.vpn.ui.theme.*

@Composable
fun MainScreen(
    isConnected: Boolean,
    onConnectToggle: () -> Unit,
    activeModeSpeed: Boolean,
    onModeToggle: (Boolean) -> Unit,
    isStaticIp: Boolean,
    onStaticIpToggle: (Boolean) -> Unit,
    batterySaver: Boolean,
    onBatterySaverToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current

    // Pulsing Neon Animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected && !batterySaver) 1.06f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    fun triggerHaptic() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator?.vibrate(45)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(CyberDark, CyberSurface, Color(0xFF070A10))
                )
            )
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        // Top Bar: Logo & Status
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SOVRA VPN",
                    color = NeonBlue,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "نسل جدید امنیت و سرعت اینترنت",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            // Battery Saver Toggle
            IconButton(
                onClick = {
                    triggerHaptic()
                    onBatterySaverToggle(!batterySaver)
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(CyberCard)
            ) {
                Icon(
                    imageVector = if (batterySaver) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    contentDescription = "Battery Saver",
                    tint = if (batterySaver) SuccessGreen else TextSecondary
                )
            }
        }

        // Center: Interactive 3D/Neon Shield Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(240.dp)
                .scale(pulseScale)
                .clickable {
                    triggerHaptic()
                    onConnectToggle()
                }
        ) {
            // Neon Glow Rings
            if (isConnected && !batterySaver) {
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .clip(CircleShape)
                        .background(NeonBlueGlow)
                )
            }

            // Shield Surface
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isConnected) listOf(NeonBlue, ElectricCyan, CyberDark)
                            else listOf(CyberCard, ShieldBorder, CyberDark)
                        )
                    )
                    .border(
                        width = 3.dp,
                        color = if (isConnected) NeonBlue else ShieldBorder,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Security else Icons.Default.Shield,
                        contentDescription = "Shield",
                        tint = if (isConnected) Color.White else ShieldSilver,
                        modifier = Modifier.size(68.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isConnected) "متصل شد" else "لمس برای اتصال",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // Bottom Controls: Smart Connection Modes & Account Preview
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Selectors: Low Ping vs High Speed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberCard)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        triggerHaptic()
                        onModeToggle(false)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!activeModeSpeed) NeonBlue else Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = if (!activeModeSpeed) CyberDark else TextSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("پینگ گیمینگ (Low Ping)", color = if (!activeModeSpeed) CyberDark else TextSecondary, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        triggerHaptic()
                        onModeToggle(true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeModeSpeed) NeonBlue else Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = if (activeModeSpeed) CyberDark else TextSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("سرعت دانلود (Speed)", color = if (activeModeSpeed) CyberDark else TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Static IP Mode Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberCard)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = if (isStaticIp) NeonBlue else TextSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("حالت آی‌پی ثابت (مخصوص ترید)", color = TextPrimary, fontSize = 13.sp)
                }
                Switch(
                    checked = isStaticIp,
                    onCheckedChange = {
                        triggerHaptic()
                        onStaticIpToggle(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NeonBlue,
                        checkedTrackColor = CyberDark
                    )
                )
            }
        }
    }
}
