package com.pocketnode.ui.mempool

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FeeEstimatePanel() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Recommended Fees",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FeeEstimateItem(
                    label = "Next Block",
                    feeRate = "45 sat/vB",
                    priority = FeePriority.High,
                    modifier = Modifier.weight(1f)
                )
                
                FeeEstimateItem(
                    label = "30 minutes",
                    feeRate = "25 sat/vB",
                    priority = FeePriority.Medium,
                    modifier = Modifier.weight(1f)
                )
                
                FeeEstimateItem(
                    label = "1 hour",
                    feeRate = "15 sat/vB",
                    priority = FeePriority.Low,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FeeEstimateItem(
    label: String,
    feeRate: String,
    priority: FeePriority,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = feeRate,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = priority.color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

enum class FeePriority(val color: Color) {
    High(Color(0xFFFF0000)),    // Red
    Medium(Color(0xFFFF8000)),  // Orange
    Low(Color(0xFF00FF00))      // Green
}