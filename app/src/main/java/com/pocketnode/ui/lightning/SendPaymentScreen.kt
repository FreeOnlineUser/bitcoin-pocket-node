package com.pocketnode.ui.lightning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketnode.lightning.LightningService
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay

/**
 * Send a Lightning payment by pasting a BOLT11 invoice or BOLT12 offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendPaymentScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToScanner: () -> Unit = {},
    scannedQr: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val lightning = remember { LightningService.getInstance(context) }

    var invoiceInput by remember { mutableStateOf("") }

    // Graph readiness — need nodes for routing
    val lnState by com.pocketnode.lightning.LightningService.stateFlow.collectAsState()
    val graphReady = lnState.graphNodes >= 100

    // Hold network open while on this screen (peer connects while user reviews invoice)
    val networkHeld = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val pmm = com.pocketnode.power.PowerModeManager.getInstance(context)
        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
        if (creds != null) {
            pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
        }
        pmm.holdNetwork()
        networkHeld.value = true
        onDispose {
            if (networkHeld.value) {
                pmm.releaseNetworkHold()
            }
        }
    }

    // Apply scanned QR result when it arrives
    LaunchedEffect(scannedQr) {
        if (scannedQr != null) {
            invoiceInput = scannedQr
        }
    }
    var offerAmountSats by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var paymentComplete by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sendingStartTime by remember { mutableStateOf(0L) }
    var showGoBack by remember { mutableStateOf(false) }

    // Show "Go Back" after 60s of sending
    LaunchedEffect(sending) {
        if (sending) {
            sendingStartTime = System.currentTimeMillis()
            showGoBack = false
            delay(60_000)
            if (sending) showGoBack = true
        } else {
            showGoBack = false
        }
    }

    // Fee bump retry dialog state
    var showRetryDialog by remember { mutableStateOf(false) }
    var retryDetail by remember { mutableStateOf<com.pocketnode.lightning.PaymentManager.PaymentResult.RoutingFailed?>(null) }

    // Payment path tracker
    val paymentAttempt by lightning.payments.tracker.currentAttempt.collectAsState()
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when payment attempt updates, or on error/result
    LaunchedEffect(paymentAttempt, error, result) {
        delay(100) // Let compose lay out
        scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
    }

    // Detect input type
    val cleanInput = invoiceInput.removePrefix("lightning:").removePrefix("LIGHTNING:").trim()
    val isOffer = cleanInput.startsWith("lno1", ignoreCase = true)
    val isInvoice = cleanInput.startsWith("lnbc", ignoreCase = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Payment") },
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
            // Invoice input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Lightning Payment", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Paste a BOLT11 invoice or BOLT12 offer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = invoiceInput,
                        onValueChange = {
                            invoiceInput = it.trim()
                            error = null
                            result = null
                        },
                        label = { Text(if (isOffer) "BOLT12 Offer" else "Invoice or Offer") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        singleLine = false
                    )

                    Spacer(Modifier.height(8.dp))

                    // Scan and Paste buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onNavigateToScanner) {
                            Text("📷 Scan QR")
                        }
                        OutlinedButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let {
                                    invoiceInput = it.trim()
                                    error = null
                                    result = null
                                }
                            }
                        ) {
                            Text("📋 Paste")
                        }
                    }
                }
            }

            // Input preview with decoded details
            if (isInvoice || isOffer) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (isOffer) "BOLT12 Offer" else "BOLT11 Invoice",
                            style = MaterialTheme.typography.titleSmall
                        )

                        if (isInvoice) {
                            // Decode BOLT11 invoice to show amount and description
                            val decoded = remember(cleanInput) {
                                try { Bolt11Invoice.fromStr(cleanInput) } catch (_: Exception) { null }
                            }
                            if (decoded != null) {
                                Spacer(Modifier.height(8.dp))
                                val amountMsat = decoded.amountMilliSatoshis()
                                if (amountMsat != null) {
                                    val amountSats = amountMsat.toLong() / 1000
                                    Text(
                                        "${"%,d".format(amountSats)} sats",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9800)
                                    )
                                } else {
                                    Text("Amount: not specified", style = MaterialTheme.typography.bodyMedium)
                                }
                                val desc = decoded.invoiceDescription()
                                val descText = when (desc) {
                                    is Bolt11InvoiceDescription.Direct -> desc.description
                                    is Bolt11InvoiceDescription.Hash -> "Payment"
                                }
                                if (descText.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        descText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                if (decoded.isExpired()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "⚠️ This invoice has expired",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    cleanInput.take(40) + "...",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        if (isOffer) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cleanInput.take(40) + "...",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Reusable payment request. You may need to specify an amount.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Amount input for BOLT12 offers (may be variable amount)
            if (isOffer) {
                OutlinedTextField(
                    value = offerAmountSats,
                    onValueChange = { offerAmountSats = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount (sats). Leave empty if offer has fixed amount") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            // Pay button
            Button(
                onClick = {
                    if (invoiceInput.isBlank()) {
                        error = "Paste an invoice first"
                        return@Button
                    }
                    sending = true
                    error = null
                    result = null
                    scope.launch {
                        val payResult = withContext(Dispatchers.IO) {
                            if (isOffer) {
                                val amountMsat = offerAmountSats.toLongOrNull()?.let { it * 1000 }
                                lightning.payOffer(cleanInput, amountMsat)
                            } else {
                                lightning.payInvoice(cleanInput)
                            }
                        }
                        payResult.onSuccess { id ->
                            if (id.startsWith("PENDING:")) {
                                result = "⏳ Payment in flight, waiting for confirmation..."
                                sending = false
                                // Don't set paymentComplete — payment may still resolve
                            } else {
                                result = "⚡ Payment successful!"
                                sending = false
                                paymentComplete = true
                            }
                        }.onFailure { e ->
                            if (e is com.pocketnode.lightning.PaymentManager.RoutingException) {
                                retryDetail = e.detail
                                showRetryDialog = true
                                sending = false
                            } else {
                                error = e.message ?: "Payment failed"
                                sending = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !sending && !paymentComplete && invoiceInput.isNotBlank() && graphReady,
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
                } else if (!graphReady) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Syncing network...")
                } else {
                    Text(if (isOffer) "⚡ Pay Offer" else "⚡ Pay Invoice")
                }
            }

            // "Go Back" button after 60s of sending
            if (showGoBack && sending) {
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Payment still routing. Go back — balance will update when it resolves.")
                }
            }

            // Payment path display — single card with all route branches
            val allAttempts by lightning.payments.tracker.attempts.collectAsState()
            val failedAttempts = allAttempts.filter { it.status == com.pocketnode.lightning.PaymentTracker.AttemptStatus.FAILED }
            val hasRouteData = paymentAttempt != null && paymentAttempt!!.status != com.pocketnode.lightning.PaymentTracker.AttemptStatus.ROUTING

            if (failedAttempts.isNotEmpty() || hasRouteData) {
                RouteTreeCard(
                    failedAttempts = failedAttempts,
                    currentAttempt = if (hasRouteData) paymentAttempt else null
                )
            }

            // Result / Error
            if (result != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f))
                ) {
                    Text(
                        result!!,
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

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

    // Clear tracker when navigating away
    DisposableEffect(Unit) {
        onDispose { lightning.payments.tracker.clear() }
    }

    // Fee bump retry dialog
    if (showRetryDialog && retryDetail != null) {
        val detail = retryDetail!!
        val currentFeeSats = detail.currentMaxFeeMsat / 1000
        val bumpedFeeSats = detail.bumpedMaxFeeMsat / 1000
        val amountSats = detail.amountMsat / 1000

        AlertDialog(
            onDismissRequest = {
                showRetryDialog = false
                retryDetail = null
            },
            title = { Text("Routing Failed") },
            text = {
                Column {
                    Text("Could not find a route with max fee of $currentFeeSats sats.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Retry with higher fee budget of $bumpedFeeSats sats?",
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Payment: $amountSats sats",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRetryDialog = false
                        retryDetail = null
                        sending = true
                        error = null
                        scope.launch {
                            val bumpedConfig = com.pocketnode.lightning.PaymentManager.bumpedRouteConfig(detail.amountMsat)
                            val payResult = withContext(Dispatchers.IO) {
                                lightning.payInvoice(detail.invoiceStr, bumpedConfig)
                            }
                            payResult.onSuccess {
                                result = "⚡ Payment successful!"
                                sending = false
                                paymentComplete = true
                            }.onFailure { e ->
                                // Use the real failure reason, not generic message
                                error = e.message ?: "No route found with enough liquidity"
                                sending = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Retry ($bumpedFeeSats sat fee)")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRetryDialog = false
                    retryDetail = null
                    error = "Payment cancelled"
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RouteTreeCard(
    failedAttempts: List<com.pocketnode.lightning.PaymentTracker.PaymentAttempt>,
    currentAttempt: com.pocketnode.lightning.PaymentTracker.PaymentAttempt?
) {
    val current = currentAttempt ?: failedAttempts.lastOrNull() ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Route", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                val statusText = when (current.status) {
                    com.pocketnode.lightning.PaymentTracker.AttemptStatus.SUCCEEDED -> "✓ Delivered"
                    com.pocketnode.lightning.PaymentTracker.AttemptStatus.IN_FLIGHT -> "In flight"
                    com.pocketnode.lightning.PaymentTracker.AttemptStatus.FAILED ->
                        if (currentAttempt == null) "✗ All routes failed" else "✗ Failed"
                    else -> "Trying..."
                }
                Text(statusText, style = MaterialTheme.typography.labelSmall,
                    color = when (current.status) {
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.SUCCEEDED -> Color(0xFF4CAF50)
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> Color(0xFFFF9800)
                    })
            }

            Spacer(Modifier.height(8.dp))

            // Failed routes — one line each showing the path as names joined by →
            failedAttempts.forEach { attempt ->
                val names = attempt.hops.drop(1).map { hop ->
                    hop.alias ?: hop.nodeId.take(8) + "..."
                }
                val failHopIdx = if (attempt.failureHopIndex > 0) attempt.failureHopIndex - 1 else -1
                val failName = if (failHopIdx in names.indices) names[failHopIdx] else null
                val pathStr = names.joinToString(" → ")

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✗", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Text(pathStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            }

            if (failedAttempts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
            }

            // Current route — full hop display
            HopRow(label = "You", isFirst = true,
                status = com.pocketnode.lightning.PaymentTracker.HopStatus.SUCCESS, feeMsat = null)

            current.hops.forEachIndexed { index, hop ->
                val isLastHop = index == current.hops.lastIndex
                val displayAlias = hop.alias ?: hop.nodeId.take(12) + "..."
                val displayLabel = when {
                    isLastHop && current.status == com.pocketnode.lightning.PaymentTracker.AttemptStatus.SUCCEEDED -> "⚡ $displayAlias"
                    else -> displayAlias
                }
                val displayFee = if (isLastHop) null else hop.feeMsat
                HopRow(
                    label = displayLabel,
                    isFirst = false,
                    status = hop.status,
                    feeMsat = displayFee,
                    isFailed = index == current.failureHopIndex
                )
            }

            // Failure reason
            if (current.failureReason != null) {
                Spacer(Modifier.height(4.dp))
                Text(cleanFailureReason(current.failureReason!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PaymentPathCard(
    attempt: com.pocketnode.lightning.PaymentTracker.PaymentAttempt,
    label: String? = null,
    compact: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (compact && attempt.status == com.pocketnode.lightning.PaymentTracker.AttemptStatus.FAILED)
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(if (compact) 8.dp else 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label ?: "Route",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when (attempt.status) {
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.ROUTING -> "Finding route..."
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.IN_FLIGHT -> "In flight"
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.SUCCEEDED -> "✓ Delivered"
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.FAILED -> "✗ Failed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (attempt.status) {
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.SUCCEEDED -> Color(0xFF4CAF50)
                        com.pocketnode.lightning.PaymentTracker.AttemptStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> Color(0xFFFF9800)
                    }
                )
            }

            if (!compact) Spacer(Modifier.height(8.dp))

            // You (sender) — skip in compact mode
            if (!compact) {
                HopRow(
                    label = "You",
                    isFirst = true,
                    status = com.pocketnode.lightning.PaymentTracker.HopStatus.SUCCESS,
                    feeMsat = null
                )
            }

            // Each hop
            attempt.hops.forEachIndexed { index, hop ->
                val isLastHop = index == attempt.hops.lastIndex
                val displayAlias = hop.alias ?: hop.nodeId.take(12) + "..."
                val displayLabel = when {
                    isLastHop && attempt.status == com.pocketnode.lightning.PaymentTracker.AttemptStatus.SUCCEEDED -> "⚡ $displayAlias"
                    isLastHop -> displayAlias  // No bolt on failed/pending destination
                    else -> displayAlias
                }
                // Last hop fee_msat is the payment amount, not a routing fee
                val displayFee = if (isLastHop) null else if (hop.feeMsat > 0) hop.feeMsat else null
                HopRow(
                    label = displayLabel,
                    isFirst = false,
                    status = hop.status,
                    feeMsat = displayFee,
                    isFailed = index == attempt.failureHopIndex
                )
            }

            // Failure reason (cleaned up from raw Rust debug format)
            if (attempt.failureReason != null && !compact) {
                Spacer(Modifier.height(4.dp))
                Text(
                    cleanFailureReason(attempt.failureReason!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Convert raw Rust PathFailure debug strings to human-readable text. */
private fun cleanFailureReason(raw: String): String {
    return when {
        raw.contains("ChannelFailure") && raw.contains("is_permanent: false") ->
            "Peer didn't have enough outgoing liquidity"
        raw.contains("ChannelFailure") && raw.contains("is_permanent: true") ->
            "Channel permanently failed"
        raw.contains("NodeFailure") && raw.contains("is_permanent: false") ->
            "Node temporarily unreachable"
        raw.contains("NodeFailure") && raw.contains("is_permanent: true") ->
            "Node permanently unreachable"
        raw.contains("TemporaryChannelFailure") ->
            "Peer didn't have enough outgoing liquidity"
        raw.contains("UnknownNextPeer") ->
            "Next hop not found"
        raw.contains("FeeInsufficient") ->
            "Fee too low for this route"
        raw.contains("IncorrectOrUnknownPaymentDetails") ->
            "Invoice expired or already paid"
        else -> raw.take(80)
    }
}

@Composable
private fun HopRow(
    label: String,
    isFirst: Boolean,
    status: com.pocketnode.lightning.PaymentTracker.HopStatus,
    feeMsat: Long?,
    isFailed: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connection line + status dot
        Text(
            when {
                isFirst -> "●"
                isFailed -> "✗"
                else -> "├─"
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when {
                isFailed -> MaterialTheme.colorScheme.error
                status == com.pocketnode.lightning.PaymentTracker.HopStatus.SUCCESS -> Color(0xFF4CAF50)
                status == com.pocketnode.lightning.PaymentTracker.HopStatus.PENDING -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.width(24.dp)
        )

        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                isFailed -> MaterialTheme.colorScheme.error
                status == com.pocketnode.lightning.PaymentTracker.HopStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )

        if (feeMsat != null && feeMsat > 0) {
            Text(
                "+${feeMsat / 1000}.${(feeMsat % 1000).toString().padStart(3, '0')} sat",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
