package com.pocketnode.ui.lightning

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import com.pocketnode.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.pocketnode.lightning.LightningService
import com.pocketnode.service.BitcoindService
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus

// Color scheme: dark + white, orange accent for primary action only
private val BitcoinOrange = Color(0xFFFF9800)
private val SubtleGrey = Color(0xFF888888)
private val DimWhite = Color(0xFFCCCCCC)
private val SuccessGreen = Color(0xFF4CAF50)
private val CardBg = Color(0xFF1A1A1A)

/**
 * Lightning Pay — the wallet home screen.
 *
 * Shown when an active Lightning channel exists.
 * Pay and Receive up front. Details below the fold.
 * Dark + white with orange as the single accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightningPayScreen(
    onNavigateToLightning: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {},
    onNavigateToReceive: () -> Unit = {},
    onNavigateToPaymentHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val lightning = remember { LightningService.getInstance(context) }
    val lightningState by LightningService.stateFlow.collectAsState()
    val serviceRunning by BitcoindService.isRunningFlow.collectAsState()

    // Auto-start node if not running
    LaunchedEffect(serviceRunning) {
        if (!serviceRunning) {
            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
            if (creds != null) {
                // Node has been set up before, start it
                val intent = Intent(context, BitcoindService::class.java)
                context.startForegroundService(intent)
            }
        }
    }

    // Auto-start Lightning once bitcoind is running
    LaunchedEffect(serviceRunning, lightningState.status) {
        if (serviceRunning && lightningState.status == LightningService.LightningState.Status.STOPPED) {
            // Small delay to let bitcoind fully initialize
            kotlinx.coroutines.delay(3000)
            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
            if (creds != null) {
                try {
                    lightning.start(creds.first, creds.second)
                } catch (_: Exception) {}
            }
        }
    }

    // Bootstrap status
    val nodeRunning = serviceRunning
    val ldkRunning = lightningState.status == LightningService.LightningState.Status.RUNNING
    val hasActiveChannel = ldkRunning && lightningState.channelCount > 0
    val hasUsableChannel = ldkRunning && lightningState.usableChannels > 0

    // Chain sync status
    val chainSynced = lightningState.chainSynced

    // Ready = node running + LDK running + active channel
    val allChecked = nodeRunning && hasActiveChannel
    // Initialize as ready if already running (e.g. returning from another screen)
    var isReady by remember { mutableStateOf(allChecked) }
    // Pay requires chain sync
    val payReady = isReady && chainSynced && hasUsableChannel

    // Brief delay after all checks pass so user sees the ticks (only on cold start)
    LaunchedEffect(allChecked) {
        if (allChecked && !isReady) {
            kotlinx.coroutines.delay(1500)
            isReady = true
        } else if (!allChecked) {
            isReady = false
        }
    }

    // Balance (hidden until scrolled)
    val sendCapacitySats = lightningState.lightningBalanceSats
    val receiveCapacitySats = lightningState.totalInboundSats
    val onchainSats = lightningState.onchainBalanceSats

    // Recent payments
    // Recent Lightning payments only (no on-chain)
    val payments = remember(lightningState) {
        if (ldkRunning) lightning.listPayments()
            .filter { it.status == PaymentStatus.SUCCEEDED && it.kind !is PaymentKind.Onchain }
            .sortedByDescending { it.latestUpdateTimestamp }
            .take(5)
        else emptyList()
    }

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bootstrap status (only when not ready)
            if (!isReady) {
                // Settings access during loading (e.g. after channel close)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onNavigateToLightning) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = SubtleGrey
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                BootstrapStatus(
                    nodeRunning = nodeRunning,
                    ldkStatus = lightningState.status,
                    channelCount = lightningState.channelCount,
                    error = lightningState.error
                )

                Spacer(modifier = Modifier.weight(1f))
            }

            if (isReady) {
                // Push Pay button to center of visible area
                Spacer(modifier = Modifier.height(80.dp))

                Text(
                    "⚡",
                    fontSize = 48.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(40.dp))
            }

            // PAY button — the hero
            Button(
                onClick = onNavigateToScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (payReady) BitcoinOrange else BitcoinOrange.copy(alpha = 0.3f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                enabled = payReady
            ) {
                if (isReady && !chainSynced) {
                    // Bootstrap done but waiting for chain sync
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Syncing...",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "Pay",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // RECEIVE button — smaller, understated
            OutlinedButton(
                onClick = onNavigateToReceive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 72.dp)
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DimWhite
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = isReady
            ) {
                Text(
                    "Receive",
                    fontSize = 16.sp
                )
            }

            if (isReady) {
                Spacer(modifier = Modifier.height(400.dp))
            }

            // === Below the fold: details ===
            if (isReady) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    color = Color(0xFF333333)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Balance + peer count row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Balance",
                        style = MaterialTheme.typography.titleSmall,
                        color = SubtleGrey
                    )
                    com.pocketnode.ui.components.PeerCountBadge()
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        BalanceRow("Can send", sendCapacitySats)
                        Spacer(modifier = Modifier.height(8.dp))
                        BalanceRow("Can receive", receiveCapacitySats)
                        if (onchainSats > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFF333333))
                            Spacer(modifier = Modifier.height(8.dp))
                            BalanceRow("On-chain", onchainSats)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Recent payments
                if (payments.isNotEmpty()) {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.titleSmall,
                        color = SubtleGrey,
                        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            payments.forEachIndexed { index, payment ->
                                val isInbound = payment.direction == PaymentDirection.INBOUND
                                val amountSats = payment.amountMsat?.let { it.toLong() / 1000 } ?: 0L
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (isInbound) "⬇️ Received" else "⬆️ Sent",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${if (isInbound) "+" else "-"}${"%,d".format(amountSats)} sats",
                                        color = if (isInbound) SuccessGreen else DimWhite,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                if (index < payments.lastIndex) {
                                    HorizontalDivider(color = Color(0xFF333333))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = onNavigateToPaymentHistory,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text("View all payments", color = SubtleGrey)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Channel info
                Text(
                    "Channel",
                    style = MaterialTheme.typography.titleSmall,
                    color = SubtleGrey,
                    modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                val channels = remember(lightningState) { lightning.listChannels() }
                val peerAliases = remember {
                    context.getSharedPreferences("peer_aliases", android.content.Context.MODE_PRIVATE)
                }

                channels.filter { it.isUsable || it.isChannelReady }.forEach { ch ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val alias = peerAliases.getString(ch.counterpartyNodeId, null)
                            Text(
                                alias ?: ch.counterpartyNodeId.take(12) + "...",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val capacitySats = ch.channelValueSats.toLong()
                            Text(
                                "${"%,d".format(capacitySats)} sats capacity",
                                color = SubtleGrey,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (ch.isUsable) {
                                Text(
                                    "⚡ Active",
                                    color = SuccessGreen,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    color = Color(0xFF333333)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Built by
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.avatar_freeonlineuser),
                        contentDescription = "FreeOnlineUser",
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Built by @FreeOnlineUser",
                        color = SubtleGrey,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                TextButton(
                    onClick = onNavigateToLightning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = SubtleGrey,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Settings", color = SubtleGrey)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BootstrapStatus(
    nodeRunning: Boolean,
    ldkStatus: LightningService.LightningState.Status,
    channelCount: Int,
    error: String?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // Step indicators
                StatusStep("Bitcoin node", nodeRunning)
                StatusStep("Lightning node", ldkStatus == LightningService.LightningState.Status.RUNNING)
                StatusStep("Channel active", channelCount > 0 && ldkStatus == LightningService.LightningState.Status.RUNNING)


            }
        }
    }
}

@Composable
private fun StatusStep(label: String, ready: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (ready) "✓" else "○",
            color = if (ready) SuccessGreen else SubtleGrey,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        Text(
            label,
            color = if (ready) Color.White else SubtleGrey,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun BalanceRow(label: String, sats: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = SubtleGrey, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${"%,d".format(sats)} sats",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
