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
import com.pocketnode.power.PowerModeManager
import com.pocketnode.util.BinaryExtractor
import com.pocketnode.util.ConfigGenerator
import org.json.JSONArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.pocketnode.lightning.LightningService
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

        private val _activeBatteryMonitor = MutableStateFlow<BatteryMonitor?>(null)
        val activeBatteryMonitorFlow: StateFlow<BatteryMonitor?> = _activeBatteryMonitor
        var activeBatteryMonitor: BatteryMonitor?
            get() = _activeBatteryMonitor.value
            private set(value) { _activeBatteryMonitor.value = value }

        private val _activePowerModeManager = MutableStateFlow<PowerModeManager?>(null)
        val activePowerModeManagerFlow: StateFlow<PowerModeManager?> = _activePowerModeManager

        /** Last known block height — used by share screen */
        var lastBlockHeight: Int = 0

        /** Whether bitcoind is currently running — observed by dashboard on launch */
        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning

        /** Callback to restart bitcoind with different args (set by service instance) */
        var restartForBurstMode: (suspend (burstMode: Boolean) -> Unit)? = null
    }

    private var bitcoindProcess: Process? = null
    private var notificationJob: Job? = null
    private var powerModeManager: PowerModeManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cachedBinaryPath: File? = null
    private var cachedDataDir: File? = null
    @Volatile private var isRestarting = false

    // Network-aware sync control
    var networkMonitor: NetworkMonitor? = null
        private set
    var syncController: SyncController? = null
        private set
    var batteryMonitor: BatteryMonitor? = null
    var screenMonitor: ScreenMonitor? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(status = "Starting..."))
        serviceScope.launch { startBitcoind() }
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        notificationJob?.cancel()
        powerModeManager?.stop()
        _activePowerModeManager.value = null
        syncController?.stop()
        networkMonitor?.stop()
        batteryMonitor?.stop()
        screenMonitor?.stop()
        activeNetworkMonitor = null
        activeSyncController = null
        activeBatteryMonitor = null
        serviceScope.launch {
            stopBitcoind()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private suspend fun startBitcoind() {
        try {
            // Step 0: Initialize TorManager and start proxy if Tor was enabled
            // Must happen before bitcoind starts so -proxy flag is set correctly
            val torManager = com.pocketnode.tor.TorManager.getInstance(this)
            if (torManager.isEnabled()) {
                Log.i(TAG, "Tor was enabled — starting SOCKS proxy before bitcoind")
                torManager.start()
                // Wait up to 30s for Tor to bootstrap
                var waited = 0
                while (com.pocketnode.tor.TorManager.statusFlow.value != com.pocketnode.tor.TorManager.TorStatus.RUNNING && waited < 30000) {
                    delay(500)
                    waited += 500
                }
                if (com.pocketnode.tor.TorManager.statusFlow.value == com.pocketnode.tor.TorManager.TorStatus.RUNNING) {
                    Log.i(TAG, "Tor proxy ready")
                } else {
                    Log.w(TAG, "Tor proxy failed to start in 30s — starting bitcoind without proxy")
                }
            }

            // Step 1: Extract binary
            val binaryPath = BinaryExtractor.extractIfNeeded(this)
            Log.i(TAG, "Binary at: $binaryPath")

            // Step 2: Generate config
            val dataDir = ConfigGenerator.ensureConfig(this)
            Log.i(TAG, "Data dir: $dataDir")

            // Migrate maxconnections from 4 to 8
            val confFile = dataDir.resolve("bitcoin.conf")
            if (confFile.exists()) {
                val content = confFile.readText()
                if (content.contains("maxconnections=4")) {
                    confFile.writeText(content.replace("maxconnections=4", "maxconnections=8"))
                    Log.i(TAG, "Migrated maxconnections from 4 to 8")
                }
            }

            // Step 2.5: Detect orphan bitcoind from a previous app crash or service restart.
            // Android may kill our service without killing the child process, leaving
            // bitcoind running with a held lock file but no managing service.
            val lockFile = dataDir.resolve(".lock")
            if (lockFile.exists()) {
                // RPC check distinguishes a live orphan from a stale lock file left after a crash
                val creds = ConfigGenerator.readCredentials(this@BitcoindService)
                if (creds != null) {
                    val testRpc = BitcoinRpcClient(creds.first, creds.second)
                    try {
                        val info = testRpc.getBlockchainInfo()
                        if (info != null) {
                            Log.i(TAG, "bitcoind already running (lock file present, RPC responding) — attaching")
                            _isRunning.value = true
                            getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                                .edit().putBoolean("node_was_running", true).apply()
                            updateNotification("Running (attached)")

                            // Start network monitoring and sync control (singleton)
                            val monitor = NetworkMonitor.getInstance(this@BitcoindService)
                            monitor.start()
                            networkMonitor = monitor
                            activeNetworkMonitor = monitor
                            val controller = SyncController(this@BitcoindService, monitor, testRpc)
                            controller.start()
                            syncController = controller
                            activeSyncController = controller
                            val bm = BatteryMonitor(this@BitcoindService)
                            bm.start()
                            batteryMonitor = bm
                            activeBatteryMonitor = bm
                            val sm = ScreenMonitor(this@BitcoindService)
                            sm.start()
                            screenMonitor = sm
                            startNotificationUpdater(testRpc)
                            val pmm = PowerModeManager.getInstance(this@BitcoindService)
                            pmm.reloadFromPrefs() // Re-read saved mode in case statics are stale
                            pmm.setMode(PowerModeManager.modeFlow.value, serviceScope)
                            pmm.setRpc(testRpc)
                            pmm.startAutoIfEnabled(monitor.networkState, bm.state, serviceScope)
                            powerModeManager = pmm
                            _activePowerModeManager.value = pmm
                            // Wire up restart callback
                            restartForBurstMode = { burstMode -> restartForMode(burstMode) }
                            // Cache paths for restart
                            cachedBinaryPath = binaryPath
                            cachedDataDir = dataDir
                            // Stop LDK on screen lock in Low/Away mode (no channels = safe to pause)
                            serviceScope.launch {
                                sm.isLockedFlow.collect { locked ->
                                    val mode = PowerModeManager.modeFlow.value
                                    if (mode == PowerModeManager.Mode.MAX) return@collect
                                    val ls = LightningService.getInstance(this@BitcoindService)
                                    if (locked) {
                                        val channels = try {
                                            ls.listChannels().size
                                        } catch (_: Exception) { 1 }  // assume channels on error
                                        if (channels == 0) {
                                            val running = LightningService.stateFlow.value.status ==
                                                LightningService.LightningState.Status.RUNNING
                                            if (running) {
                                                Log.i(TAG, "Screen locked, no channels: pausing LDK")
                                                ls.stop()
                                            }
                                        }
                                    } else {
                                        // Screen unlocked: restart LDK if it was running before
                                        val shouldRun = getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                            .getBoolean("lightning_was_running", false)
                                        val running = LightningService.stateFlow.value.status ==
                                            LightningService.LightningState.Status.RUNNING
                                        if (shouldRun && !running) {
                                            val rpcPrefs = getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                                            val rpcUser2 = rpcPrefs.getString("rpc_user", "pocketnode") ?: "pocketnode"
                                            val rpcPass2 = rpcPrefs.getString("rpc_password", "") ?: ""
                                            if (rpcPass2.isNotEmpty()) {
                                                Log.i(TAG, "Screen unlocked: resuming LDK")
                                                ls.start(rpcUser2, rpcPass2)
                                            }
                                        }
                                    }
                                }
                            }
                            Log.i(TAG, "Attached to existing bitcoind, network control started")

                            // Stay alive — poll until bitcoind stops responding
                            while (_isRunning.value) {
                                delay(10_000)
                                if (isRestarting) continue  // Don't break during intentional restart
                                try {
                                    val check = testRpc.getBlockchainInfo()
                                    if (check == null) {
                                        Log.w(TAG, "Existing bitcoind stopped responding")
                                        break
                                    }
                                } catch (_: Exception) {
                                    if (isRestarting) continue
                                    Log.w(TAG, "Existing bitcoind RPC failed")
                                    break
                                }
                            }
                            _isRunning.value = false
                            updateNotification("Stopped")
                            return
                        }
                    } catch (_: Exception) {
                        Log.d(TAG, "Lock file exists but RPC not responding — stale lock, proceeding with start")
                    }
                }
            }

            // Step 3: Start the process
            cachedBinaryPath = binaryPath
            cachedDataDir = dataDir
            // Reload saved mode before reading the flow: the static defaults to LOW,
            // so a fresh process would otherwise launch bitcoind with burst args
            // even when the saved mode is Max.
            PowerModeManager.getInstance(this@BitcoindService).reloadFromPrefs()
            val isBurstMode = PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX
            val process = startBitcoindProcess(binaryPath, dataDir, isBurstMode)
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
                val monitor = NetworkMonitor.getInstance(this@BitcoindService)
                monitor.start()
                networkMonitor = monitor
                activeNetworkMonitor = monitor

                val controller = SyncController(this@BitcoindService, monitor, rpc)
                controller.start()
                syncController = controller
                activeSyncController = controller
                val bm = BatteryMonitor(this@BitcoindService)
                bm.start()
                batteryMonitor = bm
                activeBatteryMonitor = bm
                startNotificationUpdater(rpc)
                val pmm = PowerModeManager.getInstance(this@BitcoindService)
                pmm.reloadFromPrefs() // Re-read saved mode in case statics are stale
                pmm.setMode(PowerModeManager.modeFlow.value, serviceScope)
                pmm.setRpc(rpc)
                pmm.startAutoIfEnabled(monitor.networkState, bm.state, serviceScope)
                powerModeManager = pmm
                _activePowerModeManager.value = pmm

                // Wire up restart callback so PMM can restart bitcoind on mode change
                restartForBurstMode = { burstMode -> restartForMode(burstMode) }

                // Clear any block-index invalidity inherited from a stricter
                // previously-active binary (see reconsiderInheritedInvalidBlocks).
                serviceScope.launch(Dispatchers.IO) { reconsiderInheritedInvalidBlocks(rpc) }

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

            // Wait for process to exit — but if it was replaced by restartForMode,
            // the new process is already running, so don't mark as stopped
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
            if (bitcoindProcess === process) {
                // This was the active process — it exited for real
                _isRunning.value = false
                Log.i(TAG, "bitcoind exited with code $exitCode")
                updateNotification("Stopped (exit: $exitCode)")
            } else {
                Log.i(TAG, "bitcoind process replaced by restart (exit: $exitCode)")
            }
        } catch (e: Exception) {
            _isRunning.value = false
            Log.e(TAG, "Failed to start bitcoind", e)
            updateNotification("Error: ${e.message}")
        }
    }

    /**
     * Build args and start the bitcoind process.
     * burstMode=true adds -blocksonly and -maxconnections=3 for Low/Away modes.
     */
    private fun startBitcoindProcess(binaryPath: File, dataDir: File, burstMode: Boolean): Process {
        val nativeLibDir = applicationInfo.nativeLibraryDir

        val args = mutableListOf(
            binaryPath.absolutePath,
            "-datadir=${dataDir.absolutePath}",
            "-conf=${dataDir.resolve("bitcoin.conf").absolutePath}"
        )

        // BIP 110 signaling
        if (BinaryExtractor.isSignalBip110(this)) {
            args.add("-signalbip110=1")
            args.add("-uacomment=BIP-110")
        }

        // Tor: route all P2P through SOCKS proxy when enabled
        if (com.pocketnode.tor.TorManager.enabledFlow.value) {
            args.add("-proxy=127.0.0.1:${com.pocketnode.tor.TorManager.SOCKS_PORT}")
            args.add("-onlynet=onion")
            args.add("-dnsseed=0")
        }

        // Low/Away mode: suppress mempool relay, limit peers, cap upload.
        // Inbound is already loopback-only via bind=127.0.0.1 in bitcoin.conf
        // (-listen=0 would fatally conflict with that bind line), so
        // -maxuploadtarget just caps what outbound peers can pull (MiB/24h).
        if (burstMode) {
            args.add("-blocksonly=1")
            args.add("-maxconnections=3")
            args.add("-maxuploadtarget=50")
            Log.i(TAG, "Burst mode: -blocksonly=1 -maxconnections=3 -maxuploadtarget=50")
        } else {
            args.add("-maxconnections=8")
        }

        val pb = ProcessBuilder(args)
        pb.directory(dataDir)
        pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
        pb.redirectErrorStream(true)

        val process = pb.start()
        Log.i(TAG, "bitcoind started (burstMode=$burstMode)")
        return process
    }

    /**
     * Restart bitcoind with different args for a mode change (Max <-> Low/Away).
     * Stops the current process via RPC, waits for exit, starts with new args.
     */
    suspend fun restartForMode(burstMode: Boolean) {
        val binaryPath = cachedBinaryPath ?: return
        val dataDir = cachedDataDir ?: return

        Log.i(TAG, "Restarting bitcoind for ${if (burstMode) "burst" else "max"} mode")
        updateNotification("Restarting...")
        isRestarting = true

        // Stop current process
        stopBitcoind()
        isRestarting = false

        // Start with new args
        val process = startBitcoindProcess(binaryPath, dataDir, burstMode)
        bitcoindProcess = process
        _isRunning.value = true
        updateNotification("Running")

        // Re-establish RPC connection for PowerModeManager
        val creds = ConfigGenerator.readCredentials(this@BitcoindService)
        if (creds != null) {
            // Wait for RPC to be ready (up to 10s)
            val rpc = BitcoinRpcClient(creds.first, creds.second)
            var ready = false
            for (i in 1..20) {
                try {
                    if (rpc.getBlockchainInfo() != null) {
                        ready = true
                        break
                    }
                } catch (_: Exception) {}
                delay(500)
            }
            if (ready) {
                powerModeManager?.setRpc(rpc)
                Log.i(TAG, "RPC reconnected after restart")
            } else {
                Log.w(TAG, "RPC not ready after restart")
            }
        }

        // Log stdout in background
        serviceScope.launch {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Log.d(TAG, "bitcoind: $line") }
                }
            } catch (_: java.io.InterruptedIOException) {}
            catch (_: java.io.IOException) {}
        }
    }

    /**
     * Clear block-index "invalid" flags inherited from a previously-active
     * binary with stricter consensus rules.
     *
     * The BLOCK_FAILED flag lives in the datadir's block index, not in the
     * binary. If a stricter build (e.g. a BIP-110 / UASF reduced-data Knots
     * build) marks a mainnet block invalid, then the active binary is later
     * swapped for a non-enforcing one (e.g. Core v30), the new binary inherits
     * the verdict and refuses to sync past that block even though it would
     * accept the block under its own rules. reconsiderblock clears the flag
     * (block + ancestors + descendants) so sync resumes.
     *
     * A stricter binary can mark MANY blocks invalid (e.g. a mandatory-signaling
     * UASF rejects every non-signaling block), and those flags only surface as
     * blockers progressively, as the node downloads up to each one during
     * catch-up. So this runs for the service lifetime as a periodic healer, not
     * a one-shot: it keeps sweeping getchaintips for "invalid" branches and
     * clearing them. It does NOT stop when the chain first looks synced —
     * blocks==headers is true transiently during catch-up (in the lull after
     * consuming the known headers, before the next batch carrying the next
     * poisoned block arrives), so exiting there would strand a later blocker.
     * Instead it announces "synced" once and drops to a slow heartbeat sweep,
     * which also self-heals any future occurrence (e.g. another binary swap).
     * Safe on a clean index: getchaintips reports no invalid branches, nothing
     * is cleared, and the heartbeat stays quiet.
     */
    private suspend fun reconsiderInheritedInvalidBlocks(rpc: BitcoinRpcClient) {
        // Wait for RPC to finish warmup. During warmup the daemon returns
        // error -28, which call() surfaces as a NON-null {"_rpc_error":true}
        // object — so checking for non-null isn't enough, we must confirm a
        // real (non-error) result before issuing the sweep.
        var ready = false
        for (i in 1..60) {
            val info = rpc.getBlockchainInfo()
            if (info != null && !info.optBoolean("_rpc_error", false)) { ready = true; break }
            delay(1_000)
        }
        if (!ready) {
            Log.w(TAG, "reconsider: RPC not ready after 60s, skipping invalid-block sweep")
            return
        }

        var totalCleared = 0
        var announcedSynced = false
        while (currentCoroutineContext().isActive) {
            val info = rpc.getBlockchainInfo()
            if (info == null || info.optBoolean("_rpc_error", false)) { delay(10_000); continue }
            val blocks = info.optLong("blocks", 0)
            val headers = info.optLong("headers", 0)
            val ibd = info.optBoolean("initialblockdownload", true)

            var clearedThisPass = 0
            try {
                val result = rpc.call("getchaintips")
                val tips = if (result != null && !result.optBoolean("_rpc_error", false))
                    result.optJSONArray("value") else null
                if (tips != null) {
                    for (i in 0 until tips.length()) {
                        val tip = tips.getJSONObject(i)
                        if (tip.optString("status") == "invalid") {
                            val hash = tip.optString("hash")
                            val height = tip.optLong("height")
                            Log.w(TAG, "reconsider: invalid branch tip at $height ($hash) — clearing")
                            try {
                                rpc.call("reconsiderblock", JSONArray().apply { put(hash) })
                                clearedThisPass++
                                totalCleared++
                            } catch (e: Exception) {
                                Log.e(TAG, "reconsider: reconsiderblock failed for $hash: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "reconsider: getchaintips sweep failed: ${e.message}")
            }

            if (clearedThisPass > 0) {
                announcedSynced = false  // a new blocker appeared; not done
                Log.i(TAG, "reconsider: cleared $clearedThisPass invalid branch(es) (total $totalCleared), resuming sync")
                delay(8_000)  // let it reconnect/advance, then re-check soon
            } else {
                if (!ibd && blocks >= headers - 1 && !announcedSynced) {
                    announcedSynced = true
                    Log.i(TAG, "reconsider: chain synced ($blocks/$headers), $totalCleared invalid branch(es) cleared total")
                }
                // Slow heartbeat once synced; faster while still catching up so a
                // freshly-surfaced blocker is cleared promptly.
                delay(if (announcedSynced) 120_000 else 20_000)
            }
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
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Starts a coroutine that polls RPC every 30s and updates the
     * foreground notification with live node stats.
     */
    private var electrumAutoStartedInService = false

    private fun startNotificationUpdater(rpc: BitcoinRpcClient) {
        notificationJob?.cancel()
        electrumAutoStartedInService = false
        notificationJob = serviceScope.launch {
            while (isActive && _isRunning.value) {
                try {
                    val info = rpc.getBlockchainInfo()
                    if (info != null && !info.has("_rpc_error")) {
                        val height = info.optLong("blocks", 0)
                        lastBlockHeight = height.toInt()
                        getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                            .edit().putInt("last_chain_height", lastBlockHeight).apply()
                        val headers = info.optLong("headers", 0)
                        val progress = info.optDouble("verificationprogress", 0.0)
                        val peers = rpc.getPeerCount()
                        val synced = progress > 0.9999

                        // Auto-start BWT when synced (if it was previously running)
                        if (synced && !electrumAutoStartedInService) {
                            val prefs = getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                            if (prefs.getBoolean("electrum_was_running", true)) {
                                electrumAutoStartedInService = true
                                // BWT (Bitcoin Wallet Tracker) needs a synced node to index wallet history.
                                // Re-launch it automatically so the user's wallet is ready without manual action.
                                val bwt = ElectrumService(this@BitcoindService)
                                bwt.start(saveState = false)
                                Log.i(TAG, "Auto-started BWT from service (node synced)")
                            }
                            // Auto-start Lightning when synced (if enabled and was previously running)
                            val ldkEnabled = prefs.getBoolean("ldk_lightning_enabled", true)
                            if (ldkEnabled && prefs.getBoolean("lightning_was_running", false)) {
                                val crashCount = prefs.getInt("lightning_crash_count", 0)
                                if (crashCount >= 3) {
                                    Log.e(TAG, "Lightning crash circuit breaker: $crashCount consecutive crashes. Not auto-restarting. User must start manually.")
                                    prefs.edit().putBoolean("lightning_was_running", false).apply()
                                } else {
                                    val rpcUser = prefs.getString("rpc_user", "pocketnode") ?: "pocketnode"
                                    val rpcPass = prefs.getString("rpc_password", "") ?: ""
                                    if (rpcPass.isNotEmpty()) {
                                        Thread {
                                            try {
                                                com.pocketnode.lightning.LightningService.getInstance(this@BitcoindService).start(rpcUser, rpcPass)
                                                Log.i(TAG, "Auto-started Lightning from service (node synced, crash count: $crashCount)")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to auto-start Lightning: ${e.message}")
                                            }
                                        }.start()
                                    }
                                }
                            }
                        }

                        // Read cached oracle price
                        val oraclePrefs = getSharedPreferences("oracle_cache", MODE_PRIVATE)
                        val oraclePrice = oraclePrefs.getInt("price", -1)

                        val currentMode = PowerModeManager.modeFlow.value
                        val modeLabel = "${currentMode.emoji} ${currentMode.notificationLabel}"
                        val torActive = com.pocketnode.tor.TorManager.enabledFlow.value &&
                                com.pocketnode.tor.TorManager.statusFlow.value == com.pocketnode.tor.TorManager.TorStatus.RUNNING
                        val torPrefix = if (torActive) "🧅 " else ""
                        val title = when {
                            synced -> "$torPrefix$modeLabel"
                            else -> "${torPrefix}⏳ Syncing"
                        }

                        val sb = StringBuilder()
                        sb.append("Block ${"%,d".format(height)}")
                        if (!synced) {
                            sb.append(" / ${"%,d".format(headers)}")
                            sb.append(" · ${"%.1f".format(progress * 100)}%")
                        }
                        sb.append(" · $peers peer${if (peers != 1) "s" else ""}")
                        if (oraclePrice > 0) {
                            sb.append(" · \$${"%,d".format(oraclePrice)}")
                        }
                        if (currentMode == PowerModeManager.Mode.AWAY) {
                            val nextBurst = PowerModeManager.nextBurstFlow.value
                            if (nextBurst > 0) {
                                val mins = ((nextBurst - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                                sb.append(" · next sync ${mins}min")
                            }
                        }

                        updateNotification(title, sb.toString())
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Notification updater: ${e.message}")
                }
                // Poll faster while waiting for sync, then relax to 30s once synced
                delay(if (electrumAutoStartedInService) 30_000 else 5_000)
            }
        }
    }

    private fun buildNotification(title: String = "₿ Pocket Node", status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(status: String) {
        updateNotification("₿ Pocket Node", status)
    }

    private fun updateNotification(title: String, status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, status))
    }
}
