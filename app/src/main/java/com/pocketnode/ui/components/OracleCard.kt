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
import com.pocketnode.oracle.MempoolPrice
import com.pocketnode.oracle.OracleUpdater

/**
 * Collapsible UTXOracle price card for the dashboard.
 * Collapsed: shows price and date.
 * Expanded: shows details, block range, output count, and attribution.
 */
@Composable
fun OracleCard(
    isNodeSynced: Boolean,
    blockHeight: Long = -1,
    onPriceUpdate: ((Int) -> Unit)? = null,
    onExpanded: ((Boolean) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var expanded by remember { mutableStateOf(false) }
    var showRefreshConfirm by remember { mutableStateOf(false) }

    // OracleUpdater does the work in the background (started by BitcoindService);
    // this card only shows its state.
    val state by OracleUpdater.state.collectAsState()
    val result = state.result
    val recent = state.recent
    val isRunning = state.running
    val progressText = state.progress
    val error = state.error

    val context = LocalContext.current
    LaunchedEffect(Unit) { OracleUpdater.loadWindow(context) }
    val window by OracleUpdater.window.collectAsState()
    val chartShown by OracleUpdater.chartShown.collectAsState()
    val history by OracleUpdater.history.collectAsState()
    val historyStatus by OracleUpdater.historyStatus.collectAsState()
    val live = state.live
    // Fall back to the 24h figure until the selected one exists.
    val effective = when {
        window == OracleUpdater.PriceWindow.LIVE && live != null -> OracleUpdater.PriceWindow.LIVE
        window == OracleUpdater.PriceWindow.HOUR && recent != null -> OracleUpdater.PriceWindow.HOUR
        else -> OracleUpdater.PriceWindow.DAY
    }
    val shown = when (effective) {
        OracleUpdater.PriceWindow.LIVE -> live
        OracleUpdater.PriceWindow.HOUR -> recent
        OracleUpdater.PriceWindow.DAY -> result
    }
    // The converter never uses the mempool figure (unconfirmed, cheap to skew):
    // on Live it gets the last-hour block figure instead.
    val converterPrice = if (effective == OracleUpdater.PriceWindow.LIVE) (recent ?: result) else shown

    LaunchedEffect(converterPrice?.price) {
        converterPrice?.let { onPriceUpdate?.invoke(it.price) }
    }

    // Don't show card until node is synced or we have a result
    if (!isNodeSynced && result == null) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isRunning) Modifier.defaultMinSize(minHeight = 120.dp) else Modifier)
            .clickable { expanded = !expanded; onExpanded?.invoke(expanded) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Collapsed view: always visible ──
            // Top row: emoji + label
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔮", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "On-chain price",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                when {
                    isRunning -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OracleUpdater.PriceWindow.entries.forEach { w ->
                    FilterChip(
                        selected = window == w,
                        onClick = { OracleUpdater.setWindow(context, w) },
                        label = { Text(w.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // Price row
            when {
                isRunning -> {
                    Text(
                        progressText.ifEmpty { "Calculating…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                error != null -> {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                shown != null && result != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            "$${"%,d".format(shown.price)}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFF9800) // Bitcoin orange
                        )
                        // Flag a price whose window trails the chain by more
                        // than a day: better an honest "stale" than a wrong
                        // number that looks current.
                        val resultEnd = result.blockRange.last
                        val stale = blockHeight > 0 && blockHeight - resultEnd > 144
                        val displayText = if (result.date == "recent-blocks") {
                            "to block ${"%,d".format(resultEnd)}" + if (stale) " (stale, updating…)" else ""
                        } else {
                            result.date
                        }
                        Text(
                            displayText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stale) Color(0xFFFFB74D)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    // The figures not shown, each against the 24h average.
                    val others = listOfNotNull(
                        result.takeIf { effective != OracleUpdater.PriceWindow.DAY }?.let { "24h $${"%,d".format(it.price)}" },
                        recent?.takeIf { effective != OracleUpdater.PriceWindow.HOUR }?.let {
                            "hour $${"%,d".format(it.price)} (${"%+.1f".format((it.price - result.price) * 100.0 / result.price)}%)"
                        },
                        live?.takeIf { effective != OracleUpdater.PriceWindow.LIVE }?.let {
                            "live $${"%,d".format(it.price)} (${"%+.1f".format((it.price - result.price) * 100.0 / result.price)}%)"
                        }
                    )
                    if (others.isNotEmpty()) {
                        Text(
                            others.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (effective == OracleUpdater.PriceWindow.LIVE) {
                        Text(
                            "Unconfirmed transactions, last ${MempoolPrice.WINDOW_MS / 60_000} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB74D)
                        )
                    } else if (window == OracleUpdater.PriceWindow.LIVE && state.liveNote != null) {
                        Text(
                            state.liveNote!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB74D)
                        )
                    }
                    // Optional week chart, off unless turned on here.
                    Text(
                        if (chartShown) "Hide 7-day chart" else "Show 7-day chart",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { OracleUpdater.setChartShown(context, !chartShown) }
                    )
                    if (chartShown) {
                        if (history.size >= 2) {
                            // On Live, the mempool estimate shows as a separate end marker:
                            // it isn't a mined price, so it never joins the line.
                            PriceChart(
                                history,
                                Modifier.padding(top = 6.dp),
                                live = live?.price?.takeIf { effective == OracleUpdater.PriceWindow.LIVE }
                            )
                        }
                        val note = historyStatus ?: if (history.size < 2) "The week fills in from your node's blocks shortly." else null
                        note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
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
                        val r = result
                        DetailRow("Price", "$${"%,d".format(r.price)} USD")
                        if (r.date == "recent-blocks" && state.updatedAt > 0) {
                            DetailRow("Updated", java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(state.updatedAt)))
                        } else {
                            DetailRow("Date", r.date)
                        }
                        DetailRow("Blocks", "${r.blockRange.first}–${r.blockRange.last} (${r.blockRange.last - r.blockRange.first + 1} blocks)")
                        DetailRow("Transactions", "${"%,d".format(r.outputCount)} filtered outputs")
                        DetailRow("Deviation", "${"%.1f".format(r.deviation * 100)}%")
                        state.live?.let {
                            DetailRow("Live estimate", "$${"%,d".format(it.price)} (${"%,d".format(state.liveOutputs)} outputs)")
                        }
                        if (recent != null) {
                            DetailRow("Last hour estimate", "$${"%,d".format(recent.price)} " +
                                "(${recent.blockRange.last - recent.blockRange.first + 1} blocks)")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The main price averages the last 144 blocks (about a day), so it " +
                            "trails moves. The last hour estimate runs the same method on the " +
                            "newest ${OracleUpdater.RECENT_BLOCKS} blocks, so it follows the market " +
                            "closely but leans on fewer transactions. Both come from mined " +
                            "transactions and update with each block. Live uses unconfirmed " +
                            "transactions from the last ${MempoolPrice.WINDOW_MS / 60_000} minutes " +
                            "in your node's mempool (Max mode only): the freshest view, but those " +
                            "can be replaced or never confirm, so the converter uses the last hour " +
                            "figure even when Live is shown. None of these is an exchange quote.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )

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
                            error,
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
                        "Price derived entirely from your node's transaction data. " +
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
                            uriHandler.openUri("https://utxo.live/oracle/")
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "▶ UTXOracle Live Stream",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://www.youtube.com/@UTXOracle/live")
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
                    OracleUpdater.refresh(scope)
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
