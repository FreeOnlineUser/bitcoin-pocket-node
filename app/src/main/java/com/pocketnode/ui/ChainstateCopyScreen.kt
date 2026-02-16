package com.pocketnode.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.snapshot.ChainstateManager
import com.pocketnode.snapshot.NodeSetupManager
import com.pocketnode.ui.components.AdminCredentialsDialog
import kotlinx.coroutines.launch

/**
 * Screen that manages the chainstate copy flow from a remote node.
 * Shows progress through each step: stop → copy → restart → download → extract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainstateCopyScreen(onBack: () -> Unit, onComplete: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Use a scope that survives recomposition for long-running work
    val workScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()) }
    val setupManager = remember { NodeSetupManager(context) }
    val chainstateManager = remember { ChainstateManager.getInstance(context) }
    val state by chainstateManager.state.collectAsState()

    var started by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(true) }
    var showAdminCreds by remember { mutableStateOf(false) }
    // Track the highest step reached for proper tick/cross display
    var highestStepReached by remember { mutableIntStateOf(0) }

    // Update highest step reached
    LaunchedEffect(state.step) {
        if (state.step.ordinal > highestStepReached && state.step != ChainstateManager.Step.ERROR) {
            highestStepReached = state.step.ordinal
        }
    }

    // Function to start with SFTP-only (no admin creds)
    fun startWithoutAdmin() {
        showConfirm = false
        started = true
        highestStepReached = 0
        workScope.launch {
            val host = setupManager.getSavedHost()
            val port = setupManager.getSavedPort()
            val user = setupManager.getSavedUser()
            val pass = setupManager.getSavedPassword()
            chainstateManager.copyChainstate(
                sshHost = host,
                sshPort = port,
                sshUser = "", // No admin
                sshPassword = "",
                sftpUser = user,
                sftpPassword = pass
            )
        }
    }

    if (showAdminCreds) {
        AdminCredentialsDialog(
            title = "Admin SSH — Generate Snapshot",
            defaultHost = setupManager.getSavedHost(),
            defaultPort = setupManager.getSavedPort(),
            defaultUsername = setupManager.getSavedAdminUser(),
            onDismiss = { showAdminCreds = false },
            onConfirm = { creds ->
                showAdminCreds = false
                showConfirm = false
                started = true
                highestStepReached = 0
                workScope.launch {
                    val user = setupManager.getSavedUser()
                    val pass = setupManager.getSavedPassword()
                    chainstateManager.copyChainstate(
                        sshHost = creds.host,
                        sshPort = creds.port,
                        sshUser = creds.username,
                        sshPassword = creds.password,
                        sftpUser = user,
                        sftpPassword = pass
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync from Node") },
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showConfirm && !started) {
                // Confirmation screen
                Text(
                    "Sync from Your Node",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Direct chainstate copy",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Copies the chainstate, block index, and tip blocks directly from your node. " +
                            "Your phone starts at chain tip immediately — no background validation, " +
                            "no AssumeUTXO, no waiting. ~20 minutes total over LAN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("How it works:", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("• Briefly stops your node for a consistent snapshot (~30s)", style = MaterialTheme.typography.bodySmall)
                        Text("• Archives chainstate + block index (~12 GB)", style = MaterialTheme.typography.bodySmall)
                        Text("• Restarts your node immediately", style = MaterialTheme.typography.bodySmall)
                        Text("• Downloads archive over LAN (~5 min)", style = MaterialTheme.typography.bodySmall)
                        Text("• Deploys and starts — at chain tip instantly", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Your node is only stopped for ~30 seconds during archiving.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text("Subsequent runs reuse the existing archive.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        // Check if chainstate archive already on phone or on node via SFTP
                        workScope.launch {
                            val dataDir = java.io.File(context.filesDir, "bitcoin")
                            val localArchive = java.io.File(dataDir, "node-sync.tar")
                            if (localArchive.exists() && localArchive.length() > 1_000_000_000) {
                                // Already on phone — skip archive step
                                startWithoutAdmin()
                            } else {
                                // Check if archive exists on node via SFTP
                                val host = setupManager.getSavedHost()
                                val user = setupManager.getSavedUser()
                                val pass = setupManager.getSavedPassword()
                                if (host.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()) {
                                    val exists = chainstateManager.checkArchiveExists(host, setupManager.getSavedPort(), user, pass)
                                    if (exists) {
                                        startWithoutAdmin()
                                    } else {
                                        showAdminCreds = true
                                    }
                                } else {
                                    showAdminCreds = true
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Start", fontWeight = FontWeight.Bold)
                }

            } else {
                // Progress view
                Text(
                    "Syncing from Node",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Step indicators
                StepRow("Stop remote node", state.step, ChainstateManager.Step.STOPPING_REMOTE, highestStepReached)
                StepRow("Archive node data", state.step, ChainstateManager.Step.ARCHIVING, highestStepReached)
                StepRow("Download to phone", state.step, ChainstateManager.Step.DOWNLOADING, highestStepReached)
                StepRow("Deploy chainstate", state.step, ChainstateManager.Step.DEPLOYING, highestStepReached)
                StepRow("Start node", state.step, ChainstateManager.Step.STARTING_NODE, highestStepReached)

                Spacer(Modifier.height(12.dp))

                // Progress bar (generation or download)
                val showProgressBar = (state.step == ChainstateManager.Step.DOWNLOADING && state.totalBytes > 0) ||
                        (state.step == ChainstateManager.Step.ARCHIVING && state.downloadProgress > 0)
                if (showProgressBar) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.downloadProgress,
                        animationSpec = tween(500),
                        label = "downloadProgress"
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            LinearProgressIndicator(
                                progress = animatedProgress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${"%.1f".format(state.downloadedBytes / (1024.0 * 1024 * 1024))} / ${"%.1f".format(state.totalBytes / (1024.0 * 1024 * 1024))} GB",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${"%.1f".format(animatedProgress * 100)}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Current status
                if (state.progress.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            state.progress,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (state.step == ChainstateManager.Step.ERROR) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            state.error ?: "Unknown error",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Retry button
                    OutlinedButton(
                        onClick = {
                            chainstateManager.reset()
                            showConfirm = true
                            started = false
                            highestStepReached = 0
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retry")
                    }
                }

                if (state.step == ChainstateManager.Step.COMPLETE) {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Go to Dashboard", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    label: String,
    currentStep: ChainstateManager.Step,
    thisStep: ChainstateManager.Step,
    highestStepReached: Int
) {
    val currentOrdinal = currentStep.ordinal
    val thisOrdinal = thisStep.ordinal

    val (icon, color) = when {
        // Completed steps stay green even on error
        currentStep == ChainstateManager.Step.COMPLETE -> Pair("✅", Color(0xFF4CAF50))
        // Steps before the current one that were reached = completed
        thisOrdinal < currentOrdinal -> Pair("✅", Color(0xFF4CAF50))
        // On error: steps already passed stay green
        currentStep == ChainstateManager.Step.ERROR && thisOrdinal < highestStepReached -> Pair("✅", Color(0xFF4CAF50))
        // On error: the step that failed
        currentStep == ChainstateManager.Step.ERROR && thisOrdinal == highestStepReached -> Pair("❌", Color(0xFFF44336))
        // Currently active step
        currentOrdinal == thisOrdinal -> Pair("⏳", MaterialTheme.colorScheme.primary)
        // Future steps
        else -> Pair("○", Color(0xFF757575))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(icon, modifier = Modifier.width(28.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = if (currentOrdinal == thisOrdinal) FontWeight.Bold else FontWeight.Normal
        )
    }
}
