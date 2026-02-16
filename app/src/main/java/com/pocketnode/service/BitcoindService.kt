package com.pocketnode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import com.pocketnode.MainActivity
import com.pocketnode.R
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.BinaryExtractor
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service that manages the bitcoind process lifecycle.
 *
 * Android requires a foreground service with persistent notification
 * for any long-running background work. This service:
 *
 * 1. Extracts the bitcoind binary from assets (first run)
 * 2. Generates bitcoin.conf with random RPC credentials
 * 3. Starts bitcoind as a child process
 * 4. Monitors the process and restarts if needed
 * 5. Gracefully shuts down via RPC on stop
 */
class BitcoindService : Service() {

    companion object {
        private const val TAG = "BitcoindService"
        private const val CHANNEL_ID = "bitcoind_channel"
        private const val NOTIFICATION_ID = 1

        // Accessible from UI for observation — using StateFlow so Compose recomposes
        private val _activeNetworkMonitor = MutableStateFlow<NetworkMonitor?>(null)
        val activeNetworkMonitorFlow: StateFlow<NetworkMonitor?> = _activeNetworkMonitor
        var activeNetworkMonitor: NetworkMonitor?
            get() = _activeNetworkMonitor.value
            private set(value) { _activeNetworkMonitor.value = value }

        private val _activeSyncController = MutableStateFlow<SyncController?>(null)
        val activeSyncControllerFlow: StateFlow<SyncController?> = _activeSyncController
        var activeSyncController: SyncController?
            get() = _activeSyncController.value
            private set(value) { _activeSyncController.value = value }

        /** Whether bitcoind is currently running — observed by dashboard on launch */
        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning
    }

    private var bitcoindProcess: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Network-aware sync control
    var networkMonitor: NetworkMonitor? = null
        private set
    var syncController: SyncController? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        serviceScope.launch { startBitcoind() }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        syncController?.stop()
        networkMonitor?.stop()
        activeNetworkMonitor = null
        activeSyncController = null
        serviceScope.launch {
            stopBitcoind()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private suspend fun startBitcoind() {
        try {
            // Step 1: Extract binary
            val binaryPath = BinaryExtractor.extractIfNeeded(this)
            Log.i(TAG, "Binary at: $binaryPath")

            // Step 2: Generate config
            val dataDir = ConfigGenerator.ensureConfig(this)
            Log.i(TAG, "Data dir: $dataDir")

            // Step 3: Start the process
            val nativeLibDir = applicationInfo.nativeLibraryDir

            val args = mutableListOf(
                binaryPath.absolutePath,
                "-datadir=${dataDir.absolutePath}",
                "-conf=${dataDir.resolve("bitcoin.conf").absolutePath}"
            )

            val pb = ProcessBuilder(args)
            pb.directory(dataDir)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.redirectErrorStream(true)

            val process = pb.start()
            bitcoindProcess = process

            _isRunning.value = true
            getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .edit().putBoolean("node_was_running", true).apply()
            updateNotification("Running")
            Log.i(TAG, "bitcoind started (pid available on API 33+)")

            // Start network-aware sync control
            val creds = ConfigGenerator.readCredentials(this@BitcoindService)
            if (creds != null) {
                val rpc = BitcoinRpcClient(creds.first, creds.second)
                val monitor = NetworkMonitor(this@BitcoindService)
                monitor.start()
                networkMonitor = monitor
                activeNetworkMonitor = monitor

                val controller = SyncController(this@BitcoindService, monitor, rpc)
                controller.start()
                syncController = controller
                activeSyncController = controller
                Log.i(TAG, "Network-aware sync control started")
            }

            // Log stdout/stderr in background
            serviceScope.launch {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> Log.d(TAG, "bitcoind: $line") }
                    }
                } catch (_: java.io.InterruptedIOException) {
                    Log.d(TAG, "Log reader interrupted (process stopped)")
                } catch (_: java.io.IOException) {
                    Log.d(TAG, "Log reader closed")
                }
            }

            // Wait for process to exit
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
            _isRunning.value = false
            Log.i(TAG, "bitcoind exited with code $exitCode")
            updateNotification("Stopped (exit: $exitCode)")
        } catch (e: Exception) {
            _isRunning.value = false
            Log.e(TAG, "Failed to start bitcoind", e)
            updateNotification("Error: ${e.message}")
        }
    }

    private suspend fun stopBitcoind() {
        try {
            // Try graceful RPC shutdown first
            val creds = ConfigGenerator.readCredentials(this)
            if (creds != null) {
                val rpc = BitcoinRpcClient(creds.first, creds.second)
                rpc.stop()
                Log.i(TAG, "Sent RPC stop command")

                // Wait up to 15s for graceful shutdown
                withTimeoutOrNull(15_000) {
                    while (bitcoindProcess?.isAlive == true) {
                        delay(500)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "RPC stop failed, force killing", e)
        }

        // Force kill if still running
        bitcoindProcess?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                Log.i(TAG, "Force killed bitcoind")
            }
        }
        bitcoindProcess = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("₿ Pocket Node")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
