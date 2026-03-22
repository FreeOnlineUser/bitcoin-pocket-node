package com.pocketnode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.service.BitcoindService
import com.pocketnode.service.SyncController
import com.pocketnode.snapshot.NodeSetupManager
import com.pocketnode.ui.mempool.MempoolScreen
import com.pocketnode.ui.mempool.TransactionSearchScreen

/**
 * Root composable — dark Material 3 theme + navigation.
 */

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFF7931A),      // Bitcoin orange
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
    surface = androidx.compose.ui.graphics.Color(0xFF121212),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
    background = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
)

@Composable
fun PocketNodeApp(
    networkMonitor: NetworkMonitor? = null,
    syncController: SyncController? = null
) {
    MaterialTheme(colorScheme = DarkColors) {
        val navController = rememberNavController()
        val context = LocalContext.current
        val setupManager = remember { NodeSetupManager(context) }

        // First run: no bitcoin.conf = never set up.
        // Clear any Seedvault-restored preferences on fresh install.
        val isFirstRun = remember {
            val fresh = !java.io.File(context.filesDir, "bitcoin/bitcoin.conf").exists()
            if (fresh) {
                setupManager.clearCredentials()
            }
            fresh
        }

        // Observe network state — reactively watch for service starting
        val serviceMonitor by BitcoindService.activeNetworkMonitorFlow.collectAsState()
        val serviceController by BitcoindService.activeSyncControllerFlow.collectAsState()
        val activeMonitor = networkMonitor ?: serviceMonitor
        val activeController = syncController ?: serviceController

        // Default to WIFI (no banner) until NetworkMonitor is ready — avoids false "no network" flash
        val networkState by (activeMonitor?.networkState
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(NetworkState.WIFI) })
            .collectAsState()
        val syncPaused by (activeController?.syncPaused
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) })
            .collectAsState()
        // Reactive usage state from singleton NetworkMonitor
        val usageState by (activeMonitor?.usageState
            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.pocketnode.network.UsageState()) })
            .collectAsState()
        val todayUsage = usageState.today.takeIf { it.date.isNotEmpty() }

        // Detect wide screen (foldable unfolded) — 550dp+ triggers dual-pane
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 550.dp

        // Check if Lightning has been unlocked (any channel ever opened)
        val lightningUnlocked = remember {
            context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("lightning_unlocked", false)
        }
        val startDest = when {
            isFirstRun -> "snapshot"
            lightningUnlocked -> "lightning_pay"
            else -> "status"
        }

        // Lightning Pay unlocks on next app restart after first channel opens
        // (pref set by LightningService.updateState and ChannelReady event)

        NavHost(navController = navController, startDestination = startDest) {
            composable("lightning_pay") {
                com.pocketnode.ui.lightning.LightningPayScreen(
                    onNavigateToLightning = { navController.navigate("lightning") },
                    onNavigateToScanner = { navController.navigate("qr_scanner_pay") },
                    onNavigateToReceive = { navController.navigate("lightning_receive") },
                    onNavigateToPaymentHistory = { navController.navigate("lightning_history") }
                )
            }
            composable("status") {
                if (isWideScreen) {
                    // Dual-pane: dashboard left, mempool right
                    Row(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            NodeStatusScreen(
                                onNavigateToSnapshot = { navController.navigate("snapshot") },
                                onNavigateToSetup = { navController.navigate("setup") },
                                networkState = networkState,
                                syncPaused = syncPaused,
                                todayUsage = todayUsage,
                                onNavigateToDataUsage = {}, // Greyed out in wide mode
                                onNavigateToNetworkSettings = { navController.navigate("network_settings") },
                                onNavigateToNodeAccess = { navController.navigate("node_access") },
                                onNavigateToWallet = { navController.navigate("connect_wallet") },
                                onNavigateToBlockFilter = { navController.navigate("block_filter") },
                                onNavigateToWatchtower = { navController.navigate("watchtower") },
                                onNavigateToLightning = { navController.navigate("lightning") },
                                onNavigateToShare = { navController.navigate("share") },
                                mempoolPaneVisible = true
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            MempoolScreen(
                                onNavigateToTransactionSearch = { navController.navigate("tx_search") },
                                onBack = {} // No back in dual-pane
                            )
                        }
                    }
                } else {
                NodeStatusScreen(
                    onNavigateToSnapshot = { navController.navigate("snapshot") },
                    onNavigateToSetup = { navController.navigate("setup") },
                    networkState = networkState,
                    syncPaused = syncPaused,
                    todayUsage = todayUsage,
                    onNavigateToDataUsage = { navController.navigate("data_usage") },
                    onNavigateToNetworkSettings = { navController.navigate("network_settings") },
                    onNavigateToNodeAccess = { navController.navigate("node_access") },
                    onNavigateToWallet = { navController.navigate("connect_wallet") },
                    onNavigateToBlockFilter = { navController.navigate("block_filter") },
                    onNavigateToWatchtower = { navController.navigate("watchtower") },
                    onNavigateToLightning = { navController.navigate("lightning") },
                    onNavigateToShare = { navController.navigate("share") },
                    onNavigateToMempool = { navController.navigate("mempool") }
                )
                }
            }
            composable("setup") {
                SetupChecklistScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToSnapshot = { navController.navigate("snapshot") },
                    onNavigateToNodeAccess = { navController.navigate("node_access") },
                    onNavigateToNetworkSettings = { navController.navigate("network_settings") },
                    onNavigateToBlockFilter = { navController.navigate("block_filter") },
                    onStartNode = {
                        // Navigate back to dashboard — starting happens there
                        navController.popBackStack("status", inclusive = false)
                    }
                )
            }
            composable("node_access") {
                NodeAccessScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("mempool") {
                MempoolScreen(
                    onNavigateToTransactionSearch = { navController.navigate("tx_search") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("data_usage") {
                MempoolScreen(
                    onNavigateToTransactionSearch = { navController.navigate("tx_search") },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("tx_search") {
                TransactionSearchScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("network_settings") {
                val ctx = LocalContext.current
                val syncPrefs = remember { ctx.getSharedPreferences("sync_settings", android.content.Context.MODE_PRIVATE) }
                var cellBudget by remember { mutableStateOf(syncPrefs.getLong("cellular_budget_mb", 0)) }
                var wifiBudget by remember { mutableStateOf(syncPrefs.getLong("wifi_budget_mb", 0)) }
                NetworkSettingsScreen(
                    cellularBudgetMb = cellBudget,
                    wifiBudgetMb = wifiBudget,
                    networkMonitor = networkMonitor,
                    onCellularBudgetChanged = {
                        cellBudget = it
                        syncController?.cellularBudgetMb = it
                    },
                    onWifiBudgetChanged = {
                        wifiBudget = it
                        syncController?.wifiBudgetMb = it
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("snapshot") {
                SnapshotSourceScreen(
                    onBack = { navController.popBackStack() },
                    onDownloadFromInternet = {
                        navController.navigate("internet_download")
                    },
                    onNearbyNode = {
                        navController.navigate("nearby_node?host=&port=")
                    },
                    onPullFromNode = {
                        val hasData = java.io.File(context.filesDir, "bitcoin/bitcoin.conf").exists()
                        if (setupManager.isSetupDone() && hasData) {
                            navController.navigate("chainstate_copy")
                        } else {
                            setupManager.clearCredentials()
                            navController.navigate("node_setup/chainstate_copy")
                        }
                    },
                    onCopyChainstate = {
                        val hasData = java.io.File(context.filesDir, "bitcoin/bitcoin.conf").exists()
                        if (setupManager.isSetupDone() && hasData) {
                            navController.navigate("chainstate_copy")
                        } else {
                            setupManager.clearCredentials()
                            navController.navigate("node_setup/chainstate_copy")
                        }
                    }
                )
            }
            composable("internet_download") {
                InternetDownloadScreen(
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        navController.navigate("status") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
            composable("chainstate_copy") {
                ChainstateCopyScreen(
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        navController.navigate("status") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
            composable("node_setup/{nextRoute}") { backStackEntry ->
                val nextRoute = backStackEntry.arguments?.getString("nextRoute") ?: ""
                NodeSetupScreen(
                    onBack = { navController.popBackStack() },
                    onSetupComplete = {
                        // Navigate directly to the intended destination
                        if (nextRoute.isNotEmpty()) {
                            navController.navigate(nextRoute) {
                                popUpTo("snapshot") { inclusive = false }
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable("connect_wallet") {
                ConnectWalletScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("watchtower") {
                WatchtowerScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable("lightning") {
                LightningScreen(
                    onNavigateBack = { navController.navigate("status") { popUpTo("lightning_pay") { inclusive = false } } },
                    onNavigateToLightningPay = { navController.navigate("lightning_pay") { popUpTo("lightning") { inclusive = true } } },
                    onNavigateToSend = { navController.navigate("lightning_send") },
                    onNavigateToReceive = { navController.navigate("lightning_receive") },
                    onNavigateToHistory = { navController.navigate("lightning_history") },
                    onNavigateToOpenChannel = { navController.navigate("lightning_open_channel") },
                    onNavigateToSeedBackup = { navController.navigate("seed_backup") },
                    onNavigateToWatchtower = { navController.navigate("watchtower") },
                    onNavigateToSendOnchain = { navController.navigate("send_onchain") }
                )
            }
            composable("seed_backup") {
                com.pocketnode.ui.lightning.SeedBackupScreen(
                    lightningService = com.pocketnode.lightning.LightningService.getInstance(context),
                    onBack = { navController.popBackStack() }
                )
            }
            composable("lightning_send") { backStackEntry ->
                val scannedResult = backStackEntry.savedStateHandle
                    .get<String>("scanned_qr")
                // Clear after reading so it doesn't persist on recomposition
                LaunchedEffect(scannedResult) {
                    if (scannedResult != null) {
                        backStackEntry.savedStateHandle.remove<String>("scanned_qr")
                    }
                }
                com.pocketnode.ui.lightning.SendPaymentScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToScanner = { navController.navigate("qr_scanner") },
                    scannedQr = scannedResult
                )
            }
            composable("qr_scanner") {
                com.pocketnode.ui.lightning.QrScannerScreen(
                    onResult = { result ->
                        navController.previousBackStackEntry?.savedStateHandle?.set("scanned_qr", result)
                        navController.popBackStack()
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            // QR scanner from Lightning Pay — scan goes to send, back goes to send (manual entry)
            composable("qr_scanner_pay") {
                com.pocketnode.ui.lightning.QrScannerScreen(
                    onResult = { result ->
                        navController.navigate("lightning_send_pay") {
                            popUpTo("lightning_pay") { inclusive = false }
                        }
                        navController.currentBackStackEntry?.savedStateHandle?.set("scanned_qr", result)
                    },
                    onNavigateBack = {
                        navController.navigate("lightning_send_pay") {
                            popUpTo("lightning_pay") { inclusive = false }
                        }
                    }
                )
            }
            // Send from Lightning Pay screen
            composable("lightning_send_pay") { backStackEntry ->
                val scannedResult = backStackEntry.savedStateHandle
                    .get<String>("scanned_qr")
                LaunchedEffect(scannedResult) {
                    if (scannedResult != null) {
                        backStackEntry.savedStateHandle.remove<String>("scanned_qr")
                    }
                }
                com.pocketnode.ui.lightning.SendPaymentScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToScanner = { navController.navigate("qr_scanner") },
                    scannedQr = scannedResult
                )
            }
            composable("lightning_receive") {
                com.pocketnode.ui.lightning.ReceivePaymentScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("lightning_history") {
                com.pocketnode.ui.lightning.PaymentHistoryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("send_onchain") { backStackEntry ->
                val scannedResult = backStackEntry.savedStateHandle
                    .get<String>("scanned_qr")
                LaunchedEffect(scannedResult) {
                    if (scannedResult != null) {
                        backStackEntry.savedStateHandle.remove<String>("scanned_qr")
                    }
                }
                com.pocketnode.ui.lightning.SendOnchainScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToScanner = { navController.navigate("qr_scanner_onchain") },
                    scannedQr = scannedResult
                )
            }
            composable("qr_scanner_onchain") {
                com.pocketnode.ui.lightning.QrScannerScreen(
                    onResult = { result ->
                        navController.previousBackStackEntry?.savedStateHandle?.set("scanned_qr", result)
                        navController.popBackStack()
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("lightning_open_channel") { backStackEntry ->
                val savedState = backStackEntry.savedStateHandle
                val selectedNodeId = savedState.get<String>("selected_node_id") ?: ""
                val selectedAddress = savedState.get<String>("selected_address") ?: ""
                val selectedAlias = savedState.get<String>("selected_alias") ?: ""
                com.pocketnode.ui.lightning.OpenChannelScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPeerBrowser = { navController.navigate("peer_browser") },
                    prefillNodeId = selectedNodeId,
                    prefillAddress = selectedAddress,
                    prefillAlias = selectedAlias
                )
            }
            composable("peer_browser") {
                com.pocketnode.ui.lightning.PeerBrowserScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSelectNode = { nodeId, address, alias, _ ->
                        navController.previousBackStackEntry?.savedStateHandle?.apply {
                            set("selected_node_id", nodeId)
                            set("selected_address", address)
                            set("selected_alias", alias)
                        }
                        navController.popBackStack()
                    }
                )
            }
            composable("block_filter") {
                BlockFilterUpgradeScreen(
                    onBack = { navController.popBackStack() },
                    onRestartNode = {
                        // Restart bitcoind so block filter config takes effect
                        val ctx = navController.context
                        val intent = android.content.Intent(ctx, com.pocketnode.service.BitcoindService::class.java)
                        ctx.stopService(intent)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                ctx.startForegroundService(intent)
                            } else {
                                ctx.startService(intent)
                            }
                        }, 1500)
                        navController.popBackStack("status", inclusive = false)
                    }
                )
            }
            composable("share") {
                ShareScreen(
                    onBack = { navController.popBackStack() },
                    onStopNode = {
                        val ctx = navController.context
                        val intent = android.content.Intent(ctx, com.pocketnode.service.BitcoindService::class.java)
                        ctx.stopService(intent)
                    },
                    chainHeight = com.pocketnode.service.BitcoindService.lastBlockHeight
                )
            }
            composable(
                "nearby_node?host={host}&port={port}",
                arguments = listOf(
                    androidx.navigation.navArgument("host") { defaultValue = "" },
                    androidx.navigation.navArgument("port") { defaultValue = "" }
                ),
                deepLinks = listOf(
                    androidx.navigation.navDeepLink { uriPattern = "pocketnode://share?host={host}&port={port}" }
                )
            ) { backStackEntry ->
                val deepLinkHost = backStackEntry.arguments?.getString("host")?.takeIf { it.isNotEmpty() }
                val deepLinkPort = backStackEntry.arguments?.getString("port")?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                NearbyNodeScreen(
                    onBack = { navController.popBackStack() },
                    initialHost = deepLinkHost,
                    initialPort = deepLinkPort,
                    onComplete = {
                        navController.popBackStack("status", inclusive = false)
                    },
                    onScanQr = { callback ->
                        // Store callback and navigate to QR scanner
                        navController.navigate("qr_scanner")
                    }
                )
            }
            composable("node_connect") {
                NodeConnectionScreen(
                    onBack = { navController.popBackStack() },
                    onConnected = { host, port, user, pass ->
                        navController.navigate("snapshot_progress/$host/$port/$user/$pass")
                    }
                )
            }
            composable("snapshot_progress/{host}/{port}/{user}/{pass}") { backStackEntry ->
                val args = backStackEntry.arguments!!
                SnapshotProgressScreen(
                    host = args.getString("host") ?: "",
                    port = args.getString("port")?.toIntOrNull() ?: 8332,
                    rpcUser = args.getString("user") ?: "",
                    rpcPassword = args.getString("pass") ?: "",
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        navController.popBackStack("status", inclusive = false)
                    }
                )
            }
        }
        } // BoxWithConstraints
    }
}
