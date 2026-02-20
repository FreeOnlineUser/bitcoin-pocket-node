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
        val todayUsage = remember(networkState, activeMonitor) { activeMonitor?.getTodayUsage() }

        // Detect wide screen (foldable unfolded) — 550dp+ triggers dual-pane
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 550.dp

        NavHost(navController = navController, startDestination = "status") {
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
                                onAllowCellular = { syncController?.confirmCellularSync() },
                                onNavigateToDataUsage = {}, // Greyed out in wide mode
                                onNavigateToNetworkSettings = { navController.navigate("network_settings") },
                                onNavigateToNodeAccess = { navController.navigate("node_access") },
                                onNavigateToWallet = { navController.navigate("connect_wallet") },
                                onNavigateToBlockFilter = { navController.navigate("block_filter") },
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
                    onAllowCellular = { syncController?.confirmCellularSync() },
                    onNavigateToDataUsage = { navController.navigate("data_usage") },
                    onNavigateToNetworkSettings = { navController.navigate("network_settings") },
                    onNavigateToNodeAccess = { navController.navigate("node_access") },
                    onNavigateToWallet = { navController.navigate("connect_wallet") },
                    onNavigateToBlockFilter = { navController.navigate("block_filter") }
                )
                }
            }
            composable("setup") {
                SetupChecklistScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToSnapshot = { navController.navigate("snapshot") },
                    onNavigateToNodeAccess = { navController.navigate("node_access") },
                    onNavigateToNetworkSettings = { navController.navigate("network_settings") },
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
                var allowCellular by remember { mutableStateOf(syncPrefs.getBoolean("allow_cellular_sync", false)) }
                var cellBudget by remember { mutableStateOf(syncPrefs.getLong("cellular_budget_mb", 0)) }
                var wifiBudget by remember { mutableStateOf(syncPrefs.getLong("wifi_budget_mb", 0)) }
                NetworkSettingsScreen(
                    allowCellular = allowCellular,
                    cellularBudgetMb = cellBudget,
                    wifiBudgetMb = wifiBudget,
                    networkMonitor = networkMonitor,
                    onAllowCellularChanged = {
                        allowCellular = it
                        syncPrefs.edit().putBoolean("allow_cellular_sync", it).apply()
                        syncController?.allowCellularSync = it
                    },
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
                    onPullFromNode = {
                        if (setupManager.isSetupDone()) {
                            navController.navigate("chainstate_copy")
                        } else {
                            navController.navigate("node_setup/chainstate_copy")
                        }
                    },
                    onCopyChainstate = {
                        if (setupManager.isSetupDone()) {
                            navController.navigate("chainstate_copy")
                        } else {
                            navController.navigate("node_setup/chainstate_copy")
                        }
                    }
                )
            }
            composable("internet_download") {
                InternetDownloadScreen(
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        navController.popBackStack("status", inclusive = false)
                    }
                )
            }
            composable("chainstate_copy") {
                ChainstateCopyScreen(
                    onBack = { navController.popBackStack() },
                    onComplete = {
                        navController.popBackStack("status", inclusive = false)
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
            composable("block_filter") {
                BlockFilterUpgradeScreen(
                    onBack = { navController.popBackStack() },
                    onRestartNode = {
                        navController.popBackStack("status", inclusive = false)
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
