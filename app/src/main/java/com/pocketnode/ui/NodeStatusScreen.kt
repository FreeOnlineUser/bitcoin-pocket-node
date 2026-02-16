package com.pocketnode.ui

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.network.DataUsageEntry
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.service.BitcoindService
import com.pocketnode.service.SyncController
import com.pocketnode.snapshot.ChainstateManager
import com.pocketnode.ui.components.NetworkStatusBar
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main dashboard showing node status, block height, peers, sync progress, and controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeStatusScreen(
    onNavigateToSnapshot: () -> Unit,
    onNavigateToSetup: () -> Unit = {},
    networkState: NetworkState = NetworkState.OFFLINE,
    syncPaused: Boolean = false,
    todayUsage: DataUsageEntry? = null,
    onAllowCellular: () -> Unit = {},
    onNavigateToDataUsage: () -> Unit = {},
    onNavigateToNetworkSettings: () -> Unit = {},
    onNavigateToNodeAccess: () -> Unit = {},
    onNavigateToWallet: () -> Unit = {}
) {
    val context = LocalContext.current

    // Node state
    // Pick up running state from service (survives recomposition / nav)
    val serviceRunning by BitcoindService.isRunningFlow.collectAsState()
    var nodeStatus by remember { mutableStateOf(if (BitcoindService.isRunningFlow.value) "Running" else "Stopped") }
    var blockHeight by remember { mutableStateOf(-1L) }
    var headerHeight by remember { mutableStateOf(-1L) }
    var syncProgress by remember { mutableStateOf(0.0) }
    var peerCount by remember { mutableStateOf(0) }
    var chain by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(BitcoindService.isRunningFlow.value) }
    var assumeUtxoActive by remember { mutableStateOf(false) }
    var bgValidationHeight by remember { mutableStateOf(-1L) }

    // Sync local UI state with service state (handles start/stop while on other screens)
    LaunchedEffect(serviceRunning) {
        if (serviceRunning && !isRunning) {
            isRunning = true
            nodeStatus = "Running"
        } else if (!serviceRunning && isRunning) {
            isRunning = false
            nodeStatus = "Stopped"
        }
    }
    var ibd by remember { mutableStateOf(true) }
    var sizeOnDisk by remember { mutableStateOf(0L) }
    var mempoolSize by remember { mutableStateOf(0) }
    var mempoolBytes by remember { mutableStateOf(0L) }
    var lastBlockTime by remember { mutableStateOf(0L) }
    var pruned by remember { mutableStateOf(false) }

    // For ETA calculation
    var prevBlockHeight by remember { mutableStateOf(-1L) }
    var prevCheckTime by remember { mutableStateOf(0L) }
    var blocksPerSecond by remember { mutableStateOf(0.0) }

    // Startup detail from debug.log (shown when RPC has no data yet)
    var startupDetail by remember { mutableStateOf("") }

    // Tail debug.log for startup progress (pre-sync headers phase where RPC returns 0)
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        val logFile = java.io.File(context.filesDir, "bitcoin/debug.log")
        val preSyncRegex = Regex("""height:\s*(\d+)\s*\(~?([\d.]+)%\)""")
        while (isActive && isRunning) {
            // Only read log when RPC hasn't provided real data yet
            if (blockHeight <= 0 && headerHeight <= 0) {
                try {
                    if (logFile.exists() && logFile.length() > 0) {
                        val raf = java.io.RandomAccessFile(logFile, "r")
                        val readSize = minOf(2048L, raf.length())
                        raf.seek(raf.length() - readSize)
                        val tail = ByteArray(readSize.toInt())
                        raf.readFully(tail)
                        raf.close()
                        val lines = String(tail).lines().takeLast(5)
                        val preSyncLine = lines.lastOrNull { it.contains("Pre-synchronizing blockheaders") }
                        if (preSyncLine != null) {
                            val match = preSyncRegex.find(preSyncLine)
                            if (match != null) {
                                val h = match.groupValues[1]
                                val pct = match.groupValues[2]
                                startupDetail = "Pre-syncing headers: $pct% (${"%,d".format(h.toLong())})"
                                nodeStatus = "Pre-syncing headers"
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else {
                // RPC has real data now, clear startup detail and stop log tailing
                if (startupDetail.isNotEmpty()) startupDetail = ""
                break
            }
            delay(2000)
        }
    }

    // Poll RPC when running
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        val creds = ConfigGenerator.readCredentials(context)
        if (creds == null) return@LaunchedEffect
        val rpc = BitcoinRpcClient(creds.first, creds.second)

        while (isActive && isRunning) {
            try {
                val info = rpc.getBlockchainInfo()
                if (info != null) {
                    val rpcBlocks = info.optLong("blocks", -1)
                    headerHeight = info.optLong("headers", -1)
                    syncProgress = info.optDouble("verificationprogress", 0.0)
                    chain = info.optString("chain", "")
                    ibd = info.optBoolean("initialblockdownload", true)
                    sizeOnDisk = info.optLong("size_on_disk", 0)
                    lastBlockTime = info.optLong("time", 0)
                    pruned = info.optBoolean("pruned", false)

                    // Detect AssumeUTXO: chainstate_snapshot dir exists
                    val snapshotDir = java.io.File(context.filesDir, "bitcoin/chainstate_snapshot")
                    assumeUtxoActive = snapshotDir.exists() && (snapshotDir.listFiles()?.size ?: 0) > 2

                    if (assumeUtxoActive) {
                        // Background validation is what getblockchaininfo reports
                        bgValidationHeight = rpcBlocks
                        // The snapshot chain is at the tip — use headers as effective block height
                        blockHeight = headerHeight
                        // Override last block time to current (snapshot chain is at tip)
                        lastBlockTime = System.currentTimeMillis() / 1000
                        ibd = false // snapshot chain is not in IBD
                    } else {
                        blockHeight = rpcBlocks
                        bgValidationHeight = -1
                    }

                    // Calculate sync speed (for non-assumeutxo or background validation display)
                    val newBlocks = if (assumeUtxoActive) bgValidationHeight else blockHeight
                    val now = System.currentTimeMillis()
                    if (prevBlockHeight > 0 && newBlocks > prevBlockHeight && prevCheckTime > 0) {
                        val elapsed = (now - prevCheckTime) / 1000.0
                        if (elapsed > 0) {
                            val newRate = (newBlocks - prevBlockHeight) / elapsed
                            blocksPerSecond = if (blocksPerSecond > 0) {
                                blocksPerSecond * 0.7 + newRate * 0.3
                            } else {
                                newRate
                            }
                        }
                    }
                    prevBlockHeight = newBlocks
                    prevCheckTime = now

                    // Approximate current chain tip for header progress
                    val approxChainTip = 936_000L + ((System.currentTimeMillis() / 1000 - 1739577600) / 600)
                    val headersComplete = headerHeight > 0 && headerHeight >= approxChainTip * 95 / 100

                    val blockPct = if (headerHeight > 0 && blockHeight > 0) {
                        (blockHeight.toDouble() / headerHeight * 100)
                    } else 0.0

                    nodeStatus = when {
                        assumeUtxoActive -> "Synced (validating)"
                        syncProgress >= 0.9999 && !ibd -> "Synced"
                        syncProgress >= 0.9999 -> "Almost synced"
                        blockHeight > 0 && ibd && headersComplete -> {
                            "Syncing ${"%.2f".format(blockPct)}%"
                        }
                        (syncProgress > 0.0 || blockHeight > 0) && ibd -> "Syncing"
                        headerHeight > 0 && !headersComplete -> {
                            val pct = (headerHeight.toDouble() / approxChainTip * 100).coerceAtMost(99.9)
                            "Headers ${"%.1f".format(pct)}%"
                        }
                        peerCount > 0 -> "Connected"
                        else -> "Starting"
                    }
                }

                peerCount = rpc.getPeerCount()

                // Get mempool info
                try {
                    val mpInfo = rpc.call("getmempoolinfo")
                    val mpResult = mpInfo?.optJSONObject("result")
                    if (mpResult != null) {
                        mempoolSize = mpResult.optInt("size", 0)
                        mempoolBytes = mpResult.optLong("bytes", 0)
                    }
                } catch (_: Exception) {}

            } catch (_: Exception) {
                if (nodeStatus != "Starting") nodeStatus = "Error"
            }
            delay(3_000) // poll every 3 seconds for snappy UI
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("₿ Pocket Node", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Network status banner
            NetworkStatusBar(
                networkState = networkState,
                syncPaused = syncPaused,
                todayUsage = todayUsage,
                onAllowCellular = onAllowCellular
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status indicator
                StatusHeader(nodeStatus = nodeStatus, chain = chain, detail = startupDetail)

                // Chainstate copy progress (visible from dashboard while copy runs)
                ChainstateProgressCard(context)

                if (isRunning) {
                    // Sync progress section
                    SyncProgressSection(
                        blockHeight = blockHeight,
                        headerHeight = headerHeight,
                        syncProgress = syncProgress,
                        blocksPerSecond = blocksPerSecond,
                        ibd = ibd,
                        nodeStatus = nodeStatus,
                        assumeUtxoActive = assumeUtxoActive,
                        bgValidationHeight = bgValidationHeight
                    )

                    // Stats grid
                    StatsGrid(
                        peerCount = peerCount,
                        sizeOnDisk = sizeOnDisk,
                        mempoolSize = mempoolSize,
                        mempoolBytes = mempoolBytes,
                        pruned = pruned,
                        lastBlockTime = lastBlockTime,
                        ibd = ibd
                    )
                }

                Spacer(Modifier.weight(1f))

                // Action buttons
                ActionButtons(
                    isRunning = isRunning,
                    onNavigateToDataUsage = onNavigateToDataUsage,
                    onNavigateToNetworkSettings = onNavigateToNetworkSettings,
                    onNavigateToSnapshot = onNavigateToSnapshot,
                    onNavigateToNodeAccess = onNavigateToNodeAccess,
                    onNavigateToSetup = onNavigateToSetup,
                    onNavigateToWallet = onNavigateToWallet,
                    onToggleNode = {
                        if (isRunning) {
                            context.stopService(Intent(context, BitcoindService::class.java))
                            // Clear auto-start flag — user explicitly stopped
                            context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("node_was_running", false).apply()
                            isRunning = false
                            nodeStatus = "Stopped"
                            blockHeight = -1
                            headerHeight = -1
                            peerCount = 0
                            syncProgress = 0.0
                            chain = ""
                            mempoolSize = 0
                            mempoolBytes = 0
                            blocksPerSecond = 0.0
                        } else {
                            nodeStatus = "Starting"
                            isRunning = true
                            val intent = Intent(context, BitcoindService::class.java)
                            context.startForegroundService(intent)
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusHeader(nodeStatus: String, chain: String, detail: String = "") {
    val statusColor by animateColorAsState(
        targetValue = when {
            nodeStatus == "Synced" -> Color(0xFF4CAF50)
            nodeStatus == "Almost synced" -> Color(0xFF8BC34A)
            nodeStatus.startsWith("Syncing") -> Color(0xFF2196F3)
            nodeStatus.startsWith("Headers") -> Color(0xFF2196F3)
            nodeStatus == "Connected" -> Color(0xFF03A9F4)
            nodeStatus.startsWith("Pre-syncing") -> Color(0xFF2196F3)
            nodeStatus == "Starting" -> Color(0xFFFFC107)
            nodeStatus == "Error" -> Color(0xFFF44336)
            else -> Color(0xFF757575)
        },
        animationSpec = tween(500),
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = statusColor,
                modifier = Modifier.size(16.dp)
            ) {}
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    nodeStatus,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (chain.isNotEmpty()) {
                    Text(
                        "Bitcoin $chain",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncProgressSection(
    blockHeight: Long,
    headerHeight: Long,
    syncProgress: Double,
    blocksPerSecond: Double,
    ibd: Boolean,
    nodeStatus: String,
    assumeUtxoActive: Boolean = false,
    bgValidationHeight: Long = -1
) {
    val animatedProgress by animateFloatAsState(
        targetValue = syncProgress.toFloat(),
        animationSpec = tween(1000),
        label = "syncProgress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (assumeUtxoActive) {
                // AssumeUTXO mode — show snapshot chain at tip
                Text("Block Height", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (blockHeight >= 0) "%,d".format(blockHeight) else "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "✓ Synced via AssumeUTXO",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50)
                )

                // Background validation progress
                if (bgValidationHeight > 0) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Background Validation",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    val bgPct = if (headerHeight > 0) {
                        (bgValidationHeight.toFloat() / headerHeight).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = bgPct,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = Color(0xFFFF9800) // Orange for background validation
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Block %,d".format(bgValidationHeight),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "${"%.1f".format(bgPct * 100)}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (blocksPerSecond > 0 && headerHeight > bgValidationHeight) {
                        val remaining = headerHeight - bgValidationHeight
                        val etaSeconds = (remaining / blocksPerSecond).toLong()
                        Text(
                            "ETA: ${formatEta(etaSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            } else {
            // Normal mode — block height with optional chain tip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Block Height", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (blockHeight >= 0) "%,d".format(blockHeight) else "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (headerHeight > 0 && headerHeight > blockHeight) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Chain Tip", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "%,d".format(headerHeight),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Progress bar (during sync — use block/header ratio when verificationprogress is near-zero)
            val showProgress = syncProgress in 0.0001..0.9998 ||
                    (blockHeight > 0 && headerHeight > blockHeight && ibd)
            if (showProgress) {
                val displayProgress = if (syncProgress > 0.0001) {
                    animatedProgress
                } else if (headerHeight > 0 && blockHeight > 0) {
                    (blockHeight.toFloat() / headerHeight).coerceIn(0f, 1f)
                } else 0f
                val displayPct = if (syncProgress > 0.0001) {
                    syncProgress * 100
                } else if (headerHeight > 0 && blockHeight > 0) {
                    blockHeight.toDouble() / headerHeight * 100
                } else 0.0

                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = displayProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${"%.2f".format(displayPct)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    // Blocks remaining
                    if (headerHeight > blockHeight && blockHeight > 0) {
                        val remaining = headerHeight - blockHeight
                        Text(
                            "%,d blocks left".format(remaining),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                // ETA
                if (blocksPerSecond > 0 && headerHeight > blockHeight && blockHeight > 0) {
                    val remaining = headerHeight - blockHeight
                    val etaSeconds = (remaining / blocksPerSecond).toLong()
                    val etaText = formatEta(etaSeconds)
                    Text(
                        "ETA: $etaText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (nodeStatus == "Synced" || nodeStatus.startsWith("Synced")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "✅ Fully synced to chain tip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
            }
            } // end else (normal mode)
        }
    }
}

@Composable
private fun StatsGrid(
    peerCount: Int,
    sizeOnDisk: Long,
    mempoolSize: Int,
    mempoolBytes: Long,
    pruned: Boolean,
    lastBlockTime: Long,
    ibd: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            label = "Peers",
            value = peerCount.toString(),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Disk",
            value = formatBytes(sizeOnDisk),
            subtitle = if (pruned) "pruned" else "full",
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            label = "Mempool",
            value = if (ibd) "—" else "%,d".format(mempoolSize),
            subtitle = if (ibd) "waiting for sync" else formatBytes(mempoolBytes),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Last Block",
            value = if (lastBlockTime > 0) formatTimeSince(lastBlockTime) else "—",
            subtitle = if (lastBlockTime > 0) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastBlockTime * 1000))
            } else "",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionButtons(
    isRunning: Boolean,
    onNavigateToDataUsage: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onNavigateToSnapshot: () -> Unit,
    onNavigateToNodeAccess: () -> Unit = {},
    onNavigateToSetup: () -> Unit = {},
    onNavigateToWallet: () -> Unit = {},
    onToggleNode: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Setup button — always accessible
        OutlinedButton(
            onClick = onNavigateToSetup,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Setup Checklist", fontWeight = FontWeight.Medium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onNavigateToSnapshot,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Snapshot", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToNodeAccess,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Access", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToNetworkSettings,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Network", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToDataUsage,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Data", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToWallet,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Connect", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        }

        Button(
            onClick = onToggleNode,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning) "Stop Node" else "Start Node",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

private fun formatTimeSince(unixTime: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - unixTime
    return when {
        diff < 60 -> "${diff}s ago"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

private fun formatEta(seconds: Long): String {
    return when {
        seconds < 60 -> "< 1 minute"
        seconds < 3600 -> "${seconds / 60} minutes"
        seconds < 86400 -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            "${h}h ${m}m"
        }
        else -> {
            val d = seconds / 86400
            val h = (seconds % 86400) / 3600
            "${d}d ${h}h"
        }
    }
}

/**
 * Shows chainstate copy progress on the dashboard when a copy is in progress.
 * Only visible when ChainstateManager is actively running (not NOT_STARTED or COMPLETE/ERROR).
 */
@Composable
private fun ChainstateProgressCard(context: android.content.Context) {
    val manager = remember { ChainstateManager.getInstance(context) }
    val state by manager.state.collectAsState()

    // Only show when actively running
    if (state.step == ChainstateManager.Step.NOT_STARTED ||
        state.step == ChainstateManager.Step.COMPLETE ||
        state.step == ChainstateManager.Step.ERROR) return

    val stepLabel = when (state.step) {
        ChainstateManager.Step.STOPPING_REMOTE -> "Stopping remote node"
        ChainstateManager.Step.ARCHIVING -> "Archiving node data"
        ChainstateManager.Step.DOWNLOADING -> "Downloading to phone"
        ChainstateManager.Step.DEPLOYING -> "Deploying chainstate"
        ChainstateManager.Step.STARTING_NODE -> "Starting node"
        else -> "Syncing"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "⛓ Chainstate Copy",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stepLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (state.progress.isNotEmpty()) {
                Text(
                    state.progress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            // Show download progress bar
            if (state.step == ChainstateManager.Step.DOWNLOADING && state.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
            } else {
                // Indeterminate for other steps
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
            }
        }
    }
}
