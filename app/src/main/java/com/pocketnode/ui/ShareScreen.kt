package com.pocketnode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.share.ShareServer
import com.pocketnode.ui.lightning.QrCodeImage
import kotlinx.coroutines.delay

/**
 * Share screen: enables phone-to-phone node sharing.
 * Stops bitcoind, starts HTTP server, shows QR code for receiver.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onBack: () -> Unit,
    onStopNode: () -> Unit,
    chainHeight: Int = 0
) {
    val context = LocalContext.current
    val shareServer = remember { ShareServer(context) }
    val isRunning by ShareServer.isRunningFlow.collectAsState()
    val activeTransfers by ShareServer.activeTransfersFlow.collectAsState()
    val completedFiles by ShareServer.completedTransfersFlow.collectAsState()
    var serverIp by remember { mutableStateOf<String?>(null) }
    var nodeStopped by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }

    // Check for block filters
    val hasFilters = remember {
        val filterDir = context.filesDir.resolve("bitcoin/indexes/blockfilter/basic")
        filterDir.exists() && (filterDir.listFiles()?.size ?: 0) > 1
    }

    // Total data size estimate
    val dataSize = remember {
        var size = 0L
        val bitcoinDir = context.filesDir.resolve("bitcoin")
        // Chainstate
        bitcoinDir.resolve("chainstate").walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        // Block index
        bitcoinDir.resolve("blocks/index").walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        // Tip blocks
        bitcoinDir.resolve("blocks").listFiles()?.filter { 
            it.name.matches(Regex("(blk|rev)\\d+\\.dat")) && it.length() > 0 
        }?.forEach { size += it.length() }
        // xor.dat
        val xor = bitcoinDir.resolve("blocks/xor.dat")
        if (xor.exists()) size += xor.length()
        // Filters
        if (hasFilters) {
            bitcoinDir.resolve("indexes/blockfilter/basic").walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        }
        size
    }

    DisposableEffect(Unit) {
        onDispose {
            shareServer.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share The Freedom") },
                navigationIcon = {
                    IconButton(onClick = {
                        shareServer.stop()
                        onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isRunning) {
                // Not yet sharing
                Text(
                    "Share your validated node with nearby phones",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("What gets shared:", fontWeight = FontWeight.Bold)
                        Text("• Chainstate (validated UTXO set)")
                        Text("• Block index + tip blocks")
                        if (hasFilters) Text("• Block filters (Neutrino wallets)")
                        Text("• Pocket Node APK")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Total: ~${"%.1f".format(dataSize / (1024.0 * 1024 * 1024))} GB",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How it works:", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("1. Node stops temporarily (can't sync and share at once)", color = Color.White.copy(alpha = 0.8f))
                        Text("2. Other phones scan your QR code", color = Color.White.copy(alpha = 0.8f))
                        Text("3. They download your chainstate over WiFi/hotspot", color = Color.White.copy(alpha = 0.8f))
                        Text("4. Full node in ~20 minutes per phone", color = Color.White.copy(alpha = 0.8f))
                        Text("5. Up to 2 phones at once", color = Color.White.copy(alpha = 0.8f))
                    }
                }

                Button(
                    onClick = {
                        stopping = true
                        onStopNode()
                        // Give time for node to stop
                        nodeStopped = true
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !stopping,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text(
                        if (stopping) "Stopping node..." else "Stop Node & Start Sharing",
                        fontWeight = FontWeight.Bold
                    )
                }

                // Auto-start server after node stops
                if (nodeStopped && !isRunning) {
                    LaunchedEffect(Unit) {
                        delay(3000) // Wait for bitcoind to fully stop
                        serverIp = shareServer.getLocalIpAddress()
                        shareServer.start()
                    }
                }

            } else {
                // Server running - show QR code
                val qrData = remember(serverIp) {
                    "http://${serverIp ?: "unknown"}:${ShareServer.PORT}"
                }

                Text(
                    "📡 Sharing Node",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Session progress (always visible, right below header)
                val sessionTotal by ShareServer.sessionTotalFilesFlow.collectAsState()
                val sessionCompleted by ShareServer.sessionFilesCompletedFlow.collectAsState()
                if (sessionTotal > 0) {
                    val pct = (sessionCompleted * 100) / sessionTotal
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(if (sessionCompleted >= sessionTotal) "✅ Freedom shared!" else "📡 Freedom uploading: $pct%",
                                fontWeight = FontWeight.Bold, color = Color.White)
                            Text("$sessionCompleted / $sessionTotal files", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { sessionCompleted.toFloat() / sessionTotal },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF4CAF50),
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                        }
                    }
                }

                // QR Code
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        QrCodeImage(data = qrData, size = 280)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Scan with any camera or QR reader",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                // Connection info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Manual connection:", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${serverIp ?: "..."}:${ShareServer.PORT}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Block height: ${"$chainHeight".let { "%,d".format(chainHeight) }}", 
                            style = MaterialTheme.typography.bodySmall)
                        if (hasFilters) {
                            Text("Lightning block filters included", 
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50))
                        }
                    }
                }

                // Active transfers
                if (activeTransfers.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "${activeTransfers.size} active transfer${if (activeTransfers.size > 1) "s" else ""}",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(8.dp))
                            activeTransfers.values.forEachIndexed { i, transfer ->
                                val percent = if (transfer.totalBytes > 0) 
                                    ((transfer.bytesServed * 100) / transfer.totalBytes).toInt() else 0
                                Text(
                                    "Transfer ${i + 1}: ${transfer.currentFile.split("/").last()} ($percent%)",
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else if (completedFiles > 0) {
                    // No active transfers but some files were served
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "✅ $completedFiles file${if (completedFiles != 1) "s" else ""} transferred",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Waiting for more connections, or stop sharing if done.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    Text(
                        "Waiting for connections...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Stop sharing
                OutlinedButton(
                    onClick = {
                        shareServer.stop()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Sharing")
                }
            }
        }
    }
}
