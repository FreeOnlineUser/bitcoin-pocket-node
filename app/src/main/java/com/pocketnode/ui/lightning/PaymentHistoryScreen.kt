package com.pocketnode.ui.lightning

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus

/**
 * Shows Lightning payment history from ldk-node.
 * Long-press a payment to delete it.
 * Expired unpaid invoices shown with "Expired" status.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PaymentHistoryScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lightning = remember { LightningService.getInstance(context) }
    var payments by remember {
        mutableStateOf(lightning.listPayments().sortedByDescending { it.latestUpdateTimestamp })
    }
    var deleteTarget by remember { mutableStateOf<PaymentDetails?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = { com.pocketnode.ui.components.PeerCountBadge() }
            )
        }
    ) { padding ->
        if (payments.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No payments yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(payments, key = { it.id }) { payment ->
                    PaymentCard(
                        payment = payment,
                        onLongPress = { deleteTarget = payment }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { payment ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Payment") },
            text = {
                Text("Remove this payment from history?\n\n${payment.id.take(24)}...")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lightning.removePayment(payment.id)
                        payments = lightning.listPayments().sortedByDescending { it.latestUpdateTimestamp }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaymentCard(
    payment: PaymentDetails,
    onLongPress: () -> Unit
) {
    val isInbound = payment.direction == PaymentDirection.INBOUND
    val amountMsat = payment.amountMsat?.toLong() ?: 0L
    val amountSats = amountMsat / 1000
    val nowSecs = System.currentTimeMillis() / 1000
    val updateTimeSecs = payment.latestUpdateTimestamp.toLong()

    // Detect expired: inbound + pending + older than 1 hour (default invoice expiry)
    val isExpired = isInbound
            && payment.status == PaymentStatus.PENDING
            && (nowSecs - updateTimeSecs) > 3600

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpired)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isInbound) "⬇️" else "⬆️",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isExpired -> "Expired Invoice"
                            isInbound -> "Received"
                            else -> "Sent"
                        },
                        fontWeight = FontWeight.Bold,
                        color = if (isExpired) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    payment.id.take(16) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (isInbound) "+" else "-"}${"%,d".format(amountSats)} sats",
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isExpired -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        isInbound -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.height(4.dp))
                val (statusText, statusColor) = when {
                    isExpired -> "Expired" to Color(0xFF607D8B)
                    payment.status == PaymentStatus.PENDING -> "Pending" to Color(0xFFFF9800)
                    payment.status == PaymentStatus.SUCCEEDED -> "Completed" to Color(0xFF4CAF50)
                    payment.status == PaymentStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
                    else -> "Unknown" to MaterialTheme.colorScheme.onSurface
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}
