package com.pocketnode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.service.BitcoindService
import com.pocketnode.service.SyncController
import com.pocketnode.snapshot.NodeSetupManager

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

        NavHost(navController = navController, startDestination = "status") {
            composable("status") {
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
                    onNavigateToWallet = { navController.navigate("connect_wallet") }
                )
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
                    onBack = { navController.popBackStack() }
                )
            }
            composable("network_settings") {
                NetworkSettingsScreen(
                    allowCellular = syncController?.allowCellularSync ?: false,
                    cellularBudgetMb = syncController?.cellularBudgetMb ?: 0,
                    wifiBudgetMb = syncController?.wifiBudgetMb ?: 0,
                    networkMonitor = networkMonitor,
                    onAllowCellularChanged = { syncController?.allowCellularSync = it },
                    onCellularBudgetChanged = { syncController?.cellularBudgetMb = it },
                    onWifiBudgetChanged = { syncController?.wifiBudgetMb = it },
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
    }
}
