package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Mempool viewer — shows current mempool stats from the local node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MempoolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val creds = remember { ConfigGenerator.readCredentials(context) }

    var txCount by remember { mutableStateOf(0L) }
    var memUsage by remember { mutableStateOf(0L) }
    var minFee by remember { mutableStateOf(0.0) }
    var maxFee by remember { mutableStateOf(0.0) }
    var totalFee by remember { mutableStateOf(0.0) }
    var loaded by remember { mutableStateOf(false) }

    // Poll mempool info
    LaunchedEffect(Unit) {
        if (creds == null) return@LaunchedEffect
        val rpc = BitcoinRpcClient(creds.first, creds.second)
        while (isActive) {
            try {
                val info = rpc.call("getmempoolinfo")
                if (info != null && !info.has("_rpc_error")) {
                    txCount = info.optLong("size", 0)
                    memUsage = info.optLong("bytes", 0)
                    minFee = info.optDouble("mempoolminfee", 0.0)
                    maxFee = info.optDouble("maxmempool", 0.0)
                    totalFee = info.optDouble("total_fee", 0.0)
                    loaded = true
                }
            } catch (_: Exception) {}
            delay(10_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mempool") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!loaded) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFF7931A))
                }
            } else {
                // Transaction count
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Unconfirmed Transactions",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%,d".format(txCount),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFF7931A)
                        )
                    }
                }

                // Memory usage
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Mempool Size",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        val mb = memUsage / (1024.0 * 1024.0)
                        Text(
                            "%.1f MB".format(mb),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        // Show as progress toward 300 MB default max
                        Spacer(Modifier.height(8.dp))
                        val progress = (mb / 300.0).coerceIn(0.0, 1.0).toFloat()
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = when {
                                progress > 0.8f -> Color(0xFFF44336)
                                progress > 0.5f -> Color(0xFFFFC107)
                                else -> Color(0xFFF7931A)
                            },
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        Text(
                            "of 300 MB max",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                // Fee stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Min Fee Rate",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Spacer(Modifier.height(4.dp))
                            val satsPerVb = minFee * 100_000 // BTC/kvB to sat/vB
                            Text(
                                "%.1f sat/vB".format(satsPerVb),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Total Fees",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "%.4f BTC".format(totalFee),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Live data from your local node. Updates every 10 seconds. " +
                            "All data comes directly from your bitcoind instance — no external servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}
