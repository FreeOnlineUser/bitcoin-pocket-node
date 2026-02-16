package com.pocketnode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pocketnode.service.BitcoindService
import com.pocketnode.ui.PocketNodeApp

/**
 * Main entry point for Bitcoin Pocket Node.
 *
 * This activity hosts the Compose UI and handles notification permissions
 * required for the foreground service on Android 13+.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result — service works either way, just no visible notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ requires runtime permission for notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Auto-start node if it was running before app was killed
        val prefs = getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("node_was_running", false) && !BitcoindService.isRunningFlow.value) {
            val intent = Intent(this, BitcoindService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContent {
            PocketNodeApp(
                networkMonitor = BitcoindService.activeNetworkMonitor,
                syncController = BitcoindService.activeSyncController
            )
        }
    }
}
