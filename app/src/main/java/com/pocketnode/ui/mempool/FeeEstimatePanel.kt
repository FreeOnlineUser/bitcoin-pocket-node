package com.pocketnode.ui.mempool

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
fun FeeEstimatePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val creds = remember { ConfigGenerator.readCredentials(context) }
    var syncingFees by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            if (syncingFees) {
                com.pocketnode.power.PowerModeManager.getInstance(context).releaseNetworkHold()
            }
        }
    }

    var nextBlockFee by remember { mutableStateOf<Double?>(null) }
    var thirtyMinFee by remember { mutableStateOf<Double?>(null) }
    var oneHourFee by remember { mutableStateOf<Double?>(null) }

    // Poll estimatesmartfee every 30s
    LaunchedEffect(Unit) {
        if (creds == null) return@LaunchedEffect
        val rpc = BitcoinRpcClient(creds.first, creds.second)
        while (isActive) {
            try {
                // 1 block = next block, 3 blocks ~ 30 min, 6 blocks ~ 1 hour
                nextBlockFee = estimateFee(rpc, 1)
                thirtyMinFee = estimateFee(rpc, 3)
                oneHourFee = estimateFee(rpc, 6)
                // Release network hold once we have fee data
                if (syncingFees && (nextBlockFee != null || thirtyMinFee != null || oneHourFee != null)) {
                    syncingFees = false
                    com.pocketnode.power.PowerModeManager.getInstance(context).releaseNetworkHold()
                }
            } catch (_: Exception) {}
            delay(30_000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Recommended Fees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            val noData = nextBlockFee == null && thirtyMinFee == null && oneHourFee == null
            val powerMode by com.pocketnode.power.PowerModeManager.modeFlow.collectAsState()
            val notMax = powerMode != com.pocketnode.power.PowerModeManager.Mode.MAX
            if (noData && notMax) {
                Text(
                    "Fee estimation needs Max mode (continuous mempool data)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (notMax && !syncingFees) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        syncingFees = true
                        val pmm = com.pocketnode.power.PowerModeManager.getInstance(context)
                        if (creds != null) {
                            pmm.setRpc(BitcoinRpcClient(creds.first, creds.second))
                        }
                        pmm.holdNetwork()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📡 Sync Fees")
                }
            } else if (syncingFees) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Syncing fee data (network held open)...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }
            if (!noData) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FeeEstimateItem("Next Block", formatFee(nextBlockFee), FeePriority.High, Modifier.weight(1f))
                    FeeEstimateItem("30 min", formatFee(thirtyMinFee), FeePriority.Medium, Modifier.weight(1f))
                    FeeEstimateItem("1 hour", formatFee(oneHourFee), FeePriority.Low, Modifier.weight(1f))
                }
            }
        }
    }
}

private suspend fun estimateFee(rpc: BitcoinRpcClient, confTarget: Int): Double? {
    val params = JSONArray().apply { put(confTarget) }
    val result = rpc.call("estimatesmartfee", params) ?: return null
    if (result.has("_rpc_error")) return null
    val btcPerKvb = result.optDouble("feerate", -1.0)
    if (btcPerKvb <= 0) return null
    // Convert BTC/kvB to sat/vB: BTC/kvB * 100,000,000 / 1000
    return btcPerKvb * 100_000
}

private fun formatFee(fee: Double?): String {
    if (fee == null) return "—"
    return if (fee < 10) "%.1f sat/vB".format(fee) else "%.0f sat/vB".format(fee)
}

@Composable
private fun FeeEstimateItem(label: String, feeRate: String, priority: FeePriority, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(feeRate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = priority.color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

enum class FeePriority(val color: Color) {
    High(Color(0xFFFF0000)),
    Medium(Color(0xFFFF8000)),
    Low(Color(0xFF00FF00))
}
