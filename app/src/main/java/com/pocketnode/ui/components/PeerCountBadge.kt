package com.pocketnode.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService

/**
 * Subtle peer count for Lightning screen top bars.
 */
@Composable
fun PeerCountBadge(modifier: Modifier = Modifier) {
    val lnState by LightningService.stateFlow.collectAsState()
    val peers = lnState.peerCount

    Text(
        text = "${peers}p",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.padding(end = 12.dp)
    )
}
