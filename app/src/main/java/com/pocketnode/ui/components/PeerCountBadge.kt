package com.pocketnode.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.pocketnode.lightning.LightningService

/**
 * Subtle peer counts for Lightning screen top bars.
 * Shows "B:0 L:5" — bitcoind peers and Lightning peers.
 * Tap for explainer popup.
 */
@Composable
fun PeerCountBadge(modifier: Modifier = Modifier) {
    val lnState by LightningService.stateFlow.collectAsState()
    val torActive = com.pocketnode.tor.TorManager.enabledFlow.collectAsState().value &&
            com.pocketnode.tor.TorManager.statusFlow.collectAsState().value == com.pocketnode.tor.TorManager.TorStatus.RUNNING
    var showExplainer by remember { mutableStateOf(false) }

    Text(
        text = "${if (torActive) "🧅 " else ""}B:${lnState.btcPeerCount} L:${lnState.lnPeerCount}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
            .padding(end = 12.dp)
            .clickable { showExplainer = true }
    )

    if (showExplainer) {
        Popup(
            onDismissRequest = { showExplainer = false },
            properties = PopupProperties(focusable = true)
        ) {
            Card(
                modifier = Modifier
                    .padding(16.dp)
                    .clickable { showExplainer = false },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "B: Bitcoin peers",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "P2P connections for downloading blocks, only needed when syncing. Also temporarily connects when sending or receiving with Lightning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "L: Lightning peers",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Connections to Lightning nodes for sending and receiving payments. Stay connected even when Bitcoin network is paused.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap anywhere to dismiss",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
