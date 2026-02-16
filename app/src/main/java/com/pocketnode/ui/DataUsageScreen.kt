package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.network.DataUsageEntry
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.ui.components.formatBytes

/**
 * Detailed data usage screen with daily breakdown and monthly totals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataUsageScreen(
    networkMonitor: NetworkMonitor?,
    cellularBudgetMb: Long,
    onBack: () -> Unit
) {
    val recentUsage = remember(networkMonitor) {
        networkMonitor?.getRecentUsage(7) ?: emptyList()
    }
    val monthCellular = remember(networkMonitor) {
        networkMonitor?.getMonthCellularUsage() ?: 0L
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Usage") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Monthly cellular summary
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Monthly Cellular", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatBytes(monthCellular),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (cellularBudgetMb > 0) {
                        Spacer(Modifier.height(8.dp))
                        val usedMb = monthCellular / (1024 * 1024)
                        val progress = (usedMb.toFloat() / cellularBudgetMb).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth(),
                            color = if (progress > 0.9f) Color(0xFFF44336) else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$usedMb MB / $cellularBudgetMb MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Daily breakdown
            Text("Last 7 Days", style = MaterialTheme.typography.titleMedium)

            recentUsage.forEach { entry ->
                DayUsageRow(entry)
            }
        }
    }
}

@Composable
private fun DayUsageRow(entry: DataUsageEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                entry.date,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("WiFi", style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50))
                    Text(
                        "↓ ${formatBytes(entry.wifiRx)}  ↑ ${formatBytes(entry.wifiTx)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Cellular", style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFC107))
                    Text(
                        "↓ ${formatBytes(entry.cellularRx)}  ↑ ${formatBytes(entry.cellularTx)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
