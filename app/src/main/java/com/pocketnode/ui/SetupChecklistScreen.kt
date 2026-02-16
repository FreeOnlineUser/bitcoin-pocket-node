package com.pocketnode.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.util.SetupChecker
import kotlinx.coroutines.launch

/**
 * Config mode — setup checklist with auto-detected completion status.
 * Each step can be tapped to navigate to its setup screen.
 * Ticked steps are auto-detected; users can revisit any step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupChecklistScreen(
    onBack: () -> Unit,
    onNavigateToSnapshot: () -> Unit,
    onNavigateToNodeAccess: () -> Unit,
    onNavigateToNetworkSettings: () -> Unit,
    onStartNode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SetupChecker.SetupState()) }
    var checking by remember { mutableStateOf(true) }

    // Auto-check on entry
    LaunchedEffect(Unit) {
        state = SetupChecker.check(context)
        checking = false
    }

    fun refresh() {
        checking = true
        scope.launch {
            state = SetupChecker.check(context)
            checking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Setup Checklist",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Completed steps are auto-detected. Tap any step to configure or revisit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(12.dp))

            if (checking) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                // Step 1: Binary
                ChecklistItem(
                    step = 1,
                    title = "bitcoind Binary",
                    description = if (state.binaryInstalled)
                        "Installed and ready"
                    else
                        "Binary will be extracted on first launch",
                    completed = state.binaryInstalled,
                    onClick = onStartNode  // Starting node triggers binary extraction
                )

                // Step 2: Configuration
                ChecklistItem(
                    step = 2,
                    title = "Node Configuration",
                    description = if (state.configGenerated)
                        "bitcoin.conf generated with RPC credentials"
                    else
                        "Will be auto-generated on first launch",
                    completed = state.configGenerated,
                    onClick = onStartNode
                )

                // Step 3: Remote node (optional)
                ChecklistItem(
                    step = 3,
                    title = "Remote Node Access",
                    description = when {
                        state.remoteNodeConfigured -> "SFTP access configured on your node"
                        else -> "Optional — connect to your home node for fast sync"
                    },
                    completed = state.remoteNodeConfigured,
                    optional = true,
                    onClick = onNavigateToNodeAccess
                )

                // Step 4: UTXO Snapshot
                ChecklistItem(
                    step = 4,
                    title = "UTXO Snapshot",
                    description = when {
                        state.snapshotLoaded -> "Chainstate loaded — node has blockchain data"
                        else -> "Load a UTXO snapshot or copy chainstate from your node"
                    },
                    completed = state.snapshotLoaded,
                    onClick = onNavigateToSnapshot
                )

                // Step 5: Sync
                ChecklistItem(
                    step = 5,
                    title = "Fully Synced",
                    description = when {
                        state.nodeSynced -> "Node is synced to chain tip"
                        state.nodeRunning && state.blockHeight > 0 ->
                            "Syncing — block %,d (${"%.1f".format(state.syncProgress * 100)}%%)".format(state.blockHeight)
                        state.snapshotLoaded -> "Start node to begin syncing"
                        else -> "Complete previous steps first"
                    },
                    completed = state.nodeSynced,
                    onClick = if (state.snapshotLoaded && !state.nodeRunning) onStartNode else ({})
                )

                Spacer(Modifier.height(16.dp))

                // Summary
                val completedCount = listOf(
                    state.binaryInstalled,
                    state.configGenerated,
                    state.snapshotLoaded,
                    state.nodeSynced
                ).count { it }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (completedCount == 4)
                            Color(0xFF1B5E20).copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (completedCount) {
                                4 -> "All done — your node is ready"
                                3 -> "Almost there — one step left"
                                else -> "$completedCount of 4 required steps complete"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (completedCount == 4) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Network settings shortcut
                OutlinedButton(
                    onClick = onNavigateToNetworkSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Network Settings")
                }
            }
        }
    }
}

@Composable
private fun ChecklistItem(
    step: Int,
    title: String,
    description: String,
    completed: Boolean,
    optional: Boolean = false,
    onClick: () -> Unit
) {
    val iconColor by animateColorAsState(
        targetValue = if (completed) Color(0xFF4CAF50) else Color(0xFF757575),
        animationSpec = tween(300),
        label = "checkColor"
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (completed)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (completed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (completed) "Complete" else "Incomplete",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (optional) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "optional",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
