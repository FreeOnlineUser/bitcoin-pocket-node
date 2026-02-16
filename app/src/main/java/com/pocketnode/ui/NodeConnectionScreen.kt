package com.pocketnode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Screen for entering remote node connection details and testing the connection.
 * Pre-fills common defaults for popular node platforms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConnectionScreen(
    onBack: () -> Unit,
    onConnected: (host: String, port: Int, user: String, password: String) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8332") }
    var rpcUser by remember { mutableStateOf("") }
    var rpcPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Node presets
    data class NodePreset(val name: String, val host: String, val port: String, val user: String)
    val presets = listOf(
        NodePreset("Umbrel", "umbrel.local", "8332", "umbrel"), // Note: some Umbrel setups use port 9332
        NodePreset("Start9", "start9.local", "8332", ""),
        NodePreset("RaspiBlitz", "raspiblitz.local", "8332", "raspibolt"),
        NodePreset("myNode", "mynode.local", "8332", "mynode"),
        NodePreset("Custom", "", "8332", ""),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Node") },
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
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text(
                "Node Platform",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )

            // Preset chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                presets.take(3).forEach { preset ->
                    FilterChip(
                        selected = host == preset.host && rpcUser == preset.user,
                        onClick = {
                            host = preset.host
                            port = preset.port
                            rpcUser = preset.user
                            testResult = null
                        },
                        label = { Text(preset.name, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                presets.drop(3).forEach { preset ->
                    FilterChip(
                        selected = host == preset.host && preset.name != "Custom",
                        onClick = {
                            host = preset.host
                            port = preset.port
                            rpcUser = preset.user
                            testResult = null
                        },
                        label = { Text(preset.name, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            // Setup instruction
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚙️ Node Setup Required",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your node must allow RPC connections from your local network. " +
                        "Add this to your node's bitcoin.conf (on Umbrel: Settings → Advanced):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "rpcallowip=<your-subnet>\n" +
                            "# e.g. rpcallowip=10.0.1.0/24\n" +
                            "# or   rpcallowip=192.168.1.0/24",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Restart your node after saving. Your subnet depends on your router — most home networks use 192.168.1.0/24 or 10.0.1.0/24.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Host
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; testResult = null },
                label = { Text("Host / IP") },
                placeholder = { Text("e.g. umbrel.local or 192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Port
            OutlinedTextField(
                value = port,
                onValueChange = { port = it; testResult = null },
                label = { Text("RPC Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            // RPC User
            OutlinedTextField(
                value = rpcUser,
                onValueChange = { rpcUser = it; testResult = null },
                label = { Text("RPC Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // RPC Password
            OutlinedTextField(
                value = rpcPassword,
                onValueChange = { rpcPassword = it; testResult = null },
                label = { Text("RPC Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = "Toggle password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Test connection button
            Button(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        try {
                            val node = com.pocketnode.snapshot.NodeConnectionManager(
                                host = host.trim(),
                                port = port.trim().toIntOrNull() ?: 8332,
                                rpcUser = rpcUser.trim(),
                                rpcPassword = rpcPassword
                            )
                            val result = node.testConnection()
                            if (result.success) {
                                testSuccess = true
                                testResult = "✓ Connected — ${result.chain} chain, block ${result.blocks}"
                            } else {
                                testSuccess = false
                                testResult = "✗ ${result.error}"
                            }
                        } catch (e: Exception) {
                            testSuccess = false
                            testResult = "✗ ${e.message}"
                        }
                        testing = false
                    }
                },
                enabled = !testing && host.isNotBlank() && rpcPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    Text("Test Connection")
                }
            }

            // Test result
            testResult?.let { result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (testSuccess)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        result,
                        modifier = Modifier.padding(16.dp),
                        color = if (testSuccess)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Continue button (only when test passed)
            if (testSuccess) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onConnected(
                            host.trim(),
                            port.trim().toIntOrNull() ?: 8332,
                            rpcUser.trim(),
                            rpcPassword
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Continue — Generate Snapshot")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
