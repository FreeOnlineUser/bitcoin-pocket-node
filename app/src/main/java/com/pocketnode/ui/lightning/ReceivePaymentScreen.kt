package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import com.pocketnode.power.PowerModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Generate a BOLT11 invoice or BOLT12 offer to receive Lightning payments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivePaymentScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val lightning = remember { LightningService.getInstance(context) }

    var useOffer by remember { mutableStateOf(false) }
    var amountSats by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    var waitTimeLeft by remember { mutableStateOf(0) }

    // Hold network while waiting for payment, release on unmount
    DisposableEffect(waiting) {
        if (waiting && PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX) {
            val pmm = PowerModeManager.getInstance(context)
            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
            if (creds != null) pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
            pmm.holdNetwork()
            onDispose { pmm.releaseNetworkHold() }
        } else {
            onDispose { }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive Payment") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = { com.pocketnode.ui.components.PeerCountBadge() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Invoice / Offer toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (useOffer) "BOLT12 Offer" else "BOLT11 Invoice",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (useOffer)
                                    if (PowerModeManager.modeFlow.value == PowerModeManager.Mode.MAX)
                                        "Reusable, no expiry. Payers need BOLT12 support. Best on Max mode (always online)."
                                    else
                                        "Reusable, no expiry. Requires you to be online when scanned. Switch to Max mode for reliable use."
                                else
                                    if (PowerModeManager.modeFlow.value == PowerModeManager.Mode.MAX)
                                        "One-time invoice, expires in 24 hours on Max mode. Works with all Lightning wallets."
                                    else
                                        "One-time invoice, expires in 5 minutes in this mode. For 24-hour expiry, create the invoice in Max mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = useOffer,
                            onCheckedChange = {
                                useOffer = it
                                output = null
                                error = null
                                copied = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFFFF9800)
                            )
                        )
                    }
                }
            }

            // Amount input
            OutlinedTextField(
                value = amountSats,
                onValueChange = {
                    amountSats = it.filter { c -> c.isDigit() }
                    output = null
                    error = null
                    copied = false
                },
                label = {
                    Text(if (useOffer) "Amount (sats), optional for offers" else "Amount (sats)")
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            // Description input
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    output = null
                    error = null
                    copied = false
                },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Generate button
            Button(
                onClick = {
                    if (!useOffer) {
                        val sats = amountSats.toLongOrNull()
                        if (sats == null || sats <= 0) {
                            error = "Enter a valid amount in sats"
                            return@Button
                        }
                    }
                    generating = true
                    error = null
                    output = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            if (useOffer) {
                                val sats = amountSats.toLongOrNull()
                                if (sats != null && sats > 0) {
                                    lightning.createOffer(sats * 1000, description.ifBlank { "Bitcoin Pocket Node" })
                                } else {
                                    lightning.createVariableOffer(description.ifBlank { "Bitcoin Pocket Node" })
                                }
                            } else {
                                val sats = amountSats.toLongOrNull() ?: 0L
                                val expiry = if (PowerModeManager.modeFlow.value == PowerModeManager.Mode.MAX) 86400 else 300
                                lightning.createInvoice(
                                    amountMsat = sats * 1000,
                                    description = description.ifBlank { "Bitcoin Pocket Node" },
                                    expirySecs = expiry
                                )
                            }
                        }
                        result.onSuccess { invoiceStr ->
                            output = invoiceStr
                            generating = false
                            focusManager.clearFocus()
                            scope.launch { delay(100); scrollState.animateScrollTo(scrollState.maxValue) }
                            // Start waiting for payment (BOLT11 only)
                            if (!useOffer) {
                                waiting = true
                                waitTimeLeft = if (PowerModeManager.modeFlow.value == PowerModeManager.Mode.MAX) 86400 else 300
                                val balanceBefore = LightningService.stateFlow.value.lightningBalanceSats
                                scope.launch {
                                    while (waitTimeLeft > 0 && !received) {
                                        delay(2000)
                                        waitTimeLeft -= 2
                                        val current = LightningService.stateFlow.value.lightningBalanceSats
                                        if (current > balanceBefore) {
                                            received = true
                                            waiting = false
                                            scope.launch { delay(100); scrollState.animateScrollTo(scrollState.maxValue) }
                                        }
                                    }
                                    if (!received) waiting = false
                                }
                            }
                        }.onFailure {
                            error = it.message ?: "Failed to generate"
                            generating = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !generating && (useOffer || amountSats.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Generating...")
                } else {
                    Text(if (useOffer) "⚡ Generate Offer" else "⚡ Generate Invoice")
                }
            }

            // Output display
            if (output != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (useOffer) "Offer" else "Invoice",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (useOffer) "Share this offer. It can be paid multiple times and never expires. Tied to your current channels -- regenerate if channels change."
                            else if (PowerModeManager.modeFlow.value == PowerModeManager.Mode.MAX)
                                "Share this invoice with the sender. It expires in 24 hours."
                            else
                                "Share this invoice with the sender. It expires in 5 minutes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(12.dp))

                        // QR code
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            QrCodeImage(data = output!!)
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            output!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(output!!))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (copied) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                        ) {
                            Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (copied) "Copied!" else if (useOffer) "Copy Offer" else "Copy Invoice")
                        }

                        // Payment waiting status
                        if (!useOffer && (waiting || received)) {
                            Spacer(Modifier.height(12.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (received) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                    else Color(0xFFFF9800).copy(alpha = 0.15f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (received) {
                                        Text("⚡", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Payment received!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4CAF50)
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color(0xFFFF9800),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            "Waiting for payment... ${waitTimeLeft / 60}:${"%02d".format(waitTimeLeft % 60)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFFFF9800)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Error
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        error!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
