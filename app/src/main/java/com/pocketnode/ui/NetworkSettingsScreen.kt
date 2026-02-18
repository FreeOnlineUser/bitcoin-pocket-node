package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.network.DataUsageEntry
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.ui.components.formatBytes

/**
 * Network sync settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    allowCellular: Boolean,
    cellularBudgetMb: Long,
    wifiBudgetMb: Long = 0,
    networkMonitor: NetworkMonitor? = null,
    onAllowCellularChanged: (Boolean) -> Unit,
    onCellularBudgetChanged: (Long) -> Unit,
    onWifiBudgetChanged: (Long) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var showCellularInfo by remember { mutableStateOf(prefs.getBoolean("info_cellular", true)) }
    var showCellularBudgetInfo by remember { mutableStateOf(prefs.getBoolean("info_cell_budget", true)) }
    var showWifiBudgetInfo by remember { mutableStateOf(prefs.getBoolean("info_wifi_budget", true)) }
    // Read persisted values directly from SharedPreferences as fallback
    val syncPrefs = remember { context.getSharedPreferences("sync_settings", android.content.Context.MODE_PRIVATE) }
    val persistedCellular = remember { syncPrefs.getLong("cellular_budget_mb", 0) }
    val persistedWifi = remember { syncPrefs.getLong("wifi_budget_mb", 0) }
    val effectiveCellular = if (cellularBudgetMb > 0) cellularBudgetMb else persistedCellular
    val effectiveWifi = if (wifiBudgetMb > 0) wifiBudgetMb else persistedWifi

    var budgetText by remember { mutableStateOf(if (effectiveCellular > 0) effectiveCellular.toString() else "") }
    var wifiBudgetText by remember { mutableStateOf(if (effectiveWifi > 0) effectiveWifi.toString() else "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Allow cellular sync
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Allow sync on cellular",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Sync blockchain over mobile data",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = {
                            showCellularInfo = !showCellularInfo
                            prefs.edit().putBoolean("info_cellular", showCellularInfo).apply()
                        }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "Info",
                                tint = if (showCellularInfo) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                        Switch(
                            checked = allowCellular,
                            onCheckedChange = onAllowCellularChanged
                        )
                    }
                    if (showCellularInfo) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bitcoin initial block download can use significant data (300+ GB). " +
                                "Once synced, ongoing usage is much lower (~150 MB/day). " +
                                "Enable this only if you have an unlimited plan or are nearly synced.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Monthly cellular budget
            BudgetCard(
                title = "Monthly cellular budget",
                budgetMb = cellularBudgetMb,
                budgetText = budgetText,
                showInfo = showCellularBudgetInfo,
                onShowInfoToggle = {
                    showCellularBudgetInfo = !showCellularBudgetInfo
                    prefs.edit().putBoolean("info_cell_budget", showCellularBudgetInfo).apply()
                },
                onBudgetTextChange = { newValue ->
                    budgetText = newValue.filter { it.isDigit() }
                    val value = budgetText.toLongOrNull() ?: 0
                    onCellularBudgetChanged(value)
                    syncPrefs.edit().putLong("cellular_budget_mb", value).apply()
                },
                onPresetSelected = { preset ->
                    budgetText = preset.toString()
                    onCellularBudgetChanged(preset)
                    syncPrefs.edit().putLong("cellular_budget_mb", preset).apply()
                },
                presets = listOf(500L, 1024L, 2048L, 5120L, 0L),
                infoText = "When cellular usage exceeds this limit, sync will automatically pause on mobile data. " +
                    "Set to 0 or leave empty for no limit. " +
                    "This only counts data while the app is running."
            )

            // Monthly WiFi budget
            BudgetCard(
                title = "Monthly WiFi budget",
                budgetMb = wifiBudgetMb,
                budgetText = wifiBudgetText,
                showInfo = showWifiBudgetInfo,
                onShowInfoToggle = {
                    showWifiBudgetInfo = !showWifiBudgetInfo
                    prefs.edit().putBoolean("info_wifi_budget", showWifiBudgetInfo).apply()
                },
                onBudgetTextChange = { newValue ->
                    wifiBudgetText = newValue.filter { it.isDigit() }
                    val value = wifiBudgetText.toLongOrNull() ?: 0
                    onWifiBudgetChanged(value)
                    syncPrefs.edit().putLong("wifi_budget_mb", value).apply()
                },
                onPresetSelected = { preset ->
                    wifiBudgetText = preset.toString()
                    onWifiBudgetChanged(preset)
                    syncPrefs.edit().putLong("wifi_budget_mb", preset).apply()
                },
                presets = listOf(5120L, 10240L, 20480L, 51200L, 0L),
                infoText = "Useful if you're on a capped home plan or tethering. " +
                    "When WiFi usage exceeds this limit, sync will pause until next month. " +
                    "Set to 0 or leave empty for unlimited."
            )

            // Data Usage section — reads from SharedPreferences directly so it works
            // even when NetworkMonitor isn't running (e.g. node failed to start)
            val usagePrefs = remember { context.getSharedPreferences("network_usage", android.content.Context.MODE_PRIVATE) }
            val recentUsage = remember {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val cal = java.util.Calendar.getInstance()
                (0 until 7).map { _ ->
                    val date = fmt.format(cal.time)
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    DataUsageEntry(
                        date = date,
                        wifiRx = usagePrefs.getLong("${date}_wifi_rx", 0),
                        wifiTx = usagePrefs.getLong("${date}_wifi_tx", 0),
                        cellularRx = usagePrefs.getLong("${date}_cell_rx", 0),
                        cellularTx = usagePrefs.getLong("${date}_cell_tx", 0)
                    )
                }
            }
            val monthCellular = remember {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val monthPrefix = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
                val cal = java.util.Calendar.getInstance()
                var total = 0L
                val dayOfMonth = cal.get(java.util.Calendar.DAY_OF_MONTH)
                for (i in 0 until dayOfMonth) {
                    val date = fmt.format(cal.time)
                    if (date.startsWith(monthPrefix)) {
                        total += usagePrefs.getLong("${date}_cell_rx", 0) + usagePrefs.getLong("${date}_cell_tx", 0)
                    }
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                total
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("Data Usage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            // Monthly cellular summary
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                            progress = { progress },
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
            if (recentUsage.isNotEmpty()) {
                Text("Last 7 Days", style = MaterialTheme.typography.titleMedium)
                recentUsage.forEach { entry ->
                    DayUsageRow(entry)
                }
            }
        }
    }
}

@Composable
private fun DayUsageRow(entry: DataUsageEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(entry.date, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("WiFi", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                    Text(
                        "↓ ${formatBytes(entry.wifiRx)}  ↑ ${formatBytes(entry.wifiTx)}",
                        style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Cellular", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFC107))
                    Text(
                        "↓ ${formatBytes(entry.cellularRx)}  ↑ ${formatBytes(entry.cellularTx)}",
                        style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetCard(
    title: String,
    budgetMb: Long,
    budgetText: String,
    showInfo: Boolean = true,
    onShowInfoToggle: () -> Unit = {},
    onBudgetTextChange: (String) -> Unit,
    onPresetSelected: (Long) -> Unit,
    presets: List<Long>,
    infoText: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onShowInfoToggle) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = if (showInfo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
            if (showInfo) {
                Text(
                    infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = budgetText,
                onValueChange = onBudgetTextChange,
                label = { Text("Budget (MB)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { preset ->
                    val label = when {
                        preset == 0L -> "∞"
                        preset >= 1024 -> "${preset / 1024} GB"
                        else -> "$preset MB"
                    }
                    val isSelected = when {
                        preset == 0L -> budgetText == "0"
                        else -> budgetText == preset.toString()
                    }
                    TextButton(onClick = { onPresetSelected(preset) }) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
