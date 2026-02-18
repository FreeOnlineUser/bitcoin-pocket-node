package com.pocketnode.ui.mempool

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketnode.mempool.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MempoolScreen(
    onNavigateToTransactionSearch: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MempoolViewModel = viewModel()
) {
    val mempoolState by viewModel.mempoolState.collectAsStateWithLifecycle()
    val feeRateHistogram by viewModel.feeRateHistogram.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val newBlockDetected by viewModel.newBlockDetected.collectAsStateWithLifecycle()
    val confirmedTransaction by viewModel.confirmedTransaction.collectAsStateWithLifecycle()
    val projectedBlocks by viewModel.projectedBlocks.collectAsStateWithLifecycle()
    val latestBlock by viewModel.latestBlock.collectAsStateWithLifecycle()
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(Unit) { viewModel.startMempoolUpdates() }
    DisposableEffect(Unit) { onDispose { viewModel.stopMempoolUpdates() } }

    LaunchedEffect(newBlockDetected) {
        newBlockDetected?.let {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.clearNewBlockDetected()
        }
    }
    LaunchedEffect(confirmedTransaction) {
        confirmedTransaction?.let {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.clearConfirmedTransaction()
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Auto-refreshes every 10 seconds

            item { MempoolStatsCard(mempoolState = mempoolState) }
            item { FeeEstimatePanel() }
            item {
                ProjectedBlocksVisualization(
                    projectedBlocks = projectedBlocks,
                    latestBlock = latestBlock,
                    onBlockClick = { viewModel.showBlockDetails(it) }
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Transaction Search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Search for a specific transaction by TXID", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onNavigateToTransactionSearch, modifier = Modifier.fillMaxWidth()) {
                            Text("Search Transaction")
                        }
                    }
                }
            }

            item { FeeRateHistogramCard(feeRateHistogram = feeRateHistogram) }
        }
    }
}

@Composable
private fun MempoolStatsCard(mempoolState: MempoolState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Mempool Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFF9500))
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Transactions", mempoolState.transactionCount.toString(), Modifier.weight(1f))
                StatItem("Total vMB", String.format("%.2f", mempoolState.totalVbytes / 1_000_000.0), Modifier.weight(1f))
                StatItem("vB/s Inflow", String.format("%.1f", mempoolState.vbytesPerSecond), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun getFeeRateColor(feeRate: Double): Color {
    return when {
        feeRate <= 2 -> Color(0xFF004D40)
        feeRate <= 4 -> Color(0xFF006064)
        feeRate <= 10 -> Color(0xFF2196F3)
        feeRate <= 20 -> Color(0xFF4CAF50)
        feeRate <= 50 -> Color(0xFFFFEB3B)
        feeRate <= 100 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
}

@Composable
private fun ProjectedBlocksVisualization(
    projectedBlocks: List<ProjectedBlockInfo>,
    latestBlock: LatestBlockInfo?,
    onBlockClick: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Projected Blocks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))

            if (projectedBlocks.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    projectedBlocks.forEach { block ->
                        Block3D(blockInfo = block, onClick = { onBlockClick(block.index) }, modifier = Modifier.width(140.dp))
                    }
                    if (latestBlock != null) {
                        Column(modifier = Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(30.dp))
                            repeat(5) {
                                Box(modifier = Modifier.width(2.dp).height(8.dp).background(Color.White.copy(alpha = 0.4f)))
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        MinedBlock3D(blockInfo = latestBlock, modifier = Modifier.width(140.dp))
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF16213E)),
                    contentAlignment = Alignment.Center
                ) { Text("No mempool data available", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Block3D(blockInfo: ProjectedBlockInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val medianColor = getFeeRateColor(blockInfo.medianFeeRate)
    Column(modifier = modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (blockInfo.index == 0) "Next" else "~${(blockInfo.index + 1) * 10} min",
            color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp)).background(medianColor), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("~${formatFeeRate(blockInfo.medianFeeRate)} sat/vB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                Text("${formatFeeRate(blockInfo.minFeeRate)} - ${formatFeeRate(blockInfo.maxFeeRate)}", color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(String.format("%.3f BTC", blockInfo.totalFees), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
                Text("${blockInfo.transactionCount} tx", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MinedBlock3D(blockInfo: LatestBlockInfo, modifier: Modifier = Modifier) {
    val minutesAgo = ((System.currentTimeMillis() / 1000 - blockInfo.time) / 60).toInt()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("#${blockInfo.height}", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF6D4C94)), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${blockInfo.txCount} tx", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(2.dp))
                Text(String.format("%.2f MB", blockInfo.size / 1_000_000.0), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(4.dp))
                Text(if (minutesAgo < 1) "Just now" else "${minutesAgo}m ago", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun formatFeeRate(rate: Double): String = if (rate >= 10) String.format("%.0f", rate) else String.format("%.1f", rate)

@Composable
private fun FeeRateHistogramCard(feeRateHistogram: Map<Int, Int>) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Fee Rate Distribution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            if (feeRateHistogram.isNotEmpty()) {
                val maxCount = feeRateHistogram.values.maxOrNull() ?: 1
                feeRateHistogram.entries.sortedBy { it.key }.forEach { (feeRate, count) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${feeRate}+ sat/vB", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { count.toFloat() / maxCount },
                            modifier = Modifier.weight(1f).height(16.dp),
                            color = getFeeRateColor(feeRate.toDouble()),
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        Text(count.toString(), modifier = Modifier.width(60.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text("No fee rate data available", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
