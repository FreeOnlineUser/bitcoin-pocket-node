package com.pocketnode.ui

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.pocketnode.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.background
import com.pocketnode.power.PowerModeManager
import com.pocketnode.service.BatteryMonitor
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

    onNavigateToDataUsage: () -> Unit = {},
    onNavigateToNetworkSettings: () -> Unit = {},
    onNavigateToNodeAccess: () -> Unit = {},
    onNavigateToWallet: () -> Unit = {},
    onNavigateToBlockFilter: () -> Unit = {},
    onNavigateToWatchtower: () -> Unit = {},
    onNavigateToLightning: () -> Unit = {},
    onNavigateToShare: () -> Unit = {},
    onNavigateToMempool: () -> Unit = {},
    mempoolPaneVisible: Boolean = false
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
    val appPrefs = remember { context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE) }
    var showPrice by remember { mutableStateOf(appPrefs.getBoolean("show_price", true)) }
    var showFairTrade by remember { mutableStateOf(appPrefs.getBoolean("show_fair_trade", true)) }
    var oraclePrice by remember { mutableStateOf<Int?>(null) }
    val dashboardScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
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
    // Delayed recheck — catches race where service starts before UI initializes
    // Also tries RPC directly in case _isRunning was reset (e.g. after app install)
    LaunchedEffect(Unit) {
        delay(500)
        if (BitcoindService.isRunningFlow.value && !isRunning) {
            isRunning = true
            nodeStatus = "Running"
        }
        // If still not running after 2s, try RPC as last resort
        if (!isRunning) {
            delay(1500)
            try {
                val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                if (creds != null) {
                    val rpc = com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second)
                    val info = rpc.getBlockchainInfo()
                    if (info != null) {
                        // bitcoind is running but service state was lost — recover
                        isRunning = true
                        nodeStatus = "Running"
                        // Restart the service to re-attach
                        val intent = android.content.Intent(context, BitcoindService::class.java)
                        context.startForegroundService(intent)
                    }
                }
            } catch (_: Exception) {}
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
    // Mini log — last few meaningful lines from debug.log during startup
    var miniLog by remember { mutableStateOf(listOf<String>()) }
    // Track whether we've already auto-started BWT this session
    var electrumAutoStarted by remember { mutableStateOf(false) }

    // Tail debug.log for startup phases (before RPC has real data)
    // Phases only advance forward (0→5), never regress, to handle log buffer floods
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            startupDetail = ""
            miniLog = emptyList()
            return@LaunchedEffect
        }
        val logFile = java.io.File(context.filesDir, "bitcoin/debug.log")
        // Record log size at launch — only read bytes written after this point
        val logStartOffset = if (logFile.exists()) logFile.length() else 0L
        var phase = 0 // 0=init, 1=loading index, 2=verifying, 3=pruning, 4=network, 5=done
        val preSyncRegex = Regex("""height:\s*(\d+)\s*\(~?([\d.]+)%\)""")

        while (isActive && isRunning) {
            // Once RPC has real data, clear startup detail but keep mini log for sync info
            if (blockHeight > 0 || headerHeight > 0) {
                if (startupDetail.isNotEmpty()) startupDetail = ""
            }
            try {
                if (logFile.exists() && logFile.length() > logStartOffset) {
                    val raf = java.io.RandomAccessFile(logFile, "r")
                    val newBytes = (logFile.length() - logStartOffset).coerceAtMost(16384)
                    raf.seek(logFile.length() - newBytes)
                    val buf = ByteArray(newBytes.toInt())
                    raf.readFully(buf)
                    raf.close()
                    val text = String(buf)

                    // Scan for phase markers — only advance, never go back
                    if (phase < 1 && text.contains("Loading block index")) {
                        phase = 1; nodeStatus = "Loading block index"; startupDetail = "Opening database…"
                    }
                    if (phase < 2 && text.contains("Verifying blocks")) {
                        phase = 2; nodeStatus = "Verifying blocks"; startupDetail = ""
                    }
                    // Extract chain height from "Loaded best chain" line
                    if (phase < 2) {
                        val chainMatch = Regex("""Loaded best chain:.*height=(\d+)""").find(text)
                        if (chainMatch != null) {
                            startupDetail = "Chain height: ${"%,d".format(chainMatch.groupValues[1].toLong())}"
                        }
                    }
                    if (phase < 3 && text.contains("Pruning blockstore")) {
                        phase = 3; nodeStatus = "Pruning"; startupDetail = ""
                    }
                    if (phase < 4 && text.contains("Starting network threads")) {
                        phase = 4; nodeStatus = "Starting network"; startupDetail = ""
                    }
                    if (phase < 5 && text.contains("Done loading")) {
                        phase = 5; nodeStatus = "Catching up"; startupDetail = "Waiting for peers…"
                    }
                    // Pre-sync headers (can happen at any phase before RPC data)
                    val preSyncLine = text.lines().lastOrNull { it.contains("Pre-synchronizing blockheaders") }
                    if (preSyncLine != null) {
                        val match = preSyncRegex.find(preSyncLine)
                        if (match != null) {
                            val h = match.groupValues[1]
                            val pct = match.groupValues[2]
                            startupDetail = "Pre-syncing headers: $pct% (${"%,d".format(h.toLong())})"
                            if (phase < 5) nodeStatus = "Pre-syncing headers"
                        }
                    }
                    // Clear "Waiting for peers" once a peer connects
                    if (phase >= 4 && text.contains("peer connected")) {
                        startupDetail = ""
                    }

                    // Mini log — single most recent interesting line
                    // During sync: show latest peer or mempool event
                    // During startup: show latest init progress
                    val lines = text.lines()

                    // Check for interesting events (skip peer counts — stats grid has live RPC data)
                    val interestingLine = lines.lastOrNull { line ->
                        line.isNotBlank() &&
                        (line.contains("Imported mempool") ||
                         line.contains("Leaving InitialBlockDownload") ||
                         line.contains("init message:") ||
                         line.contains("Opened LevelDB") ||
                         line.contains("Loaded best chain") ||
                         line.contains("Verifying last"))
                    } ?: lines.lastOrNull { line ->
                        // Fallback: any non-noise line
                        line.isNotBlank() &&
                        !line.contains("UpdateTip:") &&
                        !line.contains("Saw new header") &&
                        !line.contains("PATTERN_PRIVACY") &&
                        !line.contains("dnsseed") &&
                        !line.contains("Waiting 300 seconds") &&
                        !line.contains("will be tried for connections") &&
                        !line.contains("block tree size") &&
                        !line.contains("obfuscation key") &&
                        !line.contains("thread start") &&
                        !line.contains("thread exit") &&
                        !line.contains("Using 2.0 MiB") &&
                        !line.contains("Checking all blk") &&
                        !line.contains("LoadBlockIndexDB") &&
                        !line.contains("Block files have previously") &&
                        line.length > 20
                    }

                    if (interestingLine != null) {
                        val stripped = interestingLine.removePrefix(
                            interestingLine.take(20).takeWhile { it != 'Z' } + "Z "
                        )
                        val display = if (stripped.length > 80) stripped.take(77) + "…" else stripped
                        miniLog = listOf(display)
                    }
                }
            } catch (_: Exception) {}
            delay(1000) // check every second for snappy feedback
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

                    // Force Max mode during initial block download
                    if (ibd && !PowerModeManager.initialSyncHoldFlow.value) {
                        PowerModeManager.getInstance(context).startInitialSyncHold(this, rpc)
                    } else if (!ibd && PowerModeManager.initialSyncHoldFlow.value) {
                        android.util.Log.i("NodeStatusScreen", "IBD complete, restoring previous power mode")
                        val pmm = PowerModeManager.getInstance(context)
                        pmm.endInitialSyncHold(this)
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

                    val blocksBehind = if (headerHeight > 0 && blockHeight > 0) headerHeight - blockHeight else 0L
                    val newStatus = when {
                        assumeUtxoActive -> "Synced (validating)"
                        syncProgress >= 0.9999 && !ibd && blocksBehind <= 1 -> "Synced"
                        syncProgress >= 0.9999 && !ibd && blocksBehind > 1 -> "Catching up"
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

                    // Auto-start BWT when node becomes synced (if it was running before)
                    if (newStatus.startsWith("Synced") && !nodeStatus.startsWith("Synced") && !electrumAutoStarted) {
                        val prefs = context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                        if (prefs.getBoolean("electrum_was_running", true)) {
                            electrumAutoStarted = true
                            val bwt = com.pocketnode.service.ElectrumService(context)
                            bwt.start(saveState = false) // don't re-save, already true
                            android.util.Log.i("NodeStatusScreen", "Auto-started Electrum server (was previously running)")
                        }
                        // Lightning auto-start handled by BitcoindService to avoid duplicate starts
                    }
                    nodeStatus = newStatus
                }

                peerCount = rpc.getPeerCount()

                // Get mempool info
                try {
                    val mpInfo = rpc.call("getmempoolinfo")
                    if (mpInfo != null && !mpInfo.optBoolean("_rpc_error", false)) {
                        mempoolSize = mpInfo.optInt("size", 0)
                        mempoolBytes = mpInfo.optLong("bytes", 0)
                    }
                } catch (_: Exception) {}

            } catch (_: Exception) {
                if (nodeStatus != "Starting" && nodeStatus != "Stopped") nodeStatus = "Error"
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

            )


            // Burst sync / wallet hold banner
            val burstState by PowerModeManager.burstStateFlow.collectAsState()
            val nextBurst by PowerModeManager.nextBurstFlow.collectAsState()
            val walletConnected by PowerModeManager.walletConnectedFlow.collectAsState()
            BurstSyncBanner(burstState = burstState, nextBurstMs = nextBurst, walletConnected = walletConnected, peerCount = peerCount)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(dashboardScrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Power mode selector
                val currentPowerMode by PowerModeManager.modeFlow.collectAsState()
                val pmm by BitcoindService.activePowerModeManagerFlow.collectAsState()
                val coroutineScope = rememberCoroutineScope()
                val autoEnabled by PowerModeManager.autoEnabledFlow.collectAsState()
                val networkMonitor by BitcoindService.activeNetworkMonitorFlow.collectAsState()
                val batteryMon by BitcoindService.activeBatteryMonitorFlow.collectAsState()

                PowerModeSelector(
                    currentMode = currentPowerMode,
                    onModeSelected = { mode ->
                        pmm?.setMode(mode, coroutineScope)
                            ?: run {
                                // If manager not ready yet, just save preference
                                val prefs = context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                prefs.edit().putString("power_mode", mode.name).apply()
                            }
                    }
                )

                // Auto power mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Auto-adjust data mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = { enabled ->
                            val nm = networkMonitor
                            val bm = batteryMon
                            if (pmm != null && nm != null && bm != null) {
                                pmm!!.setAutoEnabled(enabled, nm.networkState, bm.state, coroutineScope)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFF9800))
                    )
                }

                // Tor network toggle
                val torEnabled by com.pocketnode.tor.TorManager.enabledFlow.collectAsState()
                val torStatus by com.pocketnode.tor.TorManager.statusFlow.collectAsState()
                var torSwitching by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🧅 Route all traffic through Tor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when (torStatus) {
                                com.pocketnode.tor.TorManager.TorStatus.OFF -> "Off. Clearnet connections visible to ISP."
                                com.pocketnode.tor.TorManager.TorStatus.BOOTSTRAPPING -> "Bootstrapping Tor..."
                                com.pocketnode.tor.TorManager.TorStatus.RUNNING -> "Active. All traffic routed through Tor."
                                com.pocketnode.tor.TorManager.TorStatus.ERROR -> "Failed to start. Using clearnet."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (torStatus) {
                                com.pocketnode.tor.TorManager.TorStatus.RUNNING -> Color(0xFF4CAF50)
                                com.pocketnode.tor.TorManager.TorStatus.BOOTSTRAPPING -> Color(0xFFFF9800)
                                com.pocketnode.tor.TorManager.TorStatus.ERROR -> Color(0xFFF44336)
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            }
                        )
                    }
                    Switch(
                        checked = torEnabled,
                        enabled = !torSwitching,
                        onCheckedChange = { enabled ->
                            torSwitching = true
                            val tm = com.pocketnode.tor.TorManager.getInstance(context)
                            tm.setEnabled(enabled)
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                // Stop Lightning first (clean shutdown)
                                try {
                                    val ls = com.pocketnode.lightning.LightningService.getInstance(context)
                                    val wasRunning = com.pocketnode.lightning.LightningService.stateFlow.value.status == com.pocketnode.lightning.LightningService.LightningState.Status.RUNNING
                                    if (wasRunning) {
                                        ls.stop()
                                        // Mark it so bitcoind auto-restarts it when synced
                                        context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("lightning_was_running", true).apply()
                                    }
                                } catch (_: Exception) {}

                                // Stop bitcoind service
                                context.stopService(android.content.Intent(context, BitcoindService::class.java))
                                kotlinx.coroutines.delay(2000) // Let it shut down

                                // Start/stop Tor proxy
                                tm.applyState()

                                // Wait for Tor to be ready if enabling
                                if (enabled) {
                                    var waited = 0
                                    while (com.pocketnode.tor.TorManager.statusFlow.value != com.pocketnode.tor.TorManager.TorStatus.RUNNING && waited < 30000) {
                                        kotlinx.coroutines.delay(500)
                                        waited += 500
                                    }
                                }

                                // Restart bitcoind (will pick up -proxy from enabledFlow)
                                val intent = android.content.Intent(context, BitcoindService::class.java)
                                context.startForegroundService(intent)
                                torSwitching = false
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF9C27B0))
                    )
                }
                if (torSwitching) {
                    Text(
                        if (torEnabled) "🧅 Starting Tor and restarting node..." else "Stopping Tor and restarting node...",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9800),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                // Tor info panel — always available
                val hasLightning = remember {
                    val filterDir = context.filesDir.resolve("bitcoin/indexes/blockfilter/basic")
                    filterDir.exists() && (filterDir.listFiles()?.size ?: 0) > 1
                }
                val torRunning = torEnabled && torStatus == com.pocketnode.tor.TorManager.TorStatus.RUNNING
                var showTorInfo by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTorInfo = !showTorInfo }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "ⓘ Network privacy info",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (torRunning) Color(0xFF9C27B0) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        if (showTorInfo) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
                if (showTorInfo) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Bitcoin P2P", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        if (torRunning) {
                            Text("• Only connects to .onion peers", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                            Text("• Your IP hidden from all peers", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                        } else {
                            Text("• Clearnet peers can see your IP", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                        }

                        Spacer(Modifier.height(4.dp))
                        Text("HTTP calls", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        if (torRunning) {
                            Text("• mempool.space via .onion address", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                            Text("• GitHub updates via Tor exit node", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                        } else {
                            Text("• mempool.space can see your IP", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                            Text("• GitHub can see your IP (updates)", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                        }

                        if (hasLightning) {
                            Spacer(Modifier.height(4.dp))
                            Text("Lightning", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            if (torRunning) {
                                Text("• All peer connections via Tor", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                                Text("• Gossip sync (RGS) via Tor exit node", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                                Text("  No .onion endpoint available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                Text("• Watchtower via .onion address", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                            } else {
                                Text("• Lightning peers can see your IP", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                                Text("• Gossip sync server can see your IP", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                                Text("• Watchtower uses Tor independently", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (torRunning) "Tor hides your IP. Your node pubkey is still permanent and linkable across sessions."
                            else "Enable Tor to hide your IP from peers and external services.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                // Lightning node button — shown when LDK Lightning is enabled
                run {
                    val ldkEnabled = LocalContext.current.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                        .getBoolean("ldk_lightning_enabled", true)
                    if (ldkEnabled) {
                        val lightningState by com.pocketnode.lightning.LightningService.stateFlow.collectAsState()
                        Button(
                            onClick = onNavigateToLightning,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                        ) {
                            Text(
                                when (lightningState.status) {
                                    com.pocketnode.lightning.LightningService.LightningState.Status.RUNNING ->
                                        if (lightningState.channelCount > 0)
                                            "⚡ LDK Lightning (${lightningState.channelCount} channel${if (lightningState.channelCount != 1) "s" else ""})"
                                        else "⚡ LDK Lightning"
                                    com.pocketnode.lightning.LightningService.LightningState.Status.STARTING ->
                                        "⚡ Lightning Starting..."
                                    com.pocketnode.lightning.LightningService.LightningState.Status.ERROR ->
                                        "⚡ Lightning Error"
                                    else -> "⚡ LDK Lightning"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Lightning errors shown on Lightning screen, not here
                    }
                }

                // Status indicator
                StatusHeader(nodeStatus = nodeStatus, chain = chain, detail = startupDetail, miniLog = miniLog)

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
                    var showPeerDialog by remember { mutableStateOf(false) }
                    val rpcCreds = remember { com.pocketnode.util.ConfigGenerator.readCredentials(context) }
                    StatsGrid(
                        peerCount = peerCount,
                        sizeOnDisk = sizeOnDisk,
                        mempoolSize = mempoolSize,
                        mempoolBytes = mempoolBytes,
                        pruned = pruned,
                        lastBlockTime = lastBlockTime,
                        ibd = ibd,
                        onPeersClick = { showPeerDialog = true },
                        onMempoolClick = { if (!mempoolPaneVisible) onNavigateToMempool() }
                    )
                    if (showPeerDialog && rpcCreds != null) {
                        PeerDetailsDialog(
                            rpcUser = rpcCreds.first,
                            rpcPassword = rpcCreds.second,
                            onDismiss = { showPeerDialog = false }
                        )
                    }
                }

                // UTXOracle price card — always processing, visibility controlled by toggle
                androidx.compose.animation.AnimatedVisibility(
                    visible = showPrice,
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    com.pocketnode.ui.components.OracleCard(
                        isNodeSynced = nodeStatus.startsWith("Synced"),
                        blockHeight = blockHeight,
                        onPriceUpdate = { oraclePrice = it },
                        onExpanded = null
                    )
                }

                // Fair Trade converter card
                androidx.compose.animation.AnimatedVisibility(
                    visible = showFairTrade && oraclePrice != null,
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    com.pocketnode.ui.components.FairTradeCard(
                        oraclePrice = oraclePrice,
                        onExpanded = null
                    )
                }

                // About card
                AboutCard()

                Spacer(Modifier.weight(1f))

                // Action buttons
                ActionButtons(
                    isRunning = isRunning,
                    isSynced = nodeStatus.startsWith("Synced"),
                    showPrice = showPrice,
                    onShowPriceChange = { showPrice = it; appPrefs.edit().putBoolean("show_price", it).apply() },
                    showFairTrade = showFairTrade,
                    onShowFairTradeChange = { showFairTrade = it; appPrefs.edit().putBoolean("show_fair_trade", it).apply() },
                    onNavigateToDataUsage = onNavigateToDataUsage,
                    onNavigateToNetworkSettings = onNavigateToNetworkSettings,
                    onNavigateToSnapshot = onNavigateToSnapshot,
                    onNavigateToNodeAccess = onNavigateToNodeAccess,
                    onNavigateToSetup = onNavigateToSetup,
                    onNavigateToWallet = onNavigateToWallet,
                    onNavigateToBlockFilter = onNavigateToBlockFilter,
                    onNavigateToWatchtower = onNavigateToWatchtower,
                    onNavigateToLightning = onNavigateToLightning,
                    onNavigateToShare = onNavigateToShare,
                    mempoolPaneVisible = mempoolPaneVisible,
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
private fun StatusHeader(nodeStatus: String, chain: String, detail: String = "", miniLog: List<String> = emptyList()) {
    val statusColor by animateColorAsState(
        targetValue = when {
            nodeStatus == "Synced" -> Color(0xFF4CAF50)
            nodeStatus == "Almost synced" -> Color(0xFF8BC34A)
            nodeStatus.startsWith("Syncing") -> Color(0xFF2196F3)
            nodeStatus.startsWith("Headers") -> Color(0xFF2196F3)
            nodeStatus == "Connected" -> Color(0xFF03A9F4)
            nodeStatus.startsWith("Pre-syncing") -> Color(0xFF2196F3)
            nodeStatus == "Loading block index" -> Color(0xFFFFC107)
            nodeStatus == "Verifying blocks" -> Color(0xFFFFC107)
            nodeStatus == "Pruning" -> Color(0xFFFFC107)
            nodeStatus == "Starting network" -> Color(0xFF03A9F4)
            nodeStatus == "Catching up" -> Color(0xFF2196F3)
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
        // Mini log — only during startup/sync, hidden when synced
        if (miniLog.isNotEmpty() && !nodeStatus.startsWith("Synced")) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 8.dp)
            ) {
                miniLog.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = 1
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
            } else if ((nodeStatus == "Synced" || nodeStatus.startsWith("Synced")) &&
                       (headerHeight <= 0 || blockHeight >= headerHeight - 1)) {
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
    ibd: Boolean,
    onPeersClick: () -> Unit = {},
    onMempoolClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            label = "Peers",
            value = peerCount.toString(),
            modifier = Modifier.weight(1f).fillMaxHeight().clickable { onPeersClick() }
        )
        StatCard(
            label = "Disk",
            value = formatBytes(sizeOnDisk),
            subtitle = if (pruned) "pruned" else "full",
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            label = "Mempool",
            value = if (ibd) "—" else "%,d".format(mempoolSize),
            subtitle = if (ibd) "waiting for sync" else formatBytes(mempoolBytes),
            modifier = Modifier.weight(1f).fillMaxHeight().clickable { onMempoolClick() }
        )
        StatCard(
            label = "Last Block",
            value = if (lastBlockTime > 0) formatTimeSince(lastBlockTime) else "—",
            subtitle = if (lastBlockTime > 0) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastBlockTime * 1000))
            } else "",
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun PeerDetailsDialog(rpcUser: String, rpcPassword: String, onDismiss: () -> Unit) {
    var peers by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val rpc = com.pocketnode.rpc.BitcoinRpcClient(rpcUser, rpcPassword)
                val result = rpc.call("getpeerinfo")
                if (result != null) {
                    val arr = result.optJSONArray("result") ?: org.json.JSONArray().also {
                        // Result might be wrapped differently
                        for (key in result.keys()) {
                            if (result.get(key) is org.json.JSONArray) {
                                val a = result.getJSONArray(key)
                                for (i in 0 until a.length()) peers = peers + a.getJSONObject(i)
                            }
                        }
                    }
                    if (peers.isEmpty()) {
                        for (i in 0 until (result.optJSONArray("result")?.length() ?: 0)) {
                            peers = peers + result.getJSONArray("result").getJSONObject(i)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PeerDetails", "Failed to get peer info", e)
            }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connected Peers (${peers.size})") },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (peers.isEmpty()) {
                Text("No peers connected", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    peers.forEach { peer ->
                        val addr = peer.optString("addr", "unknown")
                        val subver = peer.optString("subver", "").trim('/')
                        val inbound = peer.optBoolean("inbound", false)
                        val pingMs = (peer.optDouble("pingtime", 0.0) * 1000).toLong()
                        val bytesRecv = peer.optLong("bytesrecv", 0)
                        val bytesSent = peer.optLong("bytessent", 0)
                        val syncedHeaders = peer.optLong("synced_headers", -1)
                        val syncedBlocks = peer.optLong("synced_blocks", -1)

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    addr.substringBefore(":"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (subver.isNotEmpty()) {
                                    Text(subver, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        if (inbound) "↓ Inbound" else "↑ Outbound",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text("${pingMs}ms", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("↓ ${formatBytes(bytesRecv)}", style = MaterialTheme.typography.bodySmall)
                                    Text("↑ ${formatBytes(bytesSent)}", style = MaterialTheme.typography.bodySmall)
                                }
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

@Composable
private fun ActionButtons(
    isRunning: Boolean,
    isSynced: Boolean = false,
    showPrice: Boolean,
    onShowPriceChange: (Boolean) -> Unit,
    showFairTrade: Boolean,
    onShowFairTradeChange: (Boolean) -> Unit,
    onNavigateToDataUsage: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onNavigateToSnapshot: () -> Unit,
    onNavigateToNodeAccess: () -> Unit = {},
    onNavigateToSetup: () -> Unit = {},
    onNavigateToWallet: () -> Unit = {},
    onNavigateToBlockFilter: () -> Unit = {},
    onNavigateToWatchtower: () -> Unit = {},
    onNavigateToLightning: () -> Unit = {},
    onNavigateToShare: () -> Unit = {},
    mempoolPaneVisible: Boolean = false,
    onToggleNode: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val bootPrefs = LocalContext.current.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
        // Show price toggle (above Start on boot)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Show price",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = showPrice,
                onCheckedChange = { onShowPriceChange(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFFF9800)
                )
            )
        }
        // Fair Trade toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Sovereign Converter",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = showFairTrade,
                onCheckedChange = { onShowFairTradeChange(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFFF9800)
                )
            )
        }
        // Auto-start on boot toggle
        var autoStartOnBoot by remember { mutableStateOf(bootPrefs.getBoolean("auto_start_on_boot", false)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Start on boot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = autoStartOnBoot,
                onCheckedChange = {
                    autoStartOnBoot = it
                    bootPrefs.edit().putBoolean("auto_start_on_boot", it).apply()
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFFF9800)
                )
            )
        }

        // LDK Lightning toggle
        val ldkContext = LocalContext.current
        val ldkPrefs = ldkContext.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
        var lightningEnabled by remember { mutableStateOf(ldkPrefs.getBoolean("ldk_lightning_enabled", true)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "⚡ LDK Lightning",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = lightningEnabled,
                onCheckedChange = {
                    lightningEnabled = it
                    ldkPrefs.edit().putBoolean("ldk_lightning_enabled", it).apply()
                    if (!it) {
                        // Stop Lightning when disabled
                        Thread {
                            try {
                                com.pocketnode.lightning.LightningService.getInstance(ldkContext).stop()
                            } catch (_: Exception) {}
                        }.start()
                        ldkPrefs.edit().putBoolean("lightning_was_running", false).apply()
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFFF9800)
                )
            )
        }

        // App update checker
        val updateContext = LocalContext.current
        var updateInfo by remember { mutableStateOf<com.pocketnode.util.UpdateChecker.UpdateInfo?>(null) }
        var checkingUpdate by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableIntStateOf(0) }
        val currentAppVersion = remember { try { updateContext.packageManager.getPackageInfo(updateContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" } }
        var installReady by remember { mutableStateOf(false) }
        var dismissed by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "App version: $currentAppVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (downloading) {
                    Text(
                        "Downloading... $downloadProgress%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                } else if (updateInfo?.hasUpdate == true) {
                    Text(
                        "v${updateInfo!!.latestVersion} available",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                } else if (updateInfo != null && !updateInfo!!.hasUpdate) {
                    Text(
                        "Up to date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (downloading) {
                // Show progress, no button needed
            } else if (installReady) {
                Text(
                    "Check notifications for install prompt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (updateInfo?.hasUpdate != true || dismissed) {
                OutlinedButton(
                    onClick = {
                        checkingUpdate = true
                        dismissed = false
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            updateInfo = com.pocketnode.util.UpdateChecker.check(updateContext)
                            checkingUpdate = false
                        }
                    },
                    enabled = !checkingUpdate
                ) { Text(if (checkingUpdate) "Checking..." else "Check for updates") }
            }
        }

        // Update available card with release notes
        if (updateInfo?.hasUpdate == true && !dismissed) {
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                    if (updateInfo!!.releaseNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                updateInfo!!.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            OutlinedButton(onClick = { dismissed = true }) {
                                Text("Not yet")
                            }
                            val torActive = com.pocketnode.tor.TorManager.enabledFlow.collectAsState().value &&
                                com.pocketnode.tor.TorManager.statusFlow.collectAsState().value == com.pocketnode.tor.TorManager.TorStatus.RUNNING
                            if (torActive) {
                                Text(
                                    "⚠️ Downloading over Tor. Secure but slow (~88 MB).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF9800),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (updateInfo?.apkUrl != null) {
                                Button(
                                    onClick = {
                                        dismissed = true
                                        downloading = true
                                        downloadProgress = 0
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                            val success = com.pocketnode.util.UpdateChecker.downloadAndInstall(
                                                updateContext, updateInfo!!.apkUrl!!
                                            ) { progress -> downloadProgress = progress }
                                            downloading = false
                                            if (success) installReady = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                ) {
                                    Text("Update", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(updateInfo!!.htmlUrl))
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        updateContext.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                ) {
                                    Text("View release", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        // Bitcoin version selector
        val versionContext = LocalContext.current
        var selectedVersion by remember { mutableStateOf(com.pocketnode.util.BinaryExtractor.getSelectedVersion(versionContext)) }
        var showVersionPicker by remember { mutableStateOf(false) }
        var pendingVersion by remember { mutableStateOf<com.pocketnode.util.BinaryExtractor.BitcoinVersion?>(null) }
        var pendingBip110Toggle by remember { mutableStateOf<Boolean?>(null) }
        var signalBip110 by remember { mutableStateOf(com.pocketnode.util.BinaryExtractor.isSignalBip110(versionContext)) }
        val availableVersions = remember { com.pocketnode.util.BinaryExtractor.availableVersions(versionContext) }
        val allVersions = com.pocketnode.util.BinaryExtractor.BitcoinVersion.entries

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { mod ->
                    @Suppress("DEPRECATION")
                    mod.then(Modifier.padding(vertical = 4.dp))
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Bitcoin Implementation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val isBip110Active = signalBip110
                Text(
                    "${selectedVersion.displayName} ${selectedVersion.versionString}${if (isBip110Active) " + BIP 110" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
            OutlinedButton(
                onClick = { showVersionPicker = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Change", style = MaterialTheme.typography.labelSmall)
            }
        }



        if (showVersionPicker) {
            AlertDialog(
                onDismissRequest = { showVersionPicker = false },
                title = { Text("Choose Implementation") },
                text = {
                    val bip110Versions = allVersions.filter { it.supportsBip110 }
                    val otherVersions = allVersions.filter { !it.supportsBip110 }

                    @Composable
                    fun VersionCard(version: com.pocketnode.util.BinaryExtractor.BitcoinVersion) {
                        val isAvailable = version in availableVersions
                        val isSelected = version == selectedVersion
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    Color(0xFFFF9800).copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onClick = {
                                if (isAvailable && !isSelected) {
                                    pendingVersion = version
                                    showVersionPicker = false
                                }
                            },
                            enabled = isAvailable
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "${version.displayName} ${version.versionString}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isAvailable)
                                            MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                    if (isSelected) {
                                        Text("✓", color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                                    }
                                    if (!isAvailable) {
                                        Text("Coming soon", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                    }
                                }
                                Text(
                                    version.policyStance,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isAvailable)
                                        Color(0xFFFF9800).copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        // Latest first
                        otherVersions.forEach { version -> VersionCard(version) }

                        // Separator
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        Spacer(Modifier.height(2.dp))

                        // BIP 110 section
                        Text(
                            "BIP 110 Compatible",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF7931A)
                        )
                        bip110Versions.forEach { version -> VersionCard(version) }

                        // BIP 110 toggle
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Signal BIP 110",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (signalBip110) "Signaling reduced data support"
                                    else "Not signaling",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (signalBip110) Color(0xFFF7931A)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Switch(
                                checked = signalBip110,
                                onCheckedChange = { enabled ->
                                    pendingBip110Toggle = enabled
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFF7931A),
                                    checkedTrackColor = Color(0xFFF7931A).copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVersionPicker = false }) {
                        Text("Close")
                    }
                }
            )
        }

        // Restart confirmation after version change
        pendingVersion?.let { newVersion ->
            AlertDialog(
                onDismissRequest = { pendingVersion = null },
                title = { Text("Switch to ${newVersion.displayName}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Switching from ${selectedVersion.displayName} ${selectedVersion.versionString} to ${newVersion.displayName} ${newVersion.versionString}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            newVersion.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        if (isRunning) {
                            Text(
                                "Your node will be stopped and restarted with the new implementation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800)
                            )
                        } else {
                            Text(
                                "The new implementation will be used next time you start the node.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            "Your blockchain data and settings are preserved. All implementations use the same chainstate format.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        com.pocketnode.util.BinaryExtractor.setSelectedVersion(versionContext, newVersion)
                        selectedVersion = newVersion
                        pendingVersion = null
                        // If node is running, stop Lightning first, then restart bitcoind
                        if (isRunning) {
                            Thread({
                                // Stop Lightning cleanly before stopping bitcoind
                                try {
                                    val ls = com.pocketnode.lightning.LightningService.getInstance(versionContext)
                                    val wasRunning = com.pocketnode.lightning.LightningService.stateFlow.value.status == com.pocketnode.lightning.LightningService.LightningState.Status.RUNNING
                                    if (wasRunning) {
                                        ls.stop()
                                        versionContext.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("lightning_was_running", true).apply()
                                    }
                                } catch (_: Exception) {}

                                // Stop bitcoind
                                versionContext.stopService(android.content.Intent(versionContext, BitcoindService::class.java))
                                var attempts = 0
                                while (BitcoindService.isRunningFlow.value && attempts < 30) {
                                    Thread.sleep(1000)
                                    attempts++
                                }
                                // Restart with new binary
                                versionContext.startForegroundService(android.content.Intent(versionContext, BitcoindService::class.java))
                            }, "version-switch-restart").start()
                        }
                    }) {
                        Text("Switch", color = Color(0xFFFF9800))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingVersion = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // BIP 110 toggle restart confirmation
        pendingBip110Toggle?.let { enabled ->
            AlertDialog(
                onDismissRequest = { pendingBip110Toggle = null },
                title = { Text(if (enabled) "Enable BIP 110 Signaling?" else "Disable BIP 110 Signaling?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (enabled)
                                "Your node will signal support for BIP 110 (version bit 4) and prefer peers that also signal."
                            else
                                "Your node will stop signaling BIP 110 and connect to all peers equally.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isRunning) {
                            Text(
                                "Your node will be stopped and restarted to apply this change.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800)
                            )
                        } else {
                            Text(
                                "The change will take effect next time you start the node.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        com.pocketnode.util.BinaryExtractor.setSignalBip110(versionContext, enabled)
                        signalBip110 = enabled
                        pendingBip110Toggle = null
                        showVersionPicker = false
                        // Restart if running — stop Lightning first
                        if (isRunning) {
                            Thread({
                                try {
                                    val ls = com.pocketnode.lightning.LightningService.getInstance(versionContext)
                                    val wasRunning = com.pocketnode.lightning.LightningService.stateFlow.value.status == com.pocketnode.lightning.LightningService.LightningState.Status.RUNNING
                                    if (wasRunning) {
                                        ls.stop()
                                        versionContext.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("lightning_was_running", true).apply()
                                    }
                                } catch (_: Exception) {}

                                versionContext.stopService(android.content.Intent(versionContext, BitcoindService::class.java))
                                var attempts = 0
                                while (BitcoindService.isRunningFlow.value && attempts < 30) {
                                    Thread.sleep(1000)
                                    attempts++
                                }
                                versionContext.startForegroundService(android.content.Intent(versionContext, BitcoindService::class.java))
                            }, "bip110-restart").start()
                        }
                    }) {
                        Text(if (enabled) "Enable" else "Disable", color = Color(0xFFF7931A))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingBip110Toggle = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Setup row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onNavigateToSnapshot,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) { Text("Setup", fontWeight = FontWeight.Medium) }
            OutlinedButton(
                onClick = onNavigateToSetup,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) { Text("Checklist", fontWeight = FontWeight.Medium) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onNavigateToNodeAccess,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Access", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToNetworkSettings,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Data", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToDataUsage,
                modifier = Modifier.weight(1f),
                enabled = !mempoolPaneVisible,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Mempool", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = onNavigateToWallet,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) { Text("Connect", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        }

        // "Add Lightning Support" removed: LDK works without block filters.
        // Block filter install still available via Settings if needed for Neutrino wallets.

        // Share The Freedom button — always visible, greyed when syncing
        val shareEnabled = isSynced || !isRunning
        OutlinedButton(
            onClick = onNavigateToShare,
            enabled = shareEnabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Text(
                if (shareEnabled) "📡 Share The Freedom"
                else "📡 Share The Freedom (syncing...)",
                style = MaterialTheme.typography.labelSmall
            )
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

@Composable
private fun AboutCard() {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Bitcoin Pocket Node",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Sovereign Bitcoin full node in your pocket. No servers, no trust, no compromise.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.avatar_freeonlineuser),
                            contentDescription = "FreeOnlineUser",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            "Built by @FreeOnlineUser",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        "Open source, contributions welcome",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/FreeOnlineUser/bitcoin-pocket-node")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View on GitHub")
                    }
                    val packageInfo = try {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    } catch (_: Exception) { null }
                    Text(
                        "Version ${packageInfo?.versionName ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
