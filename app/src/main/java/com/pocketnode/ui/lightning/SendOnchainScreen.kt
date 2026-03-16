package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import com.pocketnode.power.PowerModeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.FeeRate

/**
 * Send on-chain Bitcoin from the LDK wallet to an external address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendOnchainScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {},
    scannedQr: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lightning = remember { LightningService.getInstance(context) }
    val state by LightningService.stateFlow.collectAsState()

    var address by remember { mutableStateOf("") }
    var amountSats by remember { mutableStateOf("") }
    var feeRate by remember { mutableStateOf("4") }
    var sendAll by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendComplete by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Handle scanned QR
    LaunchedEffect(scannedQr) {
        if (scannedQr != null) {
            // Parse bitcoin: URI or plain address
            val cleaned = scannedQr.trim()
            if (cleaned.startsWith("bitcoin:", ignoreCase = true)) {
                val uri = cleaned.removePrefix("bitcoin:").removePrefix("Bitcoin:")
                val parts = uri.split("?", limit = 2)
                address = parts[0]
                if (parts.size > 1) {
                    val params = parts[1].split("&").associate {
                        val kv = it.split("=", limit = 2)
                        kv[0].lowercase() to (kv.getOrNull(1) ?: "")
                    }
                    params["amount"]?.let { btcAmount ->
                        btcAmount.toDoubleOrNull()?.let { btc ->
                            amountSats = (btc * 100_000_000).toLong().toString()
                        }
                    }
                }
            } else {
                address = cleaned
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send On-chain") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Balance display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Available Balance", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("${"%,d".format(state.onchainBalanceSats)} sats",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                }
            }

            // Address input
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it.trim()
                    error = null
                    result = null
                },
                label = { Text("Recipient address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onNavigateToScanner) {
                        Icon(Icons.Default.QrCodeScanner, "Scan QR")
                    }
                }
            )

            // Amount input
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = if (sendAll) "" else amountSats,
                    onValueChange = {
                        amountSats = it.filter { c -> c.isDigit() }
                        sendAll = false
                        error = null
                        result = null
                    },
                    label = { Text("Amount (sats)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !sendAll
                )
                OutlinedButton(
                    onClick = {
                        sendAll = !sendAll
                        if (sendAll) amountSats = ""
                        error = null
                    }
                ) {
                    Text(if (sendAll) "MAX ✓" else "MAX")
                }
            }

            // Fee rate
            OutlinedTextField(
                value = feeRate,
                onValueChange = {
                    feeRate = it.filter { c -> c.isDigit() || c == '.' }
                    error = null
                },
                label = { Text("Fee rate (sat/vB)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            // Send button
            Button(
                onClick = {
                    if (address.isBlank()) {
                        error = "Enter a recipient address"
                        return@Button
                    }
                    if (!sendAll) {
                        val sats = amountSats.toLongOrNull()
                        if (sats == null || sats <= 0) {
                            error = "Enter a valid amount"
                            return@Button
                        }
                    }
                    val rate = feeRate.toDoubleOrNull()
                    if (rate == null || rate < 1.0) {
                        error = "Fee rate must be at least 1 sat/vB"
                        return@Button
                    }

                    sending = true
                    error = null
                    result = null

                    scope.launch {
                        // Hold network on Low/Away
                        val needsHold = PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX
                        if (needsHold) {
                            val pmm = PowerModeManager.getInstance(context)
                            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                            if (creds != null) pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
                            pmm.holdNetwork()
                        }

                        val feeRateObj = FeeRate.fromSatPerVbUnchecked(rate.toULong())
                        val sendResult = withContext(Dispatchers.IO) {
                            if (sendAll) {
                                lightning.sendAllOnchain(address, feeRateObj)
                            } else {
                                lightning.sendOnchain(address, amountSats.toLong(), feeRateObj)
                            }
                        }

                        if (needsHold) {
                            // Brief delay for tx propagation then release
                            kotlinx.coroutines.delay(5000)
                            val pmm = PowerModeManager.getInstance(context)
                            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                            if (creds != null) pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
                            pmm.releaseNetworkHold()
                        }

                        sendResult.onSuccess { txid ->
                            result = "Sent! txid: $txid"
                            sending = false
                            sendComplete = true
                        }.onFailure {
                            error = it.message ?: "Failed to send"
                            sending = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !sending && !sendComplete && address.isNotBlank() && (sendAll || amountSats.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Text(if (sendAll) "Send All" else "Send")
                }
            }

            // Result
            if (result != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ Transaction Sent",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50))
                        Spacer(Modifier.height(8.dp))
                        Text(result!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3)
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

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("On-chain transactions confirm in ~10 minutes (1 block).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("Use 'MAX' to send entire balance (minus fee).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
