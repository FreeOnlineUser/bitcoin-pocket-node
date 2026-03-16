package com.pocketnode.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService

/**
 * Subtle peer counts for Lightning screen top bars.
 * Shows "B:0 L:5" — bitcoind peers and Lightning peers.
 */
@Composable
fun PeerCountBadge(modifier: Modifier = Modifier) {
    val lnState by LightningService.stateFlow.collectAsState()

    Text(
        text = "B:${lnState.btcPeerCount} L:${lnState.lnPeerCount}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.padding(end = 12.dp)
    )
}
