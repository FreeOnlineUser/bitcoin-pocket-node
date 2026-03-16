package com.pocketnode.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService

/**
 * Compact peer count badge for Lightning screen top bars.
 * Shows peer count with color-coded dot:
 * - Red: 0 peers (disconnected)
 * - Orange: 1-2 peers (limited)
 * - Green: 3+ peers (healthy)
 */
@Composable
fun PeerCountBadge(modifier: Modifier = Modifier) {
    val lnState by LightningService.stateFlow.collectAsState()
    val peers = lnState.peerCount

    Row(
        modifier = modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Color dot
        val dotColor = when {
            peers == 0 -> Color(0xFFF44336) // red
            peers <= 2 -> Color(0xFFFF9800) // orange
            else -> Color(0xFF4CAF50)       // green
        }
        androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = dotColor)
        }
        Text(
            text = "$peers peer${if (peers != 1) "s" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
