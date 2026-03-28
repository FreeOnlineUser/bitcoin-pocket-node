package com.pocketnode.ui

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
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pocketnode.service.BitcoindService
import com.pocketnode.share.ShareClient
import com.pocketnode.share.ShareServer
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Receiver screen: connect to a nearby phone sharing its node.
 * Supports QR scan or manual IP entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyNodeScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onScanQr: ((String) -> Unit) -> Unit = {},
    initialHost: String? = null,
    initialPort: Int? = null,
    scannedQrData: String? = null,
    onConsumeQr: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ShareClient(context) }
    val downloadState by client.stateFlow.collectAsState()

    var host by remember { mutableStateOf(initialHost ?: "") }
    var port by remember { mutableIntStateOf(initialPort ?: ShareServer.PORT) }
    var serverInfo by remember { mutableStateOf<ShareClient.ShareInfo?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var includeFilters by remember { mutableStateOf(true) }

    // Process QR scan result from navigation
    LaunchedEffect(scannedQrData) {
        if (scannedQrData != null) {
            onConsumeQr()
            try {
                val json = org.json.JSONObject(scannedQrData)
                host = json.getString("host")
                port = json.optInt("port", ShareServer.PORT)
            } catch (_: Exception) {
                try {
                    val url = java.net.URL(scannedQrData)
                    host = url.host
                    port = if (url.port > 0) url.port else ShareServer.PORT
                } catch (_: Exception) {
                    val parts = scannedQrData.split(":")
                    if (parts.size >= 2) {
                        host = parts[0]
                        port = parts[1].toIntOrNull() ?: ShareServer.PORT
                    }
                }
            }
            if (host.isNotEmpty()) {
                serverInfo = client.getInfo(host, port)
                if (serverInfo == null) error = "Could not connect to $host:$port"
            }
        }
    }

    // Auto-connect if opened via deep link with host
    LaunchedEffect(initialHost) {
        if (!initialHost.isNullOrEmpty() && serverInfo == null && !connecting) {
            connecting = true
            error = null
            try {
                serverInfo = client.getInfo(host, port)
                if (serverInfo == null) error = "Could not connect to $host:$port"
            } catch (e: Exception) {
                error = e.message ?: "Connection failed"
            } finally {
                connecting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby Node") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !downloading) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (downloading) {
                // Download in progress
                Text(
                    "Downloading from nearby node",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(downloadState.phase, fontWeight = FontWeight.Bold)

                        if (downloadState.totalFiles > 0) {
                            Text(
                                "File ${downloadState.filesCompleted + 1} of ${downloadState.totalFiles}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (downloadState.currentFile.isNotEmpty()) {
                            Text(
                                downloadState.currentFile.split("/").last(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        // Overall progress bar
                        LinearProgressIndicator(
                            progress = { downloadState.totalProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFFFF9800)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${downloadState.totalProgress}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${"%.1f".format(downloadState.bytesDownloaded / (1024.0 * 1024 * 1024))} / ${"%.1f".format(downloadState.totalBytes / (1024.0 * 1024 * 1024))} GB",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (downloadState.phase == "Download complete") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "✅ All ${downloadState.totalFiles} files received. You can disconnect from the other phone now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50),
                        textAlign = TextAlign.Center
                    )

                    var postStatus by remember { mutableStateOf("Creating block file stubs...") }
                    var postDone by remember { mutableStateOf(false) }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        postStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    if (!postDone) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color(0xFFFF9800)
                        )
                    }

                    LaunchedEffect(Unit) {
                        withContext(Dispatchers.IO) {
                            try {
                                val bitcoinDir = context.filesDir.resolve("bitcoin")
                                val blocksDir = bitcoinDir.resolve("blocks")
                                blocksDir.mkdirs()

                                // Find highest blk file number from downloaded files
                                val lastBlkNum = blocksDir.listFiles()
                                    ?.filter { it.name.matches(Regex("blk\\d+\\.dat")) }
                                    ?.mapNotNull { it.name.removePrefix("blk").removeSuffix(".dat").toIntOrNull() }
                                    ?.maxOrNull() ?: 0

                                // Create empty stubs for all missing blk/rev files
                                var stubCount = 0
                                for (i in 0..lastBlkNum) {
                                    val blk = blocksDir.resolve("blk%05d.dat".format(i))
                                    val rev = blocksDir.resolve("rev%05d.dat".format(i))
                                    if (!blk.exists()) { blk.createNewFile(); stubCount++ }
                                    if (!rev.exists()) { rev.createNewFile(); stubCount++ }
                                    if (stubCount > 0 && stubCount % 1000 == 0) {
                                        postStatus = "Creating stubs: $stubCount files..."
                                    }
                                }
                                Log.i("NearbyNode", "Created $stubCount stub files (0 to $lastBlkNum)")

                                // Configure bitcoin.conf
                                postStatus = "Configuring node..."
                                ConfigGenerator.ensureConfig(context)
                                val confFile = bitcoinDir.resolve("bitcoin.conf")
                                if (confFile.exists()) {
                                    var confText = confFile.readText()
                                    if (!confText.contains("checklevel=")) {
                                        confText += "\nchecklevel=0\n"
                                    }
                                    // Block filters config
                                    val filtersExist = bitcoinDir.resolve("indexes/blockfilter/basic/db").exists()
                                    if (includeFilters && filtersExist) {
                                        if (!confText.contains("blockfilterindex=")) {
                                            confText += "\n# Lightning support (BIP 157/158 block filters)\nblockfilterindex=1\npeerblockfilters=1\n"
                                        }
                                        if (confText.contains("listen=0")) {
                                            confText = confText.replace("listen=0", "listen=1")
                                            if (!confText.contains("bind=")) {
                                                confText = confText.replace("listen=1", "listen=1\nbind=127.0.0.1")
                                            }
                                        }
                                    }
                                    confFile.writeText(confText)
                                }

                                // Save prefs
                                context.getSharedPreferences("pocketnode_prefs", 0).edit()
                                    .putBoolean("chainstate_from_nearby", true)
                                    .putBoolean("archive_has_filters", includeFilters && serverInfo?.hasFilters == true)
                                    .putBoolean("node_was_running", true)
                                    .apply()

                                // Mark setup done
                                context.getSharedPreferences("node_setup", 0).edit()
                                    .putBoolean("setup_done", true)
                                    .apply()

                                // Start bitcoind
                                postStatus = "Starting node..."
                                withContext(Dispatchers.Main) {
                                    val intent = Intent(context, BitcoindService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                }

                                // Wait briefly for service to start
                                delay(2000)
                                postStatus = "Node started! ✓"
                                postDone = true

                                delay(1500)
                            } catch (e: Exception) {
                                Log.e("NearbyNode", "Post-download setup failed", e)
                                postStatus = "Error: ${e.message}"
                                postDone = true
                            }
                        }
                        if (postDone) onComplete()
                    }
                }

            } else if (serverInfo != null) {
                // Connected - show server info and download button
                Text(
                    "Node Found",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connected to $host", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Block height: ${"%,d".format(serverInfo!!.chainHeight)}", color = Color.White.copy(alpha = 0.9f))
                        Text("App version: ${serverInfo!!.version}", color = Color.White.copy(alpha = 0.9f))
                        if (serverInfo!!.hasFilters) {
                            Text("Lightning block filters available ⚡", color = Color(0xFF81C784))
                        }
                        Text(
                            "${serverInfo!!.activeTransfers}/${serverInfo!!.maxConcurrent} transfers active",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                if (serverInfo!!.hasFilters) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Include block filters", fontWeight = FontWeight.Medium)
                            Text(
                                "Required for Lightning. Adds ~12 GB to download.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = includeFilters,
                            onCheckedChange = { includeFilters = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFF9800))
                        )
                    }
                }

                Button(
                    onClick = {
                        downloading = true
                        scope.launch {
                            // Stop Lightning and bitcoind before downloading (safe state)
                            try {
                                val ls = com.pocketnode.lightning.LightningService.getInstance(context)
                                val wasRunning = com.pocketnode.lightning.LightningService.stateFlow.value.status ==
                                    com.pocketnode.lightning.LightningService.LightningState.Status.RUNNING
                                if (wasRunning) {
                                    ls.stop()
                                    context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("lightning_was_running", true).apply()
                                }
                            } catch (_: Exception) {}
                            try {
                                context.stopService(android.content.Intent(context, com.pocketnode.service.BitcoindService::class.java))
                                kotlinx.coroutines.delay(3000) // let services shut down
                            } catch (_: Exception) {}

                            // Merge peer channel limits (lightweight, runs first)
                            client.fetchPeerLimits(host, port)
                            val success = client.downloadChainstate(host, port, includeFilters)
                            if (success) {
                                // Restart bitcoind (Lightning auto-starts if flag set)
                                try {
                                    context.startForegroundService(
                                        android.content.Intent(context, com.pocketnode.service.BitcoindService::class.java))
                                } catch (_: Exception) {}
                            } else {
                                downloading = false
                                error = "Download failed. Check connection and try again."
                                // Restart node even on failure
                                try {
                                    context.startForegroundService(
                                        android.content.Intent(context, com.pocketnode.service.BitcoindService::class.java))
                                } catch (_: Exception) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Start Download", fontWeight = FontWeight.Bold)
                }

            } else {
                // Not connected - show scan/manual entry
                Text(
                    "Connect to a nearby node",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Ask the sender to open \"Share The Freedom\" and scan their QR code, or enter the IP address manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Scan QR button
                Button(
                    onClick = {
                        onScanQr { qrData ->
                            try {
                                // Try JSON format first: {"host": "...", "port": ...}
                                val json = JSONObject(qrData)
                                host = json.getString("host")
                                port = json.optInt("port", ShareServer.PORT)
                            } catch (_: Exception) {
                                try {
                                    // Try URL format: http://10.0.1.X:8432
                                    val url = java.net.URL(qrData)
                                    host = url.host
                                    port = if (url.port > 0) url.port else ShareServer.PORT
                                } catch (_: Exception) {
                                    // Try plain host:port
                                    val parts = qrData.split(":")
                                    if (parts.size >= 2) {
                                        host = parts[0]
                                        port = parts[1].toIntOrNull() ?: ShareServer.PORT
                                    } else {
                                        error = "Invalid QR code"
                                        return@onScanQr
                                    }
                                }
                            }
                            // Auto-connect after scan
                            connecting = true
                            scope.launch {
                                serverInfo = client.getInfo(host, port)
                                connecting = false
                                if (serverInfo == null) {
                                    error = "Could not connect to $host:$port"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    enabled = !connecting
                ) {
                    Text("Scan QR Code", fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Or enter manually:", style = MaterialTheme.typography.bodyMedium)

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; error = null },
                    label = { Text("IP Address") },
                    placeholder = { Text("192.168.43.1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        connecting = true
                        error = null
                        scope.launch {
                            serverInfo = client.getInfo(host, port)
                            connecting = false
                            if (serverInfo == null) {
                                error = "Could not connect to $host:$port. Make sure you're on the same network."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = host.isNotBlank() && !connecting
                ) {
                    Text(if (connecting) "Connecting..." else "Connect")
                }
            }

            // Error display
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        error!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
