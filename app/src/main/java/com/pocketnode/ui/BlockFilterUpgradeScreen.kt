package com.pocketnode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.snapshot.BlockFilterManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen for adding or removing Lightning support (BIP 157/158 block filters).
 *
 * Flow:
 * 1. Check donor for existing filters
 * 2. If present: copy directly
 * 3. If not: enable on donor, poll until built, copy, revert donor
 * 4. Configure local node
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockFilterUpgradeScreen(
    onBack: () -> Unit,
    onRestartNode: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { BlockFilterManager(context) }
    val state by manager.state.collectAsState()

    // Load saved creds from pocketnode setup (NodeSetupManager saves these)
    val setupPrefs = remember { context.getSharedPreferences("pocketnode_sftp", android.content.Context.MODE_PRIVATE) }
    val savedHost = remember { setupPrefs.getString("sftp_host", "") ?: "" }
    val savedAdminUser = remember { setupPrefs.getString("admin_user", "") ?: "" }
    val savedSftpUser = remember { setupPrefs.getString("sftp_user", "") ?: "" }
    val savedSftpPass = remember { setupPrefs.getString("sftp_pass", "") ?: "" }

    var sshHost by remember { mutableStateOf(savedHost) }
    var sshPort by remember { mutableStateOf(22) }
    var sshUser by remember { mutableStateOf(savedAdminUser) }
    var sshPassword by remember { mutableStateOf("") }
    var sftpUser by remember { mutableStateOf(savedSftpUser.ifEmpty { "pocketnode" }) }
    var sftpPassword by remember { mutableStateOf(savedSftpPass) }

    val hasSavedHost = savedHost.isNotEmpty()
    val isInstalled = manager.isInstalledLocally()

    // Only need password input (host and user pre-filled from setup)
    var inputHost by remember { mutableStateOf(savedHost) }
    var inputUser by remember { mutableStateOf(savedAdminUser) }
    var inputPassword by remember { mutableStateOf("") }

    var passwordVisible by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var localNodeSynced by remember { mutableStateOf(false) }
    var localNodeHeight by remember { mutableStateOf(0L) }

    // Check local node sync status
    LaunchedEffect(Unit) {
        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
        if (creds != null) {
            val rpc = com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second)
            while (true) {
                try {
                    val info = rpc.getBlockchainInfo()
                    if (info != null) {
                        val progress = info.optDouble("verificationprogress", 0.0)
                        localNodeHeight = info.optLong("blocks", 0)
                        localNodeSynced = progress > 0.9999
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(5000)
            }
        }
    }
    var showBuildConfirm by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val view = LocalView.current

    // Keep screen on during active transfer
    val isWorking = state.step != BlockFilterManager.Step.IDLE &&
            state.step != BlockFilterManager.Step.ERROR &&
            state.step != BlockFilterManager.Step.COMPLETE
    LaunchedEffect(isWorking) {
        view.keepScreenOn = isWorking
    }

    // Auto-hide password after 10 seconds of visibility
    LaunchedEffect(passwordVisible) {
        if (passwordVisible) {
            delay(10_000)
            passwordVisible = false
        }
    }

    LaunchedEffect(Unit) {
        manager.reset()
    }

    // Auto-scroll to bottom when status changes so progress stays visible
    LaunchedEffect(state.step) {
        if (state.step != BlockFilterManager.Step.IDLE) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lightning Support") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color(0xFFFFB300) // Lightning yellow
            )

            Text(
                if (isInstalled) "Lightning Support Enabled" else "Add Lightning Support",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                if (isInstalled) {
                    "Block filters are installed. Zeus and other Neutrino wallets can connect to your node for sovereign Lightning chain validation."
                } else {
                    "Download BIP 157/158 block filters from your source node. This enables Zeus and other Lightning wallets to validate against your own full node."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            if (isInstalled) {
                // --- Installed state ---
                val localSize = manager.localSizeBytes()
                val sizeGb = localSize / (1024.0 * 1024 * 1024)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Active", fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Storage used: ${"%.1f".format(sizeGb)} GB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text("Zeus connects via Neutrino on localhost:8333",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showRemoveConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove Lightning Support")
                }

            } else {
                // --- Not installed state ---

                // Info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Storage, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (state.donorHasFilters == true && state.donorFilterSizeBytes > 0)
                                    "Requires ${"%.1f".format(state.donorFilterSizeBytes / (1024.0 * 1024 * 1024))} GB additional storage"
                                else
                                    "Requires ~13 GB additional storage",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Wifi, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Source node must be reachable",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFFFFB300))
                            Spacer(Modifier.width(8.dp))
                            Text("Enables Zeus, and other Neutrino wallets",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Credential input
                Text("Source Node Connection",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = inputHost,
                    onValueChange = { inputHost = it },
                    label = { Text("Host (IP or hostname)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = inputUser,
                    onValueChange = { inputUser = it },
                    label = { Text("SSH Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = inputPassword,
                    onValueChange = { inputPassword = it },
                    label = { Text("Admin Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (passwordVisible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    }
                )

                // Action buttons (right after password, before status)
                if (state.donorHasFilters == null && !isWorking) {
                    // Step 1: Check donor
                    Button(
                        onClick = {
                            sshHost = inputHost
                            sshUser = inputUser
                            sshPassword = inputPassword
                            scope.launch {
                                manager.checkDonor(inputHost, sshPort, inputUser, inputPassword)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = inputHost.isNotEmpty() && inputUser.isNotEmpty() && inputPassword.isNotEmpty()
                    ) {
                        Text("Check Source Node")
                    }
                } else if (state.donorHasFilters == true && !isWorking) {
                    // Step 2a: Filters exist — copy directly (requires synced local node)
                    if (!localNodeSynced) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFB300).copy(alpha = 0.15f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Node must be fully synced",
                                    fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text("Your local node needs to be fully synced before downloading block filters. " +
                                     "The filter index references block hashes that must exist in your node's block index." +
                                     if (localNodeHeight > 0) "\n\nCurrent height: ${"%,d".format(localNodeHeight)}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val success = manager.copyFromDonor(
                                    sshHost, sshPort, sshUser, sshPassword, sftpUser, sftpPassword)
                                if (success) onRestartNode()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = localNodeSynced
                    ) {
                        Text("Download Block Filters")
                    }
                } else if (state.donorHasFilters == false && !isWorking) {
                    // Step 2b: No filters — offer to build
                    Button(
                        onClick = { showBuildConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Build on Source Node")
                    }
                }

                // Progress display (below button so screen doesn't jump)
                AnimatedVisibility(visible = state.step != BlockFilterManager.Step.IDLE &&
                        state.step != BlockFilterManager.Step.ERROR &&
                        state.step != BlockFilterManager.Step.COMPLETE) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(state.progress.ifEmpty { "Working..." },
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))

                            when (state.step) {
                                BlockFilterManager.Step.DOWNLOADING -> {
                                    LinearProgressIndicator(
                                        progress = { state.downloadProgress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                BlockFilterManager.Step.WAITING_FOR_BUILD -> {
                                    if (state.buildProgress > 0f) {
                                        LinearProgressIndicator(
                                            progress = { state.buildProgress },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                else -> {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }

                // Error display
                if (state.step == BlockFilterManager.Step.ERROR && state.error != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Error", fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.height(4.dp))
                            Text(state.error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                // Donor status
                if (state.donorHasFilters != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (state.donorHasFilters == true) {
                                val sizeGb = state.donorFilterSizeBytes / (1024.0 * 1024 * 1024)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                                        tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Block filters found on source node",
                                        fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Size: ${"%.1f".format(sizeGb)} GB — ready to copy",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Info, contentDescription = null,
                                        tint = Color(0xFFFFB300), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Block filters not found on source node",
                                        fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Your source node needs to build a block filter index. This takes several hours but only runs once.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }

                // Complete state
                if (state.step == BlockFilterManager.Step.COMPLETE) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1B5E20).copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null,
                                tint = Color(0xFFFFB300), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Lightning Support Enabled!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("Restart your node to activate. Zeus can then connect via Neutrino on localhost.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    // Remove confirmation
    if (showRemoveConfirm) {
        val sizeGb = manager.localSizeBytes() / (1024.0 * 1024 * 1024)
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Lightning Support?") },
            text = { Text("This will delete ${"%.1f".format(sizeGb)} GB of block filter data and disable Neutrino connections. Zeus will no longer be able to connect to your node.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    scope.launch {
                        manager.removeLocal(context)
                        onBack()
                    }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Build confirmation
    if (showBuildConfirm) {
        AlertDialog(
            onDismissRequest = { showBuildConfirm = false },
            title = { Text("Build Block Filters?") },
            text = { Text("Your source node will build a block filter index. This takes several hours but only needs to happen once. You can close the app and come back later - we'll notify you when it's ready.") },
            confirmButton = {
                TextButton(onClick = {
                    showBuildConfirm = false
                    scope.launch {
                        val enabled = manager.enableOnDonor(sshHost, sshPort, sshUser, sshPassword)
                        if (enabled) {
                            // Start polling
                            while (true) {
                                delay(600_000) // 10 minutes
                                val done = manager.pollBuildProgress(
                                    sshHost, sshPort, sshUser, sshPassword)
                                if (done == true) {
                                    // Ready to copy
                                    manager.checkDonor(sshHost, sshPort, sshUser, sshPassword)
                                    // Auto-revert donor after confirming filters exist
                                    manager.revertDonor(sshHost, sshPort, sshUser, sshPassword)
                                    break
                                }
                                if (done == null) break // Error
                            }
                        }
                    }
                }) {
                    Text("Build")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBuildConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
