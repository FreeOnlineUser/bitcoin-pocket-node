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
import com.pocketnode.oracle.BlockOutputs
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
    isNodeSynced: Boolean,
    blockHeight: Long = -1
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }

    // Cache oracle results in SharedPreferences
    val prefs = remember { context.getSharedPreferences("oracle_cache", android.content.Context.MODE_PRIVATE) }

    fun saveResult(r: OracleResult) {
        val now = System.currentTimeMillis()
        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        prefs.edit()
            .putInt("price", r.price)
            .putString("date", r.date)
            .putString("updatedAt", timeStr)
            .putInt("blockStart", r.blockRange.first)
            .putInt("blockEnd", r.blockRange.last)
            .putInt("outputCount", r.outputCount)
            .putFloat("deviation", r.deviation.toFloat())
            .putLong("cachedAt", now)
            .apply()
    }

    fun loadCachedResult(): OracleResult? {
        val price = prefs.getInt("price", -1)
        if (price < 0) return null
        return OracleResult(
            price = price,
            date = prefs.getString("date", "") ?: "",
            blockRange = prefs.getInt("blockStart", 0)..prefs.getInt("blockEnd", 0),
            outputCount = prefs.getInt("outputCount", 0),
            deviation = prefs.getFloat("deviation", 0f).toDouble()
        )
    }

    var result by remember { mutableStateOf(loadCachedResult()) }
    var showRefreshConfirm by remember { mutableStateOf(false) }
    var lastUpdatedHeight by remember { mutableStateOf(prefs.getInt("blockEnd", 0).toLong()) }

    // Persistent oracle instance — survives recomposition, holds cached block data
    val oracle = remember { mutableStateOf<UTXOracle?>(null) }

    // Load cached block data from disk on first composition
    fun saveCachedBlocks(blocks: List<BlockOutputs>) {
        try {
            val json = org.json.JSONArray()
            for (b in blocks) {
                val obj = org.json.JSONObject()
                obj.put("h", b.height)
                obj.put("hash", b.hash)
                obj.put("t", b.time)
                val outs = org.json.JSONArray()
                for (o in b.outputs) outs.put(o)
                obj.put("o", outs)
                // Skip txids — too large to cache, rebuilt on next full run
                json.put(obj)
            }
            prefs.edit().putString("blockData", json.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("OracleCard", "Failed to save block data", e)
        }
    }

    fun loadCachedBlocks(): List<BlockOutputs> {
        try {
            val str = prefs.getString("blockData", null) ?: return emptyList()
            val json = org.json.JSONArray(str)
            val blocks = mutableListOf<BlockOutputs>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val outs = obj.getJSONArray("o")
                val outputList = mutableListOf<Double>()
                for (j in 0 until outs.length()) outputList.add(outs.getDouble(j))
                blocks.add(BlockOutputs(
                    height = obj.getInt("h"),
                    hash = obj.getString("hash"),
                    time = obj.getLong("t"),
                    txids = emptySet(), // not cached — rebuilt on full run
                    outputs = outputList
                ))
            }
            return blocks
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // Initial full run when node is synced and cache is stale
    LaunchedEffect(isNodeSynced) {
        val cacheAgeMs = System.currentTimeMillis() - prefs.getLong("cachedAt", 0)
        val isStale = result == null || cacheAgeMs > 12 * 60 * 60 * 1000 // 12 hours
        if (isNodeSynced && isStale && !isRunning) {
            val creds = ConfigGenerator.readCredentials(context) ?: return@LaunchedEffect
            isRunning = true
            error = null
            try {
                val rpc = BitcoinRpcClient(creds.first, creds.second)
                val oracleInstance = UTXOracle(rpc)
                oracle.value = oracleInstance

                val progressJob = launch {
                    oracleInstance.progress.collect { progressText = it }
                }

                val r = oracleInstance.getPriceRecentBlocks()
                result = r
                saveResult(r)
                saveCachedBlocks(oracleInstance.cachedBlocks)
                lastUpdatedHeight = r.blockRange.last.toLong()
                progressJob.cancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("OracleCard", "Oracle calculation cancelled (recomposition)")
                isRunning = false
                throw e
            } catch (e: Exception) {
                android.util.Log.e("OracleCard", "UTXOracle failed", e)
                error = e.message ?: "Unknown error"
            } finally {
                isRunning = false
                progressText = ""
            }
        } else if (isNodeSynced && !isStale && oracle.value == null) {
            // Restore oracle instance from cache for incremental updates
            val creds = ConfigGenerator.readCredentials(context) ?: return@LaunchedEffect
            val rpc = BitcoinRpcClient(creds.first, creds.second)
            val oracleInstance = UTXOracle(rpc)
            val cached = loadCachedBlocks()
            if (cached.isNotEmpty()) {
                oracleInstance.setCachedBlocks(cached)
                oracle.value = oracleInstance
            }
        }
    }

    // Incremental update when block height changes
    LaunchedEffect(blockHeight) {
        if (blockHeight <= 0 || !isNodeSynced || isRunning) return@LaunchedEffect
        if (blockHeight <= lastUpdatedHeight) return@LaunchedEffect

        val oracleInstance = oracle.value ?: return@LaunchedEffect
        if (oracleInstance.cachedBlocks.isEmpty()) return@LaunchedEffect

        isRunning = true
        error = null
        try {
            val progressJob = launch {
                oracleInstance.progress.collect { progressText = it }
            }

            val r = oracleInstance.incrementalUpdate(blockHeight.toInt())
            if (r != null) {
                result = r
                saveResult(r)
                saveCachedBlocks(oracleInstance.cachedBlocks)
                lastUpdatedHeight = blockHeight
            }
            progressJob.cancel()
        } catch (e: kotlinx.coroutines.CancellationException) {
            isRunning = false
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OracleCard", "Incremental update failed", e)
            // Don't show error for incremental — cached result still valid
        } finally {
            isRunning = false
            progressText = ""
        }
    }

    // Don't show card until node is synced or we have a result
    if (!isNodeSynced && result == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isRunning) Modifier.defaultMinSize(minHeight = 120.dp) else Modifier)
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
                                val displayText = if (result!!.date == "recent-blocks") {
                                    "Block ${"%,d".format(result!!.blockRange.last)}"
                                } else {
                                    result!!.date
                                }
                                Text(
                                    displayText,
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
                        val updatedAt = prefs.getString("updatedAt", null)
                        if (r.date == "recent-blocks" && updatedAt != null) {
                            DetailRow("Updated", updatedAt)
                        } else {
                            DetailRow("Date", r.date)
                        }
                        DetailRow("Blocks", "${r.blockRange.first}–${r.blockRange.last} (${r.blockRange.last - r.blockRange.first + 1} blocks)")
                        DetailRow("Transactions", "${"%,d".format(r.outputCount)} filtered outputs")
                        DetailRow("Deviation", "${"%.1f".format(r.deviation * 100)}%")

                        Spacer(Modifier.height(12.dp))

                        // Refresh button — confirmation to prevent accidental ~10 min recalc
                        OutlinedButton(
                            onClick = { showRefreshConfirm = true },
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
                        "By @SteveSimple · utxo.live",
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

    // Refresh confirmation dialog
    if (showRefreshConfirm) {
        AlertDialog(
            onDismissRequest = { showRefreshConfirm = false },
            title = { Text("Refresh Price?") },
            text = { Text("This will re-scan 144 blocks and takes about 10 minutes. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showRefreshConfirm = false
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
                            val r = oracle.getPriceRecentBlocks()
                            result = r
                            saveResult(r)
                            progressJob.cancel()
                        } catch (e: Exception) {
                            android.util.Log.e("OracleCard", "UTXOracle refresh failed", e)
                            error = e.message
                        } finally {
                            isRunning = false
                            progressText = ""
                        }
                    }
                }) {
                    Text("Refresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefreshConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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
