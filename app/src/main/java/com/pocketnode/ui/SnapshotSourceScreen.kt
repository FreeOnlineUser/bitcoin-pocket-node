package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Placeholder screen for selecting a UTXO snapshot source.
 * Phase 2 — the actual transfer logic isn't implemented yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotSourceScreen(
    onBack: () -> Unit,
    onPullFromNode: () -> Unit = {},
    onDownloadFromInternet: () -> Unit = {},
    onNearbyNode: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCopyChainstate: () -> Unit = {}
) {
    var showComingSoon by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Get Started") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Choose Snapshot Source",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Get your node up and running by loading blockchain data. " +
                "Choose the method that works best for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "From Your Node",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            SnapshotOption(
                icon = Icons.Outlined.PhoneAndroid,
                title = "Copy from a trusted Pocket Node or relay",
                subtitle = "Scan a QR code from someone sharing the freedom. " +
                    "Works over WiFi or LAN. ~13 GB, ~20 minutes. Includes Lightning.",
                onClick = onNearbyNode
            )

            SnapshotOption(
                icon = Icons.Outlined.Lan,
                title = "Sync from your own node",
                subtitle = "Copy chainstate directly from your node over LAN. " +
                    "Instant full node at chain tip. ~9 GB, ~20 minutes.",
                onClick = onPullFromNode
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "No Node Required",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            SnapshotOption(
                icon = Icons.Outlined.CloudDownload,
                title = "Download snapshot",
                subtitle = "Fetch from utxo.download (~9 GB). " +
                    "Block hash verified before loading, background " +
                    "validation checks every transaction.",
                onClick = onDownloadFromInternet
            )

            SnapshotOption(
                icon = Icons.Outlined.FolderOpen,
                title = "Load from file",
                subtitle = "Import a snapshot from Downloads, USB, or SD card.",
                onClick = { showComingSoon = true }
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "What You Get",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FeaturePreviewItem("⛓", "Full Bitcoin Node",
                        "Validate every transaction yourself. No trusted third parties.")
                    FeaturePreviewItem("⚡", "Lightning Wallet",
                        "Send and receive Lightning payments. Built-in wallet powered by LDK.")
                    FeaturePreviewItem("\uD83D\uDEE1\uFE0F", "Watchtower via Tor",
                        "Your home node watches your channels automatically. Direct .onion connection.")
                    FeaturePreviewItem("\uD83D\uDCB0", "Sovereign Price Discovery",
                        "BTC/USD price derived from on-chain data. No exchange APIs.")
                    FeaturePreviewItem("\uD83D\uDCCA", "Mempool Viewer",
                        "Fee estimates, projected blocks, transaction search. All from your own node.")
                    FeaturePreviewItem("\uD83D\uDD17", "External Wallet Support",
                        "Connect BlueWallet or Zeus to your phone's Electrum and Lightning servers.")
                }
            }

            Text(
                "Everything runs locally on your phone. The only external service used is mempool.space for Lightning peer discovery (optional).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // Coming soon dialog
    if (showComingSoon) {
        AlertDialog(
            onDismissRequest = { showComingSoon = false },
            title = { Text("Coming Soon") },
            text = { Text("Snapshot loading will be available in a future update.") },
            confirmButton = {
                TextButton(onClick = { showComingSoon = false }) { Text("OK") }
            }
        )
    }

    // Node IP dialog removed — now uses dedicated NodeConnectionScreen
}

@Composable
private fun FeaturePreviewItem(emoji: String, title: String, description: String) {
    Row {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
