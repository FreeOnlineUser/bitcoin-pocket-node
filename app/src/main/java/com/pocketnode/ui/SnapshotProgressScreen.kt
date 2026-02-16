package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.snapshot.SnapshotDownloader
import com.pocketnode.snapshot.SnapshotManager
import kotlinx.coroutines.launch

/**
 * Progress screen for the full snapshot flow:
 * dump → download → load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotProgressScreen(
    host: String,
    port: Int,
    rpcUser: String,
    rpcPassword: String,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { SnapshotManager(context) }
    val flowState by manager.state.collectAsState()
    val downloadProgress by manager.downloader.progress.collectAsState()

    // Download URL state — user provides this after dump
    var downloadUrl by remember { mutableStateOf("http://$host:8080/utxo-snapshot.dat") }
    var showDownloadUrlDialog by remember { mutableStateOf(false) }

    // Auto-connect on first composition
    LaunchedEffect(Unit) {
        manager.connectToNode(host, port, rpcUser, rpcPassword)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snapshot Transfer") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Step 1: Connection
            StepCard(
                stepNumber = 1,
                title = "Connect to Node",
                subtitle = "$host:$port",
                state = when (flowState.step) {
                    SnapshotManager.Step.CONNECTING -> StepState.IN_PROGRESS
                    SnapshotManager.Step.NOT_STARTED -> StepState.PENDING
                    else -> if (flowState.remoteConnected) StepState.COMPLETE else StepState.PENDING
                },
                detail = if (flowState.remoteConnected)
                    "${flowState.remoteChain} chain · block ${flowState.remoteBlocks}"
                else null
            )

            // Step 2: Generate snapshot
            StepCard(
                stepNumber = 2,
                title = "Generate Snapshot",
                subtitle = "Run dumptxoutset on remote node",
                state = when (flowState.step) {
                    SnapshotManager.Step.DUMPING -> StepState.IN_PROGRESS
                    SnapshotManager.Step.DUMP_COMPLETE, SnapshotManager.Step.DOWNLOADING,
                    SnapshotManager.Step.DOWNLOAD_COMPLETE, SnapshotManager.Step.LOADING,
                    SnapshotManager.Step.COMPLETE -> StepState.COMPLETE
                    else -> StepState.PENDING
                },
                detail = when (flowState.step) {
                    SnapshotManager.Step.DUMPING -> "This may take 10-60 minutes..."
                    else -> null
                }
            ) {
                if (flowState.step == SnapshotManager.Step.CONNECTED) {
                    Button(
                        onClick = { scope.launch { manager.triggerDump() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Snapshot Generation")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Or skip if you already have a snapshot file served over HTTP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showDownloadUrlDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip — I have a snapshot URL")
                    }
                }
            }

            // Step 3: Download
            StepCard(
                stepNumber = 3,
                title = "Download Snapshot",
                subtitle = "Transfer to phone over LAN",
                state = when (flowState.step) {
                    SnapshotManager.Step.DOWNLOADING -> StepState.IN_PROGRESS
                    SnapshotManager.Step.DOWNLOAD_COMPLETE, SnapshotManager.Step.LOADING,
                    SnapshotManager.Step.COMPLETE -> StepState.COMPLETE
                    else -> StepState.PENDING
                },
                detail = when (downloadProgress.state) {
                    SnapshotDownloader.DownloadState.DOWNLOADING -> {
                        val mb = downloadProgress.bytesDownloaded / (1024 * 1024)
                        val totalMb = if (downloadProgress.totalBytes > 0) downloadProgress.totalBytes / (1024 * 1024) else 0
                        val speed = downloadProgress.speedBytesPerSec / (1024 * 1024)
                        "${mb}MB / ${totalMb}MB · ${speed} MB/s"
                    }
                    else -> null
                }
            ) {
                if (downloadProgress.state == SnapshotDownloader.DownloadState.DOWNLOADING &&
                    downloadProgress.totalBytes > 0
                ) {
                    LinearProgressIndicator(
                        progress = downloadProgress.progressFraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (downloadProgress.state == SnapshotDownloader.DownloadState.DOWNLOADING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                }

                if (flowState.step == SnapshotManager.Step.DUMP_COMPLETE) {
                    Text(
                        "Snapshot ready on remote node. Now serve it over HTTP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("On your node, run:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "cd ~/.bitcoin && python3 -m http.server 8080",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text("Snapshot URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { manager.downloadSnapshot(downloadUrl) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Snapshot")
                    }
                }
            }

            // Step 4: Load
            StepCard(
                stepNumber = 4,
                title = "Load Snapshot",
                subtitle = "Import into local bitcoind",
                state = when (flowState.step) {
                    SnapshotManager.Step.LOADING -> StepState.IN_PROGRESS
                    SnapshotManager.Step.COMPLETE -> StepState.COMPLETE
                    else -> StepState.PENDING
                }
            ) {
                if (flowState.step == SnapshotManager.Step.DOWNLOAD_COMPLETE) {
                    Text(
                        "Snapshot downloaded. Make sure local bitcoind is running, then load.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { manager.loadSnapshot() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Load into Bitcoind")
                    }
                }

                if (flowState.step == SnapshotManager.Step.COMPLETE) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done — Return to Dashboard")
                    }
                }
            }

            // Error display
            if (flowState.step == SnapshotManager.Step.ERROR && flowState.error != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Error",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            flowState.error!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // Download URL dialog (when skipping dump)
    if (showDownloadUrlDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadUrlDialog = false },
            title = { Text("Snapshot URL") },
            text = {
                Column {
                    Text("Enter the HTTP URL of the snapshot file:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text("URL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadUrlDialog = false
                    scope.launch { manager.downloadSnapshot(downloadUrl) }
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadUrlDialog = false }) { Text("Cancel") }
            }
        )
    }
}

enum class StepState { PENDING, IN_PROGRESS, COMPLETE }

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    subtitle: String,
    state: StepState,
    detail: String? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                StepState.COMPLETE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                StepState.IN_PROGRESS -> MaterialTheme.colorScheme.surfaceVariant
                StepState.PENDING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    StepState.COMPLETE -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Complete",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    StepState.IN_PROGRESS -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    StepState.PENDING -> Text(
                        "$stepNumber",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}
