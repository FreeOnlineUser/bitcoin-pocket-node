package com.pocketnode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.oracle.OracleResult
import com.pocketnode.oracle.UTXOracle
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.launch

/**
 * Collapsible UTXOracle price card for the dashboard.
 * Collapsed: shows price and date.
 * Expanded: shows details, block range, output count, and attribution.
 */
@Composable
fun OracleCard(
    isNodeSynced: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OracleResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    // Auto-run once when node is synced and we haven't run yet
    LaunchedEffect(isNodeSynced) {
        if (isNodeSynced && result == null && !isRunning) {
            val creds = ConfigGenerator.readCredentials(context) ?: return@LaunchedEffect
            isRunning = true
            error = null
            try {
                val rpc = BitcoinRpcClient(creds.first, creds.second)
                val oracle = UTXOracle(rpc)

                // Collect progress in background
                val progressJob = launch {
                    oracle.progress.collect { progressText = it }
                }

                result = oracle.getPrice()
                progressJob.cancel()
            } catch (e: Exception) {
                android.util.Log.e("OracleCard", "UTXOracle failed", e)
                error = e.message ?: "Unknown error"
            } finally {
                isRunning = false
                progressText = ""
            }
        }
    }

    // Don't show card until node is synced or we have a result
    if (!isNodeSynced && result == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Collapsed view: always visible ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔮", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "UTXOracle",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        when {
                            isRunning -> {
                                Text(
                                    progressText.ifEmpty { "Calculating…" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            error != null -> {
                                Text(
                                    "Error",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            result != null -> {
                                Text(
                                    result!!.date,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                // Price or progress indicator
                when {
                    isRunning -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    result != null -> {
                        Text(
                            "$${"%,d".format(result!!.price)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFF9800) // Bitcoin orange
                        )
                    }
                }
            }

            // ── Progress bar while running ──
            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ── Expanded view ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(12.dp))

                    if (result != null) {
                        val r = result!!
                        DetailRow("Price", "$${"%,d".format(r.price)} USD")
                        DetailRow("Date", r.date)
                        DetailRow("Blocks", "${r.blockRange.first}–${r.blockRange.last} (${r.blockRange.last - r.blockRange.first + 1} blocks)")
                        DetailRow("Transactions", "${"%,d".format(r.outputCount)} filtered outputs")
                        DetailRow("Deviation", "${"%.1f".format(r.deviation * 100)}%")

                        Spacer(Modifier.height(12.dp))

                        // Refresh button
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val creds = ConfigGenerator.readCredentials(context) ?: return@launch
                                    isRunning = true
                                    error = null
                                    try {
                                        val rpc = BitcoinRpcClient(creds.first, creds.second)
                                        val oracle = UTXOracle(rpc)
                                        val progressJob = launch {
                                            oracle.progress.collect { progressText = it }
                                        }
                                        result = oracle.getPriceRecentBlocks()
                                        progressJob.cancel()
                                    } catch (e: Exception) {
                                        android.util.Log.e("OracleCard", "UTXOracle refresh failed", e)
                                        error = e.message
                                    } finally {
                                        isRunning = false
                                        progressText = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRunning
                        ) {
                            Text("↻ Refresh (last 144 blocks)")
                        }
                    }

                    if (error != null) {
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // ── Attribution ──
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "About UTXOracle",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Price derived entirely from your node's transaction data — " +
                        "no external APIs, no third parties. The algorithm detects " +
                        "round fiat spending patterns in on-chain outputs to determine " +
                        "the Bitcoin/USD exchange rate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "UTXOracle · utxo.live",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://utxo.live")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
