package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketnode.snapshot.NodeSetupManager
import com.pocketnode.ui.components.AdminCredentialsDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeAccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setupManager = remember { NodeSetupManager(context) }
    val isSetup = remember { setupManager.isSetupDone() }

    var host by remember { mutableStateOf(setupManager.getSavedHost()) }
    var port by remember { mutableStateOf(setupManager.getSavedPort()) }
    var user by remember { mutableStateOf(setupManager.getSavedUser()) }
    var password by remember { mutableStateOf(setupManager.getSavedPassword()) }

    var passwordVisible by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<String?>(null) }
    var removeSuccess by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        AdminCredentialsDialog(
            title = "Admin Credentials to Remove Access",
            defaultHost = host,
            defaultPort = port,
            defaultUsername = setupManager.getSavedAdminUser(),
            onDismiss = { showRemoveDialog = false },
            onConfirm = { creds ->
                showRemoveDialog = false
                removing = true
                removeError = null
                scope.launch {
                    val result = setupManager.removeAccess(
                        adminHost = creds.host,
                        adminPort = creds.port,
                        adminUser = creds.username,
                        adminPassword = creds.password
                    )
                    removing = false
                    if (result) {
                        removeSuccess = true
                    } else {
                        removeError = "Failed to remove access. Check your admin credentials."
                    }
                }
            }
        )
    }

    if (removeSuccess) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Node Access") },
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
            if (removeSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        "Access removed successfully",
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
                return@Scaffold
            }

            if (!isSetup) {
                Text("No node access configured.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Use Snapshot > Copy chainstate or Pull UTXO snapshot to set up access to your node.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Scaffold
            }

            // SFTP Credentials
            Text(
                "SFTP Credentials",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LabelValue("Host", host)
                    LabelValue("Port", port.toString())
                    LabelValue("Username", user)

                    // Password with toggle
                    Column {
                        Text(
                            "Password",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (passwordVisible) password else "••••••••••••",
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "Hide" else "Show"
                                )
                            }
                        }
                    }
                }
            }

            // What was set up
            Text(
                "What was set up",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val items = listOf(
                        "A restricted SFTP-only user was created on your node",
                        "Chrooted to /home/pocketnode — cannot access other files",
                        "Cannot run commands — file transfer only",
                        "Cannot read your Bitcoin data, wallet, or configs",
                        "Root-owned helper script copies only snapshot files",
                        "SSH config enforces these restrictions"
                    )
                    items.forEach { item ->
                        Text(
                            "• $item",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Why card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Why?", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This allows your phone to securely download snapshots and chainstate exports without exposing sensitive data.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Error
            removeError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Remove Access
            Button(
                onClick = { showRemoveDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                enabled = !removing
            ) {
                if (removing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Removing...")
                } else {
                    Text("Remove Access", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace
        )
    }
}
