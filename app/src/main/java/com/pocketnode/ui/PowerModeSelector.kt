package com.pocketnode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pocketnode.lightning.LightningService
import com.pocketnode.power.PowerModeManager

/**
 * Three-segment power mode toggle with info button.
 * [ ⚡ Max ] [ 🔋 Low ] [ 🚶 Away ]  (i)
 */
@Composable
fun PowerModeSelector(
    currentMode: PowerModeManager.Mode,
    onModeSelected: (PowerModeManager.Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = PowerModeManager.Mode.values()
    var showInfo by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lightningInstalled = remember {
        val filterDir = java.io.File(context.filesDir, "bitcoin/indexes/blockfilter/basic")
        filterDir.exists() && (filterDir.listFiles()?.size ?: 0) > 1
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode toggle (compressed to left)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            modes.forEach { mode ->
                val isSelected = mode == currentMode
                val bgColor = if (isSelected) {
                    when (mode) {
                        PowerModeManager.Mode.MAX -> Color(0xFFFF9800)
                        PowerModeManager.Mode.LOW -> Color(0xFF4CAF50)
                        PowerModeManager.Mode.AWAY -> Color(0xFF607D8B)
                    }
                } else {
                    Color.Transparent
                }
                val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .clickable { onModeSelected(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${mode.emoji} ${mode.label}",
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Info button
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "Power mode info",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Info dialog
    if (showInfo) {
        val (title, description) = when (currentMode) {
            PowerModeManager.Mode.MAX -> "⚡ Max Data" to (
                "Everything on, all the time.\n\n" +
                "• Continuous sync, 8 peers always connected\n" +
                "• Full mempool relay and oracle updates\n" +
                "• Electrum server fully active\n" +
                (if (lightningInstalled) "• Lightning fully active\n• Required for opening Lightning channels\n" else "") +
                "\nEstimated data: up to ~10 GB up and down per day\n\n" +
                "Best when: plugged in on WiFi.")

            PowerModeManager.Mode.LOW -> "🔋 Low Data" to (
                "Same WiFi, less data. Syncs every 15 minutes then disconnects.\n\n" +
                "• Burst sync to chain tip, then network off until next burst\n" +
                "• All services update during each burst\n" +
                (if (lightningInstalled) "• Sending and receiving Lightning payments doesn't need node connection\n• Force-close detection within 15 minutes\n" else "") +
                "• Opening your wallet keeps peers connected until you close it\n\n" +
                "Estimated data: ~100-200 MB/day\n\n" +
                "Best when: on WiFi but not plugged in.")

            PowerModeManager.Mode.AWAY -> "🚶 Away" to (
                "Conserves battery and cellular data. Syncs once per hour.\n\n" +
                "• Burst sync every 60 minutes, network off between\n" +
                (if (lightningInstalled) "• Sending and receiving Lightning payments doesn't need node connection\n• Lightning safety maintained (watchtower covers gaps)\n• Channel opens disabled\n" else "") +
                "• Opening your wallet keeps peers connected until you close it\n\n" +
                "Estimated data: ~25-50 MB/day\n\n" +
                "Best when: out on cellular, saving battery.")
        }

        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title) },
            text = { Text(description, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

/**
 * Burst sync status banner for Low Data and Away modes.
 * Shows next sync time, current sync progress, or wallet hold status.
 */
@Composable
fun BurstSyncBanner(
    burstState: PowerModeManager.BurstState,
    nextBurstMs: Long,
    walletConnected: Boolean = false,
    peerCount: Int = 0,
    modifier: Modifier = Modifier
) {
    // Initial sync hold takes highest priority
    val initialSyncHold by PowerModeManager.initialSyncHoldFlow.collectAsState()
    if (initialSyncHold) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xFFFF9800))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (peerCount > 0) "⚡ Syncing to chain tip ($peerCount peers)" else "⚡ Syncing to chain tip: connecting...",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    // Channel hold (channel open pending confirmation)
    val channelHold by PowerModeManager.channelHoldFlow.collectAsState()
    if (channelHold) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xFFFF9800))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚡ Network held: channel opening",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    // Wallet hold takes priority over burst state
    if (walletConnected) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xFF2196F3))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "📱 Wallet connected, network active",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        return
    }

    // Never show burst banner in Max mode
    val currentMode by PowerModeManager.modeFlow.collectAsState()
    if (currentMode == PowerModeManager.Mode.MAX) return

    if (burstState == PowerModeManager.BurstState.IDLE && nextBurstMs == 0L) return

    val text = when (burstState) {
        PowerModeManager.BurstState.SYNCING -> if (peerCount > 0) "⏳ Burst sync: connected ($peerCount peers)" else "⏳ Burst sync: connecting..."
        PowerModeManager.BurstState.WAITING -> if (peerCount > 0) return else {
            val remaining = nextBurstMs - System.currentTimeMillis()
            if (remaining > 0) {
                val minutes = (remaining / 60_000).toInt()
                "💤 Network paused, next sync in ${minutes}min"
            } else {
                "💤 Network paused, syncing soon..."
            }
        }
        PowerModeManager.BurstState.IDLE -> return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF607D8B))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}
