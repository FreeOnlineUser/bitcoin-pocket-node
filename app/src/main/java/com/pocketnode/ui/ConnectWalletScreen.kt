package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tag
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
            // Explanation
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Personal Electrum Server", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This runs a lightweight Electrum server (BWT) on your phone. " +
                        "Unlike public Electrum servers, it only tracks wallets you add below — " +
                        "your addresses never leave the device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add your wallet's zpub/xpub, then point your wallet app " +
                        "(BlueWallet, Electrum, etc.) at this server. Only wallets listed " +
                        "here will be tracked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ The Electrum server indexes blocks from your node. " +
                        "If your node is still syncing, wallet history may be incomplete " +
                        "until it reaches the chain tip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
            }

            // Status card
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (statusText, statusColor) = when (bwtState.status) {
                            BwtService.BwtState.Status.RUNNING -> "Electrum Server Running" to Color(0xFF4CAF50)
                            BwtService.BwtState.Status.STARTING -> "Starting..." to Color(0xFFFF9800)
                            BwtService.BwtState.Status.ERROR -> "Error" to MaterialTheme.colorScheme.error
                            BwtService.BwtState.Status.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        }
                        Text("●", color = statusColor, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, fontWeight = FontWeight.Bold)
                    }

                    if (bwtState.status == BwtService.BwtState.Status.RUNNING) {
                        val statusClip = LocalClipboardManager.current
                        Spacer(Modifier.height(12.dp))
                        Text("Electrum Server", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                bwtState.electrumAddress,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF7931A),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { statusClip.setText(AnnotatedString(bwtState.electrumAddress)) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy address",
                                    modifier = Modifier.size(20.dp),
                                    tint = Color(0xFFF7931A)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Protocol: TCP (no SSL)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    if (bwtState.error != null) {
                        Spacer(Modifier.height(8.dp))
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

            // Connection instructions
            if (bwtRunning) {
                val clipManager = LocalClipboardManager.current
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Connect Your Wallet", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("BlueWallet", fontWeight = FontWeight.Medium,
                            color = Color(0xFFF7931A))
                        Text("Settings → Network → Electrum Server",
                            style = MaterialTheme.typography.bodySmall)
                        CopyableValue("Host", bwtState.electrumAddress.substringBefore(":"), clipManager)
                        CopyableValue("Port", bwtState.electrumAddress.substringAfter(":"), clipManager)
                        Spacer(Modifier.height(8.dp))
                        Text("Electrum (Android)", fontWeight = FontWeight.Medium,
                            color = Color(0xFFF7931A))
                        Text("Network → Server → Use custom server",
                            style = MaterialTheme.typography.bodySmall)
                        CopyableValue("Host", BwtService.ELECTRUM_HOST, clipManager)
                        CopyableValue("Port", BwtService.ELECTRUM_PORT.toString(), clipManager)
                        Spacer(Modifier.height(8.dp))
                        Text("Other Wallets", fontWeight = FontWeight.Medium,
                            color = Color(0xFFF7931A))
                        Text("Any wallet with \"custom Electrum server\" can connect.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Use TCP (not SSL) on port ${BwtService.ELECTRUM_PORT}.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Tracked xpubs/addresses
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tracked Wallets", fontWeight = FontWeight.Bold)

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

            // Standard RPC connection
            val rpcCreds = remember { com.pocketnode.util.ConfigGenerator.readCredentials(context) }
            val rpcClip = LocalClipboardManager.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bitcoin RPC", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Direct JSON-RPC connection to your node.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.height(8.dp))
                    CopyableValue("Host", "127.0.0.1", rpcClip)
                    CopyableValue("Port", "8332", rpcClip)
                    CopyableValue("User", rpcCreds?.first ?: "—", rpcClip)
                    CopyableValue("Password", rpcCreds?.second ?: "—", rpcClip)
                    Spacer(Modifier.height(8.dp))
                    Text("For apps that support direct bitcoind RPC (e.g. Fully Noded, bitcoin-cli).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }

            // Safety info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Privacy", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your xpub lets BWT derive and track your wallet addresses locally. " +
                        "It never leaves the device. Unlike public Electrum servers, your " +
                        "address queries stay on your phone — zero address leakage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "An xpub can derive all your addresses but cannot spend funds. " +
                        "It's safe to import here — your private keys stay in your wallet app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
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
