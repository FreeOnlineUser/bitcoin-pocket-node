package com.pocketnode.ui.mempool

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketnode.mempool.GbtResult
import com.pocketnode.mempool.MempoolState
import kotlin.math.min

@Composable
fun MempoolScreen(
    onNavigateToTransactionSearch: () -> Unit,
    viewModel: MempoolViewModel = viewModel()
) {
    val mempoolState by viewModel.mempoolState.collectAsStateWithLifecycle()
    val gbtResult by viewModel.gbtResult.collectAsStateWithLifecycle()
    val feeRateHistogram by viewModel.feeRateHistogram.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val newBlockDetected by viewModel.newBlockDetected.collectAsStateWithLifecycle()
    val confirmedTransaction by viewModel.confirmedTransaction.collectAsStateWithLifecycle()
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.startMempoolUpdates()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopMempoolUpdates()
        }
    }
    
    // Handle new block detection with haptic feedback
    LaunchedEffect(newBlockDetected) {
        newBlockDetected?.let { blockHash ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.clearNewBlockDetected()
        }
    }
    
    // Handle transaction confirmation with stronger haptic feedback
    LaunchedEffect(confirmedTransaction) {
        confirmedTransaction?.let { event ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            // TODO: Show toast/snackbar with confirmation message
            viewModel.clearConfirmedTransaction()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshMempool() },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        item {
            MempoolStatsCard(mempoolState = mempoolState)
        }
        
        item {
            FeeEstimatePanel()
        }

        item {
            ProjectedBlocksVisualization(
                gbtResult = gbtResult,
                onBlockClick = { blockIndex ->
                    // Show block details
                    viewModel.showBlockDetails(blockIndex)
                }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Transaction Search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Search for a specific transaction by TXID",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToTransactionSearch,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Search Transaction")
                    }
                }
            }
        }

        item {
            FeeRateHistogramCard(feeRateHistogram = feeRateHistogram)
        }
        }
    }
}

@Composable
private fun MempoolStatsCard(mempoolState: MempoolState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Mempool Statistics",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9500) // Bitcoin orange
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = "Transactions",
                    value = mempoolState.transactionCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "Total vMB",
                    value = String.format("%.2f", mempoolState.totalVbytes / 1_000_000.0),
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "vB/s Inflow",
                    value = String.format("%.1f", mempoolState.vbytesPerSecond),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProjectedBlocksVisualization(
    gbtResult: GbtResult?,
    onBlockClick: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Projected Blocks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (gbtResult?.blocks?.isNotEmpty() == true) {
                ProjectedBlocksCanvas(
                    blocks = gbtResult.blocks,
                    blockWeights = gbtResult.blockWeights,
                    onBlockClick = onBlockClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No mempool data available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectedBlocksCanvas(
    blocks: Array<IntArray>,
    blockWeights: IntArray,
    onBlockClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    
    Canvas(
        modifier = modifier.clickable { /* Handle clicks based on touch position */ }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        
        if (blocks.isNotEmpty()) {
            val blockWidth = canvasWidth / blocks.size
            
            blocks.forEachIndexed { index, block ->
                val x = index * blockWidth
                val txCount = block.size
                val avgFeeRate = estimateBlockFeeRate(block) // You'll need to implement this
                
                val color = getFeeRateColor(avgFeeRate)
                
                // Draw block rectangle
                drawRect(
                    color = color,
                    topLeft = Offset(x, 0f),
                    size = Size(blockWidth - 2.dp.toPx(), canvasHeight)
                )
                
                // Draw transaction count text
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = with(density) { 12.sp.toPx() }
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    
                    drawText(
                        txCount.toString(),
                        x + blockWidth / 2,
                        canvasHeight / 2,
                        paint
                    )
                }
            }
        }
    }
}

private fun estimateBlockFeeRate(block: IntArray): Double {
    // TODO: Calculate actual fee rate from transaction data
    // For now, return a placeholder based on block position
    return when (block.size) {
        in 0..100 -> 1.0
        in 101..500 -> 5.0
        in 501..1000 -> 15.0
        in 1001..2000 -> 30.0
        in 2001..3000 -> 60.0
        else -> 120.0
    }
}

private fun getFeeRateColor(feeRate: Double): Color {
    return when {
        feeRate <= 2 -> Color(0xFFFF00FF) // Magenta (1-2 sat/vB)
        feeRate <= 4 -> Color(0xFF8000FF) // Purple (3-4 sat/vB)
        feeRate <= 10 -> Color(0xFF0080FF) // Blue (5-10 sat/vB)
        feeRate <= 20 -> Color(0xFF00FF00) // Green (11-20 sat/vB)
        feeRate <= 50 -> Color(0xFFFFFF00) // Yellow (21-50 sat/vB)
        feeRate <= 100 -> Color(0xFFFF8000) // Orange (51-100 sat/vB)
        else -> Color(0xFFFF0000) // Red (100+ sat/vB)
    }
}

@Composable
private fun FeeRateHistogramCard(feeRateHistogram: Map<Int, Int>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Fee Rate Distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (feeRateHistogram.isNotEmpty()) {
                val maxCount = feeRateHistogram.values.maxOrNull() ?: 1
                
                feeRateHistogram.entries.sortedBy { it.key }.forEach { (feeRate, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${feeRate}+ sat/vB",
                            modifier = Modifier.width(80.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        LinearProgressIndicator(
                            progress = count.toFloat() / maxCount,
                            modifier = Modifier
                                .weight(1f)
                                .height(16.dp),
                            color = getFeeRateColor(feeRate.toDouble()),
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        
                        Text(
                            text = count.toString(),
                            modifier = Modifier.width(60.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                Text(
                    text = "No fee rate data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}