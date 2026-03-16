package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import com.pocketnode.power.PowerModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Open a Lightning channel to a peer node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenChannelScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToPeerBrowser: () -> Unit = {},
    prefillNodeId: String = "",
    prefillAddress: String = "",
    prefillAlias: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lightning = remember { LightningService.getInstance(context) }
    val lightningState by LightningService.stateFlow.collectAsState()

    var nodeId by remember { mutableStateOf(prefillNodeId) }
    var address by remember { mutableStateOf(prefillAddress) }
    var amountSats by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var channelId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var anchorWarning by remember { mutableStateOf<String?>(null) }
    val powerMode by PowerModeManager.modeFlow.collectAsState()
    val needsMaxMode = powerMode != PowerModeManager.Mode.MAX
    // Fee state — persisted to SharedPreferences so it survives screen exits
    val feePrefs = remember { context.getSharedPreferences("fee_sync", android.content.Context.MODE_PRIVATE) }
    val cachedFee = remember { feePrefs.getString("fee_rate", null) }
    val cachedTime = remember { feePrefs.getLong("fee_time", 0L) }
    val isCacheFresh = remember { cachedFee != null && System.currentTimeMillis() - cachedTime < 30 * 60 * 1000 }
    var syncingFees by remember { mutableStateOf(false) }
    var feeSynced by remember { mutableStateOf(isCacheFresh) }
    var syncedFeeRate by remember { mutableStateOf(if (isCacheFresh) cachedFee else null) }

    // Auto-check fee on screen load if in Max mode or if we can reach bitcoind
    LaunchedEffect(Unit) {
        if (!feeSynced) {
            withContext(Dispatchers.IO) {
                val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                if (creds != null) {
                    try {
                        val rpc = com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second)
                        val params = org.json.JSONArray().apply { put(6) }
                        val result = rpc.call("estimatesmartfee", params)
                        val feeRate = result?.optDouble("feerate", -1.0) ?: -1.0
                        if (feeRate > 0) {
                            val satPerVb = feeRate * 100_000
                            val formatted = if (satPerVb < 10) "%.1f sat/vB".format(satPerVb) else "%.0f sat/vB".format(satPerVb)
                            syncedFeeRate = formatted
                            feeSynced = true
                            feePrefs.edit().putString("fee_rate", formatted).putLong("fee_time", System.currentTimeMillis()).apply()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Clean up network hold when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            if (syncingFees) {
                PowerModeManager.getInstance(context).releaseNetworkHold()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open Channel") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = { com.pocketnode.ui.components.PeerCountBadge() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Open a Lightning Channel", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connect to a Lightning peer and open a payment channel. You need on-chain funds in your Lightning wallet to open a channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Available: ${"%,d".format(lightningState.onchainBalanceSats)} sats",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF9800)
                    )
                }
            }

            // Browse peers button
            OutlinedButton(
                onClick = onNavigateToPeerBrowser,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("\uD83D\uDD0D Browse Peers")
            }

            // Selected peer name (above entry fields)
            if (prefillAlias.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f))
                ) {
                    Text(
                        "Selected: $prefillAlias",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Peer details (node ID + address)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nodeId,
                        onValueChange = { nodeId = it.trim(); error = null; result = null },
                        label = { Text("Peer Node ID") },
                        placeholder = { Text("02abc...def") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim(); error = null; result = null },
                        label = { Text("Peer Address") },
                        placeholder = { Text("ip:port or .onion:port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Channel amount (separate, highlighted orange when peer selected)
            val hasSelectedPeer = prefillAlias.isNotEmpty() || nodeId.isNotEmpty()
            OutlinedTextField(
                value = amountSats,
                onValueChange = { amountSats = it.filter { c -> c.isDigit() }; error = null; result = null },
                label = { Text("Channel Amount (sats)") },
                placeholder = { Text("100000", color = androidx.compose.ui.graphics.Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                supportingText = {
                    Text("Minimum ~20,000 sats recommended")
                },
                colors = if (hasSelectedPeer) OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF9800),
                    unfocusedBorderColor = Color(0xFFFF9800),
                    focusedLabelColor = Color(0xFFFF9800)
                ) else OutlinedTextFieldDefaults.colors()
            )

            // Peer minimum warning (only from cached rejection data, not heuristic)
            // Re-read after errors (rejection saves new data to SharedPreferences)
            val cachedMin = remember(nodeId, error) {
                if (nodeId.length >= 60) lightning.getPeerMinChannel(nodeId) else -1L
            }
            val isExact = remember(nodeId, error) {
                if (nodeId.length >= 60) lightning.isPeerMinExact(nodeId) else true
            }
            val isCeiling = remember(nodeId, error) {
                if (nodeId.length >= 60) lightning.isPeerMinCeiling(nodeId) else false
            }
            if (cachedMin > 0) {
                val amountLong = amountSats.toLongOrNull() ?: 0L
                val tooSmall = !isCeiling && amountLong in 1..cachedMin
                val suffix = if (isExact) "" else if (isCeiling) "-" else "+"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1565C0).copy(alpha = 0.15f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            (if (isCeiling) "Peer accepted: " else "Peer minimum: ") +
                                "${"%,d".format(cachedMin)}$suffix sats",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCeiling) Color(0xFF4CAF50) else Color(0xFF64B5F6)
                        )
                        if (tooSmall) {
                            Text(
                                if (isExact) "⚠️ Amount is below this peer's known minimum"
                                else "⚠️ This peer rejected ${"%,d".format(cachedMin)} sats",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Status / Error (just above button)
            if (result != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(result!!, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                        if (channelId != null) {
                            Text(
                                "Channel: ${channelId!!.take(16)}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50).copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            if (anchorWarning != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.15f))
                ) {
                    Text(
                        "⚠️ $anchorWarning",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(error!!, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // Fee status
            if (feeSynced && syncedFeeRate != null) {
                Text(
                    "✅ Fee rate: $syncedFeeRate",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            } else if (needsMaxMode) {
                if (!syncingFees) {
                    Text(
                        "⚠️ Fee estimation unavailable. Funding tx will use minimum fee rate which may be slow to confirm in high-fee environments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                    OutlinedButton(
                        onClick = {
                            syncingFees = true
                            val pmm = PowerModeManager.getInstance(context)
                            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                            if (creds != null) {
                                pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
                            }
                            pmm.holdNetwork()
                            scope.launch(Dispatchers.IO) {
                                val rpc = if (creds != null) com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second) else null
                                if (rpc != null) {
                                    repeat(180) {
                                        try {
                                            val params = org.json.JSONArray().apply { put(6) }
                                            val r = rpc.call("estimatesmartfee", params)
                                            val fr = r?.optDouble("feerate", -1.0) ?: -1.0
                                            if (fr > 0) {
                                                val satPerVb = fr * 100_000
                                                val formatted = if (satPerVb < 10) "%.1f sat/vB".format(satPerVb) else "%.0f sat/vB".format(satPerVb)
                                                syncedFeeRate = formatted
                                                feeSynced = true
                                                syncingFees = false
                                                feePrefs.edit().putString("fee_rate", formatted).putLong("fee_time", System.currentTimeMillis()).apply()
                                                pmm.releaseNetworkHold()
                                                return@launch
                                            }
                                        } catch (_: Exception) {}
                                        kotlinx.coroutines.delay(10_000)
                                    }
                                }
                                syncingFees = false
                                pmm.releaseNetworkHold()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📡 Sync Fees")
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "Syncing fee data (network held open)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }

            // Open button
            Button(
                onClick = {
                    when {
                        nodeId.length < 60 -> { error = "Invalid node ID (should be 66 hex characters)"; return@Button }
                        address.isBlank() -> { error = "Enter peer address (ip:port)"; return@Button }
                        (amountSats.toLongOrNull() ?: 0) < 1000 -> { error = "Amount too small"; return@Button }
                    }
                    opening = true
                    error = null
                    result = null
                    anchorWarning = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            // Save peer alias for channel display
                            if (prefillAlias.isNotEmpty()) {
                                context.getSharedPreferences("peer_aliases", android.content.Context.MODE_PRIVATE)
                                    .edit().putString(nodeId, prefillAlias).apply()
                            }
                            lightning.openChannel(nodeId, address, amountSats.toLong())
                        }.onSuccess {
                            channelId = it
                            result = if (lightningState.channelCount > 0) {
                                "Channel opening! Funding transaction broadcasting."
                            } else {
                                "Channel accepted by peer. Waiting for funding confirmation."
                            }
                            // Check if peer supports anchors
                            val supportsAnchors = lightning.peerSupportsAnchors(nodeId)
                            if (supportsAnchors == false) {
                                anchorWarning = "Legacy channel (no anchors). Fee negotiation carries higher risk on mobile."
                            } else if (supportsAnchors == true) {
                                anchorWarning = null // Anchor channel, all good
                            }
                            opening = false
                        }.onFailure {
                            error = it.message ?: "Failed to open channel"
                            opening = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !opening && nodeId.isNotBlank() && address.isNotBlank() && amountSats.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800)
                )
            ) {
                if (opening) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (needsMaxMode) "Connecting..." else "Opening...")
                } else {
                    Text("⚡ Open Channel")
                }
            }

        }
    }
}
