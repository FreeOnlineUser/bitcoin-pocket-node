package com.pocketnode.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import kotlinx.coroutines.delay

/**
 * Subtle peer count for Lightning screen top bars.
 * Polls every 2 seconds for near-real-time updates.
 */
@Composable
fun PeerCountBadge(modifier: Modifier = Modifier) {
    var peers by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val node = LightningService.nodeRef
                if (node != null) {
                    peers = node.listPeers().size
                }
            } catch (_: Exception) {}
            delay(2000)
        }
    }

    Text(
        text = "$peers peer${if (peers != 1) "s" else ""}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.padding(end = 12.dp)
    )
}
