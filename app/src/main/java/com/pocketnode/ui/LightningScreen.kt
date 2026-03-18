package com.pocketnode.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import com.pocketnode.snapshot.BlockFilterManager
import kotlinx.coroutines.launch

/**
 * Lightning wallet management screen.
 * Powered by LDK Node — shows status, channels, and basic operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightningScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSend: () -> Unit = {},
    onNavigateToReceive: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToOpenChannel: () -> Unit = {},
    onNavigateToSeedBackup: () -> Unit = {},
    onNavigateToWatchtower: () -> Unit = {},
    onNavigateToLightningPay: () -> Unit = {},
    onNavigateToSendOnchain: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val lightning = remember { LightningService.getInstance(context) }

    // Primary: collect StateFlow reactively
    val effectiveState by LightningService.stateFlow.collectAsState()

    // Reconciler: if the node is actually running but state says otherwise,
    // force an update immediately. Polls every 500ms to catch background starts.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val running = lightning.isRunning()
            val stale = LightningService.stateFlow.value.status != LightningService.LightningState.Status.RUNNING
            if (running && stale) {
                lightning.updateState() // emits to StateFlow → collectAsState recomposes
            }
        }
    }

    // RPC credentials from existing config
    val rpcPrefs = remember { context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE) }
    val rpcUser = remember { rpcPrefs.getString("rpc_user", "pocketnode") ?: "pocketnode" }
    val rpcPassword = remember { rpcPrefs.getString("rpc_password", "") ?: "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lightning") },
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
            // Lightning Pay button — shown when channel is active
            if (effectiveState.status == LightningService.LightningState.Status.RUNNING &&
                effectiveState.channelCount > 0) {
                Button(
                    onClick = onNavigateToLightningPay,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800),
                        contentColor = Color.Black
                    )
                ) {
                    Text("⚡ Lightning Pay View")
                }
            }

            // Status banner — shown for starting, recovering, and error states (not stopped or running)
            if (effectiveState.status != LightningService.LightningState.Status.RUNNING &&
                effectiveState.status != LightningService.LightningState.Status.STOPPED) {
                val isStarting = effectiveState.status == LightningService.LightningState.Status.STARTING
                val isError = effectiveState.status == LightningService.LightningState.Status.ERROR
                val isRecovering = effectiveState.status == LightningService.LightningState.Status.RECOVERING
                val bannerColor = when {
                    isRecovering -> Color(0xFFFF9800)
                    isStarting -> Color(0xFFFF9800)
                    isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isError) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = bannerColor
                        )
                    }
                    Column {
                        Text(
                            when {
                                isRecovering && effectiveState.recoveryWaitingForWifi ->
                                    "📶 Waiting for WiFi..."
                                isRecovering -> {
                                    val pct = if (effectiveState.recoveryBlocksNeeded > 0)
                                        (effectiveState.recoveryBlocksDone * 100 / effectiveState.recoveryBlocksNeeded)
                                    else 0
                                    "⚡ Recovering ${effectiveState.recoveryBlocksNeeded} pruned blocks ($pct%)"
                                }
                                isStarting -> "⏳ Starting Lightning..."
                                isError -> "⚠️ Node Error"
                                else -> "⏳ Waiting for node..."
                            },
                            fontWeight = FontWeight.Bold,
                            color = bannerColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when {
                                isRecovering && effectiveState.recoveryWaitingForWifi ->
                                    "Blocks were pruned while offline. Connect to WiFi to re-download."
                                isRecovering ->
                                    "Re-downloading blocks pruned while offline (${effectiveState.recoveryBlocksDone}/${effectiveState.recoveryBlocksNeeded})"
                                isStarting -> "Connecting to bitcoind and syncing"
                                isError -> effectiveState.error ?: "Lightning node encountered an error"
                                else -> "Lightning will start when bitcoind is synced"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                // Progress bar for recovery
                if (isRecovering && !effectiveState.recoveryWaitingForWifi && effectiveState.recoveryBlocksNeeded > 0) {
                    LinearProgressIndicator(
                        progress = effectiveState.recoveryBlocksDone.toFloat() / effectiveState.recoveryBlocksNeeded.toFloat(),
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Color(0xFFFF9800),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Status card — only shown when running (banner covers non-running states)
            if (effectiveState.status == LightningService.LightningState.Status.RUNNING) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LDK Node", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("●", color = Color(0xFF4CAF50))
                                Spacer(Modifier.width(4.dp))
                                Text("Running", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (effectiveState.nodeId != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Node ID", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    effectiveState.nodeId!!.take(16) + "..." + effectiveState.nodeId!!.takeLast(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(effectiveState.nodeId!!)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Powered by LDK (Lightning Dev Kit)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Start/Stop button
            if (effectiveState.status == LightningService.LightningState.Status.STOPPED ||
                effectiveState.status == LightningService.LightningState.Status.ERROR) {
                val isError = effectiveState.status == LightningService.LightningState.Status.ERROR

                // Check if fee estimates are available
                var feesAvailable by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    val rpc = com.pocketnode.rpc.BitcoinRpcClient(rpcUser, rpcPassword)
                    while (isActive) {
                        try {
                            val result = rpc.call("estimatesmartfee", org.json.JSONArray().put(6))
                            val feeRate = result?.optDouble("feerate", -1.0) ?: -1.0
                            feesAvailable = feeRate > 0
                        } catch (_: Exception) {
                            feesAvailable = false
                        }
                        if (feesAvailable == true) break
                        delay(10_000)
                    }
                }

                val canStart = feesAvailable == true
                if (feesAvailable == false) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Waiting for fee estimates from bitcoind. This is normal after a fresh sync, just needs a few confirmed blocks.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            lightning.start(rpcUser, rpcPassword)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = canStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canStart) Color(0xFFFF9800) else Color.Gray
                    )
                ) {
                    Text("⚡ Start Lightning Node")
                }
                if (!lightning.hasSeed()) {
                    TextButton(
                        onClick = onNavigateToSeedBackup,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore existing wallet from seed words")
                    }
                }
            }

            // Action buttons — shown when running
            if (effectiveState.status == LightningService.LightningState.Status.RUNNING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onNavigateToSend,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("⬆️ Send")
                    }
                    Button(
                        onClick = onNavigateToReceive,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("⬇️ Receive")
                    }
                }
                OutlinedButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Payment History")
                }
                var showLnPeers by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showLnPeers = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connected Peers")
                }
                if (showLnPeers) {
                    LightningPeerDialog(onDismiss = { showLnPeers = false })
                }
            }

            // Balances card — shown when running
            if (effectiveState.status == LightningService.LightningState.Status.RUNNING) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Balances", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("On-chain", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${"%,d".format(effectiveState.onchainBalanceSats)} sats",
                                        fontWeight = FontWeight.Bold)
                                    if (effectiveState.scanningForFunds) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val pct = effectiveState.scanProgress
                                        Text(
                                            if (pct > 0) "scanning chainstate $pct%" else "scanning chainstate…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val pendingCloseSats = effectiveState.pendingCloseSats
                                val orphanLightning = effectiveState.channelCount == 0 && effectiveState.lightningBalanceSats > 0
                                val effectivePending = if (orphanLightning) effectiveState.lightningBalanceSats else pendingCloseSats
                                val activeLightning = effectiveState.lightningBalanceSats - effectivePending

                                if (activeLightning > 0) {
                                    Text("Lightning", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(activeLightning)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                                if (effectivePending > 0) {
                                    if (activeLightning > 0) Spacer(modifier = Modifier.height(4.dp))
                                    Text("Pending Close", style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFF9800))
                                    Text("${"%,d".format(effectivePending)} sats",
                                        fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                                }
                                if (activeLightning <= 0 && effectivePending <= 0) {
                                    Text("Lightning", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("0 sats", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Channels card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Channels", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        if (effectiveState.channelCount == 0) {
                            Text(
                                "No channels yet. Open a channel to start using Lightning.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onNavigateToOpenChannel,
                                enabled = effectiveState.pendingChannels.isEmpty()
                            ) {
                                Text(if (effectiveState.pendingChannels.isNotEmpty()) "Channel Pending..." else "Open Channel")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Active", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${effectiveState.channelCount}", fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Capacity", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(effectiveState.totalCapacitySats)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Inbound", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${"%,d".format(effectiveState.totalInboundSats)} sats",
                                        fontWeight = FontWeight.Bold)
                                }
                            }

                            // Pending close balances
                            if (effectiveState.pendingCloseDetails.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                val ldkHeight = remember(effectiveState) { lightning.getLdkHeight() }
                                effectiveState.pendingCloseDetails.forEach { pc ->
                                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Channel closed", style = MaterialTheme.typography.bodySmall)
                                            val blocksInfo = if (pc.confirmationHeight > 0 && ldkHeight > 0) {
                                                // CSV timelock is typically 144 blocks for to_local
                                                val blocksElapsed = ldkHeight - pc.confirmationHeight
                                                val blocksRemaining = (144 - blocksElapsed).coerceAtLeast(0)
                                                if (blocksRemaining > 0) " (~$blocksRemaining blocks)" else " (ready)"
                                            } else ""
                                            Text(
                                                "${pc.status}$blocksInfo",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFFF9800)
                                            )
                                        }
                                        Text(
                                            "${"%,d".format(pc.amountSats)} sats",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFFF9800)
                                        )
                                    }
                                }
                            }

                            // Channel list — tap to close
                            Spacer(Modifier.height(12.dp))
                            val channels = remember(effectiveState) { lightning.listChannels() }
                            val peerAliases = remember {
                                val prefs = context.getSharedPreferences("peer_aliases", android.content.Context.MODE_PRIVATE)
                                // Seed known peer aliases (one-time)
                                if (!prefs.getBoolean("_seeded", false)) {
                                    prefs.edit()
                                        .putString("0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3", "CoinGate")
                                        .putString("026165850492521f4ac8abd9bd8088123446d126f648ca35e60f88177dc149ceb2", "CoinGate")
                                        .putString("f3e4413f37844739ed718d4191730ffb4bb7945099cfbe8f0418ef5b0caea442", "CoinGate")
                                        .putBoolean("_seeded", true)
                                        .apply()
                                }
                                prefs
                            }
                            var selectedChannel by remember { mutableStateOf<org.lightningdevkit.ldknode.ChannelDetails?>(null) }
                            channels.forEach { ch ->
                                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedChannel = ch }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        val peerAlias = peerAliases.getString(ch.counterpartyNodeId, null)
                                        if (peerAlias != null) {
                                            Text(
                                                peerAlias,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Text(
                                                ch.counterpartyNodeId.take(12) + "...",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        val confs = ch.confirmations?.toInt() ?: 0
                                        val confsReq = ch.confirmationsRequired?.toInt() ?: 3
                                        val fundingFee = effectiveState.channelFeeRates[ch.channelId]
                                        val status = when {
                                            ch.isUsable -> "Active ⚡"
                                            ch.isChannelReady -> "Ready"
                                            fundingFee != null -> "Confirming $confs/$confsReq @ $fundingFee sat/vB"
                                            else -> "Confirming $confs/$confsReq"
                                        }
                                        val statusColor = when {
                                            ch.isUsable -> Color(0xFF4CAF50)
                                            ch.isChannelReady -> Color(0xFFFF9800)
                                            else -> Color(0xFF64B5F6)
                                        }
                                        Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "${"%,d".format(ch.channelValueSats.toLong())} sats",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        val outbound = ch.outboundCapacityMsat.toLong() / 1000
                                        Text(
                                            "can send ${"%,d".format(outbound)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }

                            // Close channel dialog
                            selectedChannel?.let { ch ->
                                var closing by remember { mutableStateOf(false) }
                                var closeError by remember { mutableStateOf<String?>(null) }
                                AlertDialog(
                                    onDismissRequest = { if (!closing) selectedChannel = null },
                                    title = { Text("Channel Options") },
                                    text = {
                                        Column {
                                            Text(
                                                ch.counterpartyNodeId,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text("${"%,d".format(ch.channelValueSats.toLong())} sats capacity")
                                            if (closeError != null) {
                                                Spacer(Modifier.height(8.dp))
                                                Text(closeError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            }
                                            if (closing) {
                                                Spacer(Modifier.height(8.dp))
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                            }
                                            Spacer(Modifier.height(16.dp))
                                            // Cooperative close
                                            Button(
                                                onClick = {
                                                    closing = true
                                                    closeError = null
                                                    scope.launch {
                                                        val pmm = com.pocketnode.power.PowerModeManager.getInstance(context)
                                                        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                                                        if (creds != null) {
                                                            pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
                                                        }
                                                        pmm.holdNetwork()
                                                        kotlinx.coroutines.delay(2000)
                                                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                            try {
                                                                lightning.closeChannel(ch.userChannelId, ch.counterpartyNodeId)
                                                            } finally {
                                                                pmm.releaseNetworkHold()
                                                            }
                                                        }
                                                        result.onSuccess { selectedChannel = null }
                                                            .onFailure { closing = false; closeError = it.message }
                                                    }
                                                },
                                                enabled = !closing,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                            ) { Text("Cooperative Close") }
                                            Spacer(Modifier.height(8.dp))
                                            // Force close
                                            OutlinedButton(
                                                onClick = {
                                                    closing = true
                                                    closeError = null
                                                    scope.launch {
                                                        lightning.forceCloseChannel(ch.userChannelId, ch.counterpartyNodeId)
                                                            .onSuccess { selectedChannel = null }
                                                            .onFailure { closing = false; closeError = it.message }
                                                    }
                                                },
                                                enabled = !closing,
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Force Close") }
                                            Spacer(Modifier.height(8.dp))
                                            // Exit / go back
                                            OutlinedButton(
                                                onClick = { selectedChannel = null },
                                                enabled = !closing,
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("Go Back") }
                                        }
                                    },
                                    confirmButton = {},
                                    dismissButton = {}
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = onNavigateToOpenChannel,
                                enabled = effectiveState.pendingChannels.isEmpty()
                            ) {
                                Text(if (effectiveState.pendingChannels.isNotEmpty()) "Channel Pending..." else "Open Another Channel")
                            }
                        }

                        // Pending channel closes (outside if/else so it shows with 0 channels too)
                        val pendingCloses = effectiveState.pendingCloseDetails
                        val hasOrphanLightning = effectiveState.channelCount == 0 && effectiveState.lightningBalanceSats > 0
                        if (pendingCloses.isNotEmpty() || hasOrphanLightning) {
                            Spacer(Modifier.height(12.dp))
                            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Closing",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFFF9800)
                            )
                            Spacer(Modifier.height(4.dp))
                            if (pendingCloses.isNotEmpty()) {
                                pendingCloses.forEach { close ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                close.txid?.take(16)?.plus("...") ?: "Pending",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            val statusText = if (close.blocksRemaining > 0) {
                                                val hours = (close.blocksRemaining * 10) / 60
                                                "${close.confirmations}/144 confirmations (~${hours}h remaining)"
                                            } else close.status
                                            Text(statusText, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                        }
                                        Text(
                                            "${"%,d".format(close.amountSats)} sats",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            } else {
                                // Fallback: lightning balance with no channels
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "Force close pending",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Waiting for close tx to confirm",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                    Text(
                                        "${"%,d".format(effectiveState.lightningBalanceSats)} sats",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                // Fund wallet card
                var depositAddress by remember { mutableStateOf<String?>(null) }
                var addressVisible by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Fund Lightning Wallet", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Send bitcoin to this address to fund your Lightning wallet for opening channels.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(12.dp))

                        if (depositAddress != null && addressVisible) {
                            // Tap header to collapse the address
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { addressVisible = false },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Deposit Address", style = MaterialTheme.typography.labelMedium)
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Hide",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                com.pocketnode.ui.lightning.QrCodeImage(
                                    data = "bitcoin:${depositAddress!!}",
                                    size = 200
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    depositAddress!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Visible
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(depositAddress!!)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy Address", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = {
                                        lightning.markDepositAddressUsed(depositAddress!!)
                                        depositAddress = null
                                        lightning.getOnchainAddress().onSuccess { depositAddress = it }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, "Skip", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Skip Address", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    if (depositAddress == null) {
                                        lightning.getOnchainAddress().onSuccess {
                                            depositAddress = it
                                            addressVisible = true
                                        }
                                    } else {
                                        addressVisible = true
                                    }
                                }
                            ) {
                                Text(if (depositAddress != null) "Show Deposit Address" else "Generate Deposit Address")
                            }
                        }

                        // Send on-chain button
                        if (effectiveState.onchainBalanceSats > 0) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onNavigateToSendOnchain,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Send On-chain")
                            }
                        }
                    }
                }

                // Watchtower status card
                val wtPrefs = remember { context.getSharedPreferences("watchtower_prefs", android.content.Context.MODE_PRIVATE) }
                val wtManager = remember { com.pocketnode.service.WatchtowerManager(context) }
                val towerConfigured = remember { wtManager.isConfigured() }

                // Check monitor backup status
                val backupMonitorsDir = remember { java.io.File(context.filesDir, "lightning_backup/monitors") }
                val backupCount = remember { backupMonitorsDir.listFiles()?.count { it.name.endsWith(".bin") } ?: 0 }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (towerConfigured) {
                            val wtStatus = wtManager.getStatus()
                            val nodeOs = if (wtStatus is com.pocketnode.service.WatchtowerManager.WatchtowerStatus.Configured) wtStatus.nodeOs else null
                            val towerReachable = effectiveState.watchtowerReachable
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("\uD83D\uDEE1\uFE0F Watchtower", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                when (towerReachable) {
                                    true -> Text("● Connected", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                                    false -> Text("● Offline", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
                                    null -> Text("● Checking...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            }
                            Text(
                                "Your home node${if (nodeOs != null) " ($nodeOs)" else ""} watches your channels when this phone is offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            val towerOnion = wtPrefs.getString("tower_onion", "") ?: ""
                            if (towerOnion.isNotEmpty()) {
                                Text(
                                    "\uD83E\uDDA7 ${towerOnion.take(16)}...onion",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            Text("\uD83D\uDEE1\uFE0F Watchtower", fontWeight = FontWeight.Bold)
                            Text(
                                "Not connected. Connect to your home node's watchtower to protect your channels when this phone is offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        // Show local backup status
                        val channelCount = LightningService.stateFlow.value.channelCount
                        if (channelCount > 0 || backupCount > 0) {
                            Text(
                                "\uD83D\uDCBE Local backup: $backupCount channel monitor${if (backupCount != 1) "s" else ""} saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (backupCount > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        OutlinedButton(onClick = onNavigateToWatchtower) {
                            Text(if (towerConfigured) "Watchtower Settings" else "Set Up Watchtower")
                        }
                    }
                }

                // Wallet backup
                OutlinedButton(
                    onClick = onNavigateToSeedBackup,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("\uD83D\uDD11 Wallet Seed and Backup")
                }

                // Stop node
                OutlinedButton(
                    onClick = { lightning.stop() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Lightning Node")
                }
            }

            // Lightning info & management — always visible at bottom
            run {
                val filterDir = java.io.File(context.filesDir, "bitcoin/indexes/blockfilter/basic")
                val hasFilters = filterDir.exists() && (filterDir.listFiles()?.size ?: 0) > 1

                if (hasFilters) {
                    val manager = remember { BlockFilterManager(context) }
                    val localSize = remember { manager.localSizeBytes() }
                    val sizeGb = localSize / (1024.0 * 1024 * 1024)

                    Divider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Block Filters", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "BIP 157/158 block filters installed (${"%.1f".format(sizeGb)} GB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "See Connect page for LNDHub and Pruned Neutrino connect info",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    var showRemoveConfirm by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = { showRemoveConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Remove Lightning Support")
                    }

                    if (showRemoveConfirm) {
                        AlertDialog(
                            onDismissRequest = { showRemoveConfirm = false },
                            title = { Text("Remove Lightning Support?") },
                            text = {
                                Text("This will delete ${"%.1f".format(sizeGb)} GB of block filter data. Lightning will stop working and your channels may need to be closed.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRemoveConfirm = false
                                    scope.launch {
                                        lightning.stop()
                                        manager.removeLocal(context)
                                        onNavigateBack()
                                    }
                                }) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRemoveConfirm = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LightningPeerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var peers by remember { mutableStateOf<List<org.lightningdevkit.ldknode.PeerDetails>>(emptyList()) }
    var aliases by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ls = LightningService.getInstance(context)
            peers = ls.listPeers()
            // Resolve aliases from network graph
            try {
                val graph = ls.networkGraph()
                val aliasMap = mutableMapOf<String, String>()
                for (peer in peers) {
                    try {
                        val nodeInfo = graph?.node(peer.nodeId)
                        val alias = nodeInfo?.announcementInfo?.alias
                        if (!alias.isNullOrBlank()) {
                            aliasMap[peer.nodeId] = alias
                        }
                    } catch (_: Exception) {}
                }
                aliases = aliasMap
            } catch (_: Exception) {}
            loading = false
        }
    }

    val torActive = com.pocketnode.tor.TorManager.enabledFlow.collectAsState().value &&
            com.pocketnode.tor.TorManager.statusFlow.collectAsState().value == com.pocketnode.tor.TorManager.TorStatus.RUNNING

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${if (torActive) "🧅 " else "⚡ "}Connected Peers (${peers.size})") },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (peers.isEmpty()) {
                Text("No Lightning peers connected", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    peers.forEach { peer ->
                        val addr = peer.address.toString()
                        val isOnion = addr.contains(".onion")
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val alias = aliases[peer.nodeId]
                                if (alias != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            alias,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (isOnion) Text("🧅", style = MaterialTheme.typography.bodySmall)
                                            if (peer.supportsAnchors) Text("⚓", style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                if (peer.isConnected) "●" else "○",
                                                color = if (peer.isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Text(
                                        peer.nodeId.take(16) + "...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            peer.nodeId.take(16) + "...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (isOnion) Text("🧅", style = MaterialTheme.typography.bodySmall)
                                            if (peer.supportsAnchors) Text("⚓", style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                if (peer.isConnected) "●" else "○",
                                                color = if (peer.isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                                Text(
                                    if (isOnion) addr.take(24) + "...onion" else addr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
