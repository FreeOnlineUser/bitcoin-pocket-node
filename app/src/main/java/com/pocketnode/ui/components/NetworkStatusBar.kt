package com.pocketnode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.network.DataUsageEntry
import com.pocketnode.network.NetworkState

private val AmberColor = Color(0xFFFFC107)
private val AmberDark = Color(0xFF5D4600)
private val RedColor = Color(0xFFF44336)
private val RedDark = Color(0xFF5C1A16)
private val GreenColor = Color(0xFF4CAF50)

/**
 * Banner shown at top of dashboard for network status.
 */
@Composable
fun NetworkStatusBar(
    networkState: NetworkState,
    syncPaused: Boolean,
    todayUsage: DataUsageEntry?,
    onAllowCellular: () -> Unit
) {
    Column {
        // Offline banner
        AnimatedVisibility(
            visible = networkState == NetworkState.OFFLINE,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RedDark)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚠ No network connection",
                    color = RedColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Cellular paused banner
        AnimatedVisibility(
            visible = networkState == NetworkState.CELLULAR && syncPaused,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmberDark)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sync paused — on mobile data",
                    color = AmberColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onAllowCellular,
                    colors = ButtonDefaults.textButtonColors(contentColor = AmberColor)
                ) {
                    Text("Allow", fontWeight = FontWeight.Bold)
                }
            }
        }

        // WiFi indicator + data usage summary
        if (networkState == NetworkState.WIFI && todayUsage != null) {
            val rxStr = formatBytes(todayUsage.wifiRx)
            val txStr = formatBytes(todayUsage.wifiTx)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = GreenColor,
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    "Today: $rxStr ↓ / $txStr ↑ (WiFi)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else if (networkState == NetworkState.CELLULAR && !syncPaused && todayUsage != null) {
            val rxStr = formatBytes(todayUsage.cellularRx)
            val txStr = formatBytes(todayUsage.cellularTx)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Today: $rxStr ↓ / $txStr ↑ (Cellular)",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
