package com.pocketnode.ui

import android.content.Intent
import android.os.Build
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
import androidx.compose.ui.unit.dp
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.service.BitcoindService
import com.pocketnode.snapshot.ChainstateManager
import com.pocketnode.snapshot.SnapshotDownloader
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class DownloadStep { NOT_STARTED, DOWNLOADING, VALIDATING, PREPARING, LOADING, COMPLETE, ERROR }

/**
 * Screen for downloading a UTXO snapshot from the internet (utxo.download)
 * and loading it via AssumeUTXO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternetDownloadScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current

    var currentStep by remember { mutableStateOf(DownloadStep.NOT_STARTED) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedGB by remember { mutableStateOf(0.0) }
    var totalGB by remember { mutableStateOf(0.0) }
    var speedMBs by remember { mutableStateOf(0.0) }
    var loadElapsedMin by remember { mutableStateOf(0L) }
    var isRunning by remember { mutableStateOf(false) }

    val snapshotUrl = "https://utxo.download/utxo-910000.dat"
    val downloader = remember { SnapshotDownloader(context) }

    // Check if snapshot already exists on phone
    LaunchedEffect(Unit) {
        val snapshotFile = File(context.filesDir, "bitcoin/utxo-snapshot.dat")
        val chainstateSnapshotDir = File(context.filesDir, "bitcoin/chainstate_snapshot")
        if (snapshotFile.exists() && snapshotFile.length() > 1_000_000_000) {
            currentStep = DownloadStep.COMPLETE
            statusMessage = "Snapshot already on phone (${snapshotFile.length() / (1024 * 1024 * 1024)} GB)."
        } else if (chainstateSnapshotDir.exists() && (chainstateSnapshotDir.listFiles()?.size ?: 0) > 2) {
            currentStep = DownloadStep.COMPLETE
            statusMessage = "Snapshot already loaded! Node is running."
        }
    }

    // Collect download progress
    val dlProgress by downloader.progress.collectAsState()

    LaunchedEffect(dlProgress) {
        if (currentStep == DownloadStep.DOWNLOADING) {
            downloadProgress = dlProgress.progressFraction
            downloadedGB = dlProgress.bytesDownloaded / (1024.0 * 1024 * 1024)
            totalGB = dlProgress.totalBytes / (1024.0 * 1024 * 1024)
            speedMBs = dlProgress.speedBytesPerSec / (1024.0 * 1024)
            if (dlProgress.isComplete) {
                // Move to validation
                currentStep = DownloadStep.VALIDATING
            }
            if (dlProgress.state == SnapshotDownloader.DownloadState.ERROR) {
                currentStep = DownloadStep.ERROR
                errorMessage = dlProgress.error ?: "Download failed"
            }
        }
    }

    fun startFlow() {
        currentStep = DownloadStep.DOWNLOADING
        statusMessage = "Connecting to utxo.download..."
        isRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            // Step 1: Download
            val file = downloader.download(snapshotUrl)
            if (file == null) {
                if (currentStep != DownloadStep.ERROR) {
                    currentStep = DownloadStep.ERROR
                    errorMessage = "Download failed"
                }
                isRunning = false
                return@launch
            }

            // Step 2: Validate
            currentStep = DownloadStep.VALIDATING
            statusMessage = "Validating snapshot..."
            val hash = ChainstateManager.readSnapshotBlockHash(file)
            if (hash != ChainstateManager.EXPECTED_BLOCK_HASH) {
                currentStep = DownloadStep.ERROR
                errorMessage = "Invalid snapshot — block hash doesn't match height 910,000"
                file.delete()
                isRunning = false
                return@launch
            }
            statusMessage = "Snapshot verified ✓"
            delay(1000)

            // Step 3: Prepare node
            currentStep = DownloadStep.PREPARING
            statusMessage = "Starting node..."

            // Check if already loaded
            val chainstateSnapshotDir = File(context.filesDir, "bitcoin/chainstate_snapshot")
            if (chainstateSnapshotDir.exists() && (chainstateSnapshotDir.listFiles()?.size ?: 0) > 2) {
                ConfigGenerator.ensureConfig(context)
                val creds = ConfigGenerator.readCredentials(context)
                if (creds != null) {
                    val rpc = BitcoinRpcClient(creds.first, creds.second)
                    for (i in 0 until 15) {
                        val check = rpc.call("getblockchaininfo", connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
                        if (check != null && !check.optBoolean("_rpc_error", false)) {
                            // Already loaded!
                            file.delete()
                            context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("node_was_running", true).apply()
                            currentStep = DownloadStep.COMPLETE
                            statusMessage = "Snapshot already loaded! Node is running."
                            isRunning = false
                            return@launch
                        }
                        delay(2000)
                    }
                }
            }

            // Start bitcoind if not running
            if (!BitcoindService.isRunningFlow.value) {
                val intent = Intent(context, BitcoindService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }

            // Wait for RPC
            ConfigGenerator.ensureConfig(context)
            val creds = ConfigGenerator.readCredentials(context)
            if (creds == null) {
                currentStep = DownloadStep.ERROR
                errorMessage = "No RPC credentials"
                isRunning = false
                return@launch
            }
            val rpc = BitcoinRpcClient(creds.first, creds.second)
            var rpcReady = false
            val rpcDeadline = System.currentTimeMillis() + 360_000
            while (System.currentTimeMillis() < rpcDeadline) {
                statusMessage = "Waiting for node to respond..."
                try {
                    val info = rpc.call("getnetworkinfo", connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
                    if (info != null) { rpcReady = true; break }
                } catch (_: Exception) {}
                delay(2000)
            }
            if (!rpcReady) {
                currentStep = DownloadStep.ERROR
                errorMessage = "Node didn't respond — check logs"
                isRunning = false
                return@launch
            }

            // Wait for warmup
            val warmupDeadline = System.currentTimeMillis() + 600_000
            while (System.currentTimeMillis() < warmupDeadline) {
                val check = rpc.call("getblockchaininfo", connectTimeoutMs = 2_000, readTimeoutMs = 10_000)
                if (check != null && !check.optBoolean("_rpc_error", false)) break
                val msg = check?.optString("message", "Loading...") ?: "Loading..."
                statusMessage = "Node warming up: $msg"
                delay(2000)
            }

            // Step 4: Load snapshot
            currentStep = DownloadStep.LOADING
            statusMessage = "Loading UTXO snapshot..."
            val loadStartTime = System.currentTimeMillis()

            // Fire loadtxoutset in background
            val loadJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    rpc.loadTxOutset(file.absolutePath)
                } catch (_: Exception) {}
            }

            // Poll for completion
            val dataDir = File(context.filesDir, "bitcoin")
            val snapshotDir = dataDir.resolve("chainstate_snapshot")
            val loadTimeout = System.currentTimeMillis() + 3_600_000
            var loadComplete = false

            while (System.currentTimeMillis() < loadTimeout) {
                loadElapsedMin = (System.currentTimeMillis() - loadStartTime) / 60_000
                val remainingMin = (25 - loadElapsedMin).coerceAtLeast(0)
                statusMessage = "Loading UTXO snapshot... ${loadElapsedMin}min elapsed, ~${remainingMin}min remaining"

                if (snapshotDir.exists() && (snapshotDir.listFiles()?.size ?: 0) > 2) {
                    val check = rpc.call("getblockchaininfo", connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
                    if (check != null && !check.optBoolean("_rpc_error", false)) {
                        loadComplete = true
                        break
                    }
                }
                delay(3000)
            }

            loadJob.cancel()

            if (!loadComplete) {
                val finalCheck = snapshotDir.exists() && (snapshotDir.listFiles()?.size ?: 0) > 2
                if (!finalCheck) {
                    currentStep = DownloadStep.ERROR
                    errorMessage = "Snapshot load timed out"
                    isRunning = false
                    return@launch
                }
            }

            // Success
            file.delete()
            context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("node_was_running", true).apply()
            currentStep = DownloadStep.COMPLETE
            statusMessage = "UTXO snapshot loaded! Node syncing from block 910,000."
            isRunning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download Snapshot") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Text(
                "Download from Internet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Step indicators
            StepIndicator("Download snapshot (9 GB)", currentStep, DownloadStep.DOWNLOADING)
            StepIndicator("Validate block hash", currentStep, DownloadStep.VALIDATING)
            StepIndicator("Prepare node", currentStep, DownloadStep.PREPARING)
            StepIndicator("Load snapshot (~25 min)", currentStep, DownloadStep.LOADING)

            Spacer(Modifier.height(8.dp))

            // Progress details
            when (currentStep) {
                DownloadStep.NOT_STARTED -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Source: utxo.download", fontWeight = FontWeight.Bold)
                            Text(
                                "File: utxo-910000.dat (~9 GB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // Safety profile
                    var safetyExpanded by remember {
                        mutableStateOf(
                            context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                .getBoolean("safety_info_expanded", true)
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Safety Profile", fontWeight = FontWeight.Bold)
                                TextButton(onClick = {
                                    safetyExpanded = !safetyExpanded
                                    context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("safety_info_expanded", safetyExpanded).apply()
                                }) {
                                    Text(if (safetyExpanded) "Hide" else "Show")
                                }
                            }
                            if (safetyExpanded) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "This download is trustless — you don't need to trust the server.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF4CAF50)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Bitcoin Core has the expected block hash for height 910,000 " +
                                    "hardcoded in its source code. Before loading, the app reads " +
                                    "the block hash from the snapshot file header and compares it " +
                                    "against the expected value. A tampered or wrong-height snapshot " +
                                    "is rejected before it touches your node.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "After loading, Bitcoin Core independently validates every " +
                                    "transaction back to the genesis block in the background. " +
                                    "This means even if a snapshot somehow passed the hash check, " +
                                    "any invalid state would be detected and rejected during " +
                                    "background validation. The same security as syncing from scratch.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "The source (utxo.download) is a well-known public snapshot " +
                                    "host used by the Bitcoin community. But even if you downloaded " +
                                    "from an untrusted source, the cryptographic verification means " +
                                    "you'd get the same result — bad data is always rejected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                DownloadStep.DOWNLOADING -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            LinearProgressIndicator(
                                progress = downloadProgress,
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${"%.1f".format(downloadedGB)} / ${"%.1f".format(totalGB)} GB",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "${"%.1f".format(speedMBs)} MB/s",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (speedMBs > 0 && totalGB > downloadedGB) {
                                val remainingGB = totalGB - downloadedGB
                                val etaSec = (remainingGB * 1024 / speedMBs).toLong()
                                val etaMin = etaSec / 60
                                Text(
                                    "ETA: ${etaMin}min remaining",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                DownloadStep.VALIDATING, DownloadStep.PREPARING -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                DownloadStep.LOADING -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                DownloadStep.COMPLETE -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f))
                    ) {
                        Text(
                            statusMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
                DownloadStep.ERROR -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            errorMessage,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Action button
            when (currentStep) {
                DownloadStep.NOT_STARTED -> {
                    Button(
                        onClick = { startFlow() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("Start Download", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                DownloadStep.COMPLETE -> {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("Go to Dashboard", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                DownloadStep.ERROR -> {
                    OutlinedButton(
                        onClick = {
                            downloader.reset()
                            currentStep = DownloadStep.NOT_STARTED
                            errorMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Retry")
                    }
                }
                else -> {} // In progress — no button
            }
        }
    }
}

@Composable
private fun StepIndicator(
    label: String,
    currentStep: Any,
    thisStep: Any
) {
    // Determine state based on enum ordinal comparison
    val steps = listOf("NOT_STARTED", "DOWNLOADING", "VALIDATING", "PREPARING", "LOADING", "COMPLETE", "ERROR")
    val currentOrd = steps.indexOf(currentStep.toString())
    val thisOrd = steps.indexOf(thisStep.toString())

    val (icon, color) = when {
        currentStep.toString() == "ERROR" && thisOrd <= currentOrd -> "❌" to Color(0xFFE53935)
        currentOrd > thisOrd -> "✅" to Color(0xFF4CAF50)
        currentOrd == thisOrd -> "⏳" to Color(0xFFFF9800)
        else -> "○" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    Text(
        "$icon  $label",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (currentOrd == thisOrd) FontWeight.Bold else FontWeight.Normal,
        color = color
    )
}
