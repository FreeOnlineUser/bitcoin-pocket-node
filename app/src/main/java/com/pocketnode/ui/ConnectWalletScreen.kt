package com.pocketnode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.service.BwtService

/**
 * Screen for connecting external wallet apps to the local Electrum server (BWT).
 * Shows BWT status, connection details, and lets users add xpubs/addresses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectWalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val bwtState by BwtService.stateFlow.collectAsState()
    val bwtRunning by BwtService.isRunningFlow.collectAsState()

    var showAddXpub by remember { mutableStateOf(false) }
    var showAddAddress by remember { mutableStateOf(false) }
    var xpubInput by remember { mutableStateOf("") }
    var addressInput by remember { mutableStateOf("") }

    val xpubs = remember { mutableStateListOf<String>() }
    val addresses = remember { mutableStateListOf<String>() }

    // Load saved config
    LaunchedEffect(Unit) {
        xpubs.clear()
        xpubs.addAll(BwtService.SavedConfig.getXpubs(context))
        addresses.clear()
        addresses.addAll(BwtService.SavedConfig.getAddresses(context))
    }

    val bwtService = remember { BwtService(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Bitcoin RPC card
            val connClip = LocalClipboardManager.current
            val rpcCreds = remember { com.pocketnode.util.ConfigGenerator.readCredentials(context) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bitcoin RPC", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    CopyableValue("Host", "127.0.0.1", connClip)
                    CopyableValue("Port", "8332", connClip)
                    CopyableValue("User", rpcCreds?.first ?: "—", connClip)
                    CopyableValue("Password", rpcCreds?.second ?: "—", connClip)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "For any app with direct RPC support.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // Lightning / Neutrino card (only when block filters installed)
            val filterDir = context.filesDir.resolve("bitcoin/indexes/blockfilter/basic")
            val hasFilters = filterDir.exists() && (filterDir.listFiles()?.any { it.name.startsWith("fltr") } == true)
            if (hasFilters) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFB300).copy(alpha = 0.15f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚡ Lightning (Neutrino)", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        CopyableValue("Host", "127.0.0.1", connClip)
                        CopyableValue("Port", "8333", connClip)
                        Text("BIP 157/158 compact block filters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Connect Zeus or any Neutrino-compatible Lightning wallet. Your node serves block filters for sovereign chain validation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Zeus setup guide (only when Lightning is available)
            if (hasFilters) {
                ZeusSetupGuideCard()
            }

            // Electrum Server card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (bwtState.status) {
                        BwtService.BwtState.Status.RUNNING -> Color(0xFF1B5E20).copy(alpha = 0.2f)
                        BwtService.BwtState.Status.ERROR -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Electrum Server", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (statusText, statusColor) = when (bwtState.status) {
                            BwtService.BwtState.Status.RUNNING -> "Running" to Color(0xFF4CAF50)
                            BwtService.BwtState.Status.STARTING -> "Starting..." to Color(0xFFFF9800)
                            BwtService.BwtState.Status.ERROR -> "Error" to MaterialTheme.colorScheme.error
                            BwtService.BwtState.Status.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        }
                        Text("●", color = statusColor)
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    }

                    if (bwtState.status == BwtService.BwtState.Status.RUNNING) {
                        Spacer(Modifier.height(8.dp))
                        CopyableValue("Host", BwtService.ELECTRUM_HOST, connClip)
                        CopyableValue("Port", BwtService.ELECTRUM_PORT.toString(), connClip)
                        Text("TCP (no SSL)", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Connect a wallet app like BlueWallet to this address.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    if (bwtState.error != null) {
                        Text(bwtState.error!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Start/Stop button
            val hasConfig = xpubs.isNotEmpty() || addresses.isNotEmpty()
            if (bwtRunning) {
                OutlinedButton(
                    onClick = { bwtService.stop() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Electrum Server")
                }
            } else {
                Button(
                    onClick = { bwtService.start() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasConfig,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7931A))
                ) {
                    Text(
                        if (hasConfig) "Start Electrum Server" else "Add an xpub or address first",
                        fontWeight = FontWeight.Bold,
                        color = if (hasConfig) Color.Black else Color.Gray
                    )
                }
            }

            // Tracked xpubs/addresses
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Electrum Tracked Wallets", fontWeight = FontWeight.Bold)

                    if (xpubs.isEmpty() && addresses.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No wallets configured yet.\nAdd your wallet's xpub to track all addresses,\nor add individual addresses.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (xpubs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("WALLETS", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        xpubs.forEach { xpub ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFFF7931A))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${xpub.take(8)}...${xpub.takeLast(8)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        when {
                                            xpub.startsWith("zpub") -> "Native SegWit (zpub)"
                                            xpub.startsWith("ypub") -> "SegWit (ypub)"
                                            xpub.startsWith("xpub") -> "Legacy (xpub)"
                                            else -> "Extended public key"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                                IconButton(onClick = {
                                    BwtService.SavedConfig.removeXpub(context, xpub)
                                    xpubs.remove(xpub)
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }

                    if (addresses.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("ADDRESSES", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        addresses.forEach { addr ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Tag, contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${addr.take(8)}...${addr.takeLast(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    BwtService.SavedConfig.removeAddress(context, addr)
                                    addresses.remove(addr)
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }

                    // Add buttons
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddXpub = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFF7931A)
                            )
                        ) {
                            Text("+ Wallet", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showAddAddress = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("+ Address", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Privacy note moved into Connection Details card
        }
    }

    // Add xpub dialog
    if (showAddXpub) {
        val clipboardManager = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showAddXpub = false },
            title = { Text("Add Extended Public Key") },
            text = {
                Column {
                    Text(
                        "Paste your wallet's extended public key. BlueWallet uses zpub — find it in wallet settings. xpub and ypub are also supported.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = xpubInput,
                        onValueChange = { xpubInput = it },
                        label = { Text("zpub / xpub / ypub") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { xpubInput = it }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = xpubInput.trim()
                        if (trimmed.isNotEmpty()) {
                            BwtService.SavedConfig.saveXpub(context, trimmed)
                            xpubs.add(trimmed)
                            xpubInput = ""
                        }
                        showAddXpub = false
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddXpub = false }) { Text("Cancel") }
            }
        )
    }

    // Add address dialog  
    if (showAddAddress) {
        val clipboardManager = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showAddAddress = false },
            title = { Text("Add Address") },
            text = {
                Column {
                    Text(
                        "Paste a Bitcoin address to watch. Use this for individual addresses you want to monitor.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        label = { Text("Bitcoin address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { addressInput = it }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = addressInput.trim()
                        if (trimmed.isNotEmpty()) {
                            BwtService.SavedConfig.saveAddress(context, trimmed)
                            addresses.add(trimmed)
                            addressInput = ""
                        }
                        showAddAddress = false
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddAddress = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Expandable card walking users through Zeus setup step by step.
 * Includes version warning (v0.12.3+ has a SQLite bug that stalls sync)
 * and the non-obvious restart/wait/settings dance required by Zeus.
 */
@Composable
private fun ZeusSetupGuideCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFB300).copy(alpha = 0.10f)
        ),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚡ Zeus Setup Guide",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            if (!expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap for step-by-step Lightning wallet setup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Version warning
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Install Zeus v0.12.2 only",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "v0.12.3 and newer have a SQLite bug that stalls chain sync at block 123,000. Download v0.12.2 from Zeus GitHub releases.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // Setup steps
                    ZeusStep(1, "Install Zeus v0.12.2",
                        "Download from GitHub releases: github.com/ZeusLN/zeus/releases/tag/v0.12.2")
                    ZeusStep(2, "Create embedded LND",
                        "Open Zeus and select \"Create embedded LND\" to set up a new Lightning wallet.")
                    ZeusStep(3, "Wait for initial sync",
                        "Zeus shows a boot animation for about 15 minutes while it syncs block headers. This is normal. Do not change any settings yet.")
                    ZeusStep(4, "Restart Zeus",
                        "Close and reopen the app after the boot animation finishes.")
                    ZeusStep(5, "Wait for warning icon",
                        "A warning icon (⚠️) appears at the top of the screen. This means Zeus is ready to configure.")
                    ZeusStep(6, "Open node settings",
                        "Tap the warning icon to open node settings.")
                    ZeusStep(7, "Find Neutrino Peers",
                        "Navigate to: Embedded node → Peers → Neutrino Peers")
                    ZeusStep(8, "Remove default peers",
                        "Delete ALL default Neutrino peers from the list.")
                    ZeusStep(9, "Add localhost",
                        "Add 127.0.0.1 as the only Neutrino peer. This connects Zeus to your local Bitcoin node.")
                    ZeusStep(10, "Restart Zeus again",
                        "Close and reopen Zeus. It connects to your local node and the wallet appears.")
                    ZeusStep(11, "Verify connection",
                        "Settings → Embedded node → Peers → Neutrino Peers should show 127.0.0.1 as the only peer.")

                    // Success note
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Once connected, Zeus uses your node's block filters for fully sovereign chain validation. No external servers involved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ZeusStep(number: Int, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Step number badge
        Surface(
            shape = MaterialTheme.shapes.small,
            color = Color(0xFFF7931A).copy(alpha = 0.2f),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF7931A)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun CopyableValue(
    label: String,
    value: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(value)) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = "Copy $label",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
