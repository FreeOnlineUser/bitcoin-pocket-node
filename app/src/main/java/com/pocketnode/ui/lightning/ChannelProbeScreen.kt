package com.pocketnode.ui.lightning

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.ChannelProbe
import com.pocketnode.lightning.LightningService

/**
 * UI for the channel probe scanner.
 * Discovers which .onion nodes accept small channels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val probe = remember { ChannelProbe(context) }
    val probeState by probe.state.collectAsState()
    var confirmed by remember { mutableStateOf(false) }
    var strategy by remember { mutableStateOf(ChannelProbe.Strategy.SMALL_FRIENDLY) }
    var probeAmountText by remember { mutableStateOf("100000") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channel Probe") },
                navigationIcon = {
                    IconButton(onClick = {
                        probe.stop()
                        onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!probeState.running && probeState.results.isEmpty()) {
                // Pre-start info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Discover small-channel-friendly nodes", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("This probes .onion Lightning nodes to find which ones accept channels of 100,000 sats.",
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        Text("How it works:", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("1. Fetches top .onion nodes from the network", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text("2. Connects to each, attempts a 100k sat channel open", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text("3. Records the rejection reason or acceptance", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text("4. Disconnects and moves to the next (45s between attempts)", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text("5. Results saved and shared via phone-to-phone", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4A1010))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚠️ Warning", fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                        Text("This sends real channel open requests to nodes. Accepted channels are immediately closed. Some nodes may temporarily ban your pubkey. Uses Tor for all connections.",
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        Text("Takes ~2-3 hours for 200 nodes. Keep app open and on WiFi.",
                            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (!confirmed) {
                    OutlinedButton(
                        onClick = { confirmed = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("I understand, prepare scan")
                    }
                } else {
                    // Strategy selector
                    Text("Strategy", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = strategy == ChannelProbe.Strategy.SMALL_FRIENDLY,
                            onClick = { strategy = ChannelProbe.Strategy.SMALL_FRIENDLY },
                            label = { Text("Small-friendly first", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = strategy == ChannelProbe.Strategy.TOP_NODES,
                            onClick = { strategy = ChannelProbe.Strategy.TOP_NODES },
                            label = { Text("Top nodes first", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Text(
                        when (strategy) {
                            ChannelProbe.Strategy.SMALL_FRIENDLY -> "Sorted by smallest average channel size. Most likely to accept."
                            ChannelProbe.Strategy.TOP_NODES -> "Biggest, most connected nodes. Best for routing if they accept."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    // Probe amount
                    OutlinedTextField(
                        value = probeAmountText,
                        onValueChange = { probeAmountText = it.filter { c -> c.isDigit() } },
                        label = { Text("Channel size to test (sats)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            val ls = LightningService.getInstance(context)
                            probe.probeAmountSats = probeAmountText.toLongOrNull() ?: ChannelProbe.DEFAULT_PROBE_AMOUNT_SATS
                            probe.start(ls.channels, com.pocketnode.lightning.NodeDirectory, strategy)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("Start Probing", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Running state
            if (probeState.running) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔍 Probing...", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Current: ${probeState.currentNode}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                        if (probeState.totalNodes > 0) {
                            LinearProgressIndicator(
                                progress = { probeState.probed.toFloat() / probeState.totalNodes },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFFF9800)
                            )
                        }
                        Text(
                            "${probeState.probed}/${probeState.totalNodes} probed | ✅ ${probeState.accepted} accept | ❌ ${probeState.rejected} reject | ⚫ ${probeState.unreachable} offline",
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedButton(
                    onClick = { probe.stop() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336))
                ) {
                    Text("Stop Probe")
                }
            }

            // Results
            if (probeState.results.isNotEmpty()) {
                if (!probeState.running) {
                    Text("Scan complete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                // Accepted nodes first
                val accepted = probeState.results.filter { it.outcome == ChannelProbe.Outcome.ACCEPTED }
                if (accepted.isNotEmpty()) {
                    Text("✅ Accepts 100k sats (${accepted.size})", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    accepted.forEach { result ->
                        ProbeResultRow(result)
                    }
                }

                // Rejected with known minimum
                val rejectedWithMin = probeState.results.filter { it.outcome == ChannelProbe.Outcome.REJECTED_MIN_SIZE }
                if (rejectedWithMin.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("❌ Too small (${rejectedWithMin.size})", color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
                    rejectedWithMin.sortedBy { it.minSats }.forEach { result ->
                        ProbeResultRow(result)
                    }
                }

                // Other rejections
                val rejectedOther = probeState.results.filter { it.outcome == ChannelProbe.Outcome.REJECTED_OTHER || it.outcome == ChannelProbe.Outcome.FORCE_CLOSED }
                if (rejectedOther.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠️ Rejected (${rejectedOther.size})", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    rejectedOther.forEach { result ->
                        ProbeResultRow(result)
                    }
                }

                // Unreachable
                val unreachable = probeState.results.filter { it.outcome == ChannelProbe.Outcome.UNREACHABLE }
                if (unreachable.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("⚫ Unreachable (${unreachable.size})", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun ProbeResultRow(result: ChannelProbe.ProbeResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.alias.ifEmpty { result.nodeId.take(16) + "..." },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            if (result.minSats != null) {
                Text(
                    "Min: ${"%,d".format(result.minSats)} sats",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF9800)
                )
            } else if (result.message.isNotEmpty() && result.outcome != ChannelProbe.Outcome.ACCEPTED) {
                Text(
                    result.message.take(60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            result.nodeId.take(8),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
