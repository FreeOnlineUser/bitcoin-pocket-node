package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray
import org.lightningdevkit.ldknode.*
import java.io.File

/**
 * Manages the ldk-node Lightning node lifecycle.
 * Connects to local bitcoind via RPC for chain data.
 * Powered by LDK (Lightning Dev Kit) from Spiral.
 */
class LightningService(private val context: Context) {

    companion object {
        private const val TAG = "LightningService"
        private const val STORAGE_DIR = "lightning"
        private const val RGS_URL = "https://rapidsync.lightningdevkit.org/snapshot"

        // One-time restart flag for orphan balance rebroadcast (persists across LDK restarts)
        @Volatile
        private var hasAttemptedRebroadcastRestart = false
        private var hasCalledBroadcastThisSession = false

        // Singleton state for UI observation
        private val _state = MutableStateFlow(LightningState())
        val stateFlow: StateFlow<LightningState> = _state.asStateFlow()

        private var instance: LightningService? = null

        fun getInstance(context: Context): LightningService {
            return instance ?: LightningService(context.applicationContext).also { instance = it }
        }
    }

    data class LightningState(
        val status: Status = Status.STOPPED,
        val nodeId: String? = null,
        val onchainBalanceSats: Long = 0,
        val spendableOnchainSats: Long = 0,
        val lightningBalanceSats: Long = 0,
        val channelCount: Int = 0,
        val usableChannels: Int = 0,
        val totalCapacitySats: Long = 0,
        val totalInboundSats: Long = 0,
        val error: String? = null,
        // Prune recovery progress
        val recoveryBlocksNeeded: Int = 0,
        val recoveryBlocksDone: Int = 0,
        val recoveryWaitingForWifi: Boolean = false,
        // Background UTXO scan
        val scanningForFunds: Boolean = false,
        val scanProgress: Int = 0,  // 0-100%
        // Channel error (set when a pending channel is rejected by peer)
        val lastChannelError: String? = null,
        // Pending channel confirmation tracking
        val pendingChannels: List<PendingChannel> = emptyList(),
        // Funding tx fee rates keyed by channel ID (sat/vB)
        val channelFeeRates: Map<String, Long> = emptyMap(),
        // Pending balances from channel closures
        val pendingCloseSats: Long = 0,
        val pendingCloseDetails: List<PendingClose> = emptyList(),
        // Chain sync status for payment readiness
        val ldkHeight: Long = 0,
        val bitcoindHeight: Long = 0,
        val chainSynced: Boolean = false,
        // Watchtower bridge connectivity
        val watchtowerReachable: Boolean? = null,  // null=unknown, true=connected, false=failed
        // Graph readiness for routing
        val graphNodes: Int = 0,
        // Connected peer counts
        val lnPeerCount: Int = 0,
        val btcPeerCount: Int = 0
    ) {
        data class PendingChannel(
            val channelId: String,
            val peerAlias: String,
            val confirmations: Int,
            val confirmationsRequired: Int,
            val capacitySats: Long
        )

        data class PendingClose(
            val channelId: String,
            val amountSats: Long,
            val status: String, // "Pending broadcast", "Awaiting confirmation", "Awaiting threshold"
            val confirmationHeight: Int = 0, // block height when confirmed (for countdown)
            val txid: String? = null, // spending/close txid
            val blocksRemaining: Int = 0, // blocks until spendable (for force-close timelock)
            val confirmations: Int = 0 // current confirmation count
        )

        enum class Status { STOPPED, STARTING, RUNNING, ERROR, RECOVERING }
    }

    private var node: Node? = null
    private lateinit var storageDir: File

    /**
     * Force WAL checkpoint on LDK's SQLite. Called after every critical state change
     * (channel events, payments) to ensure the main DB file is up to date.
     * If the process is killed after this returns, no channel state is lost.
     */
    private fun walCheckpoint() {
        try {
            val dbFile = File(storageDir, "ldk_node_data.sqlite")
            if (!dbFile.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.close()
        } catch (e: Exception) {
            Log.w(TAG, "Runtime WAL checkpoint failed: ${e.message}")
        }
    }
    private var rpcClient: BitcoinRpcClient? = null
    private var watchtowerBridge: WatchtowerBridge? = null
    private var lndHubServer: LndHubServer? = null
    private var stateRefreshJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Extracted managers (delegate pattern — public API stays the same)
    val payments = PaymentManager(context)
    val channels = ChannelManager(context)
    val onchain = OnchainWallet(context)
    val recovery = RecoveryManager(context).also { it.stateFlow = _state }
    val channelEvents = ChannelEventHandler(context)
    val scb = StaticChannelBackup(context)
    private lateinit var refreshLoop: StateRefreshLoop
    private val reconnectAttempts = mutableMapOf<String, Long>()
    private val pendingChannelHolds = mutableSetOf<String>() // channel IDs with active network holds

    // Signaled by handleEvents() when a channel event (Pending/Ready/Closed) occurs
    @Volatile private var channelEventLatch: java.util.concurrent.CountDownLatch? = null

    @Volatile private var starting = false
    @Volatile private var lastBitcoindHeight = 0L

    /**
     * Start the Lightning node on a bare Java Thread.
     *
     * CRITICAL: Must NOT use Dispatchers.IO, runBlocking, or any coroutine
     * dispatcher before node.start() is called.
     *
     * Root cause: UniFFI's JNI bridge attaches a tokio runtime to every
     * Dispatchers.IO thread. runBlocking() on a plain Thread installs a
     * Kotlin BlockingEventLoop, and any withContext(Dispatchers.IO) call
     * inside it briefly runs on a tokio-bearing thread. This causes
     * tokio::runtime::Handle::try_current() to succeed in LDK's Runtime::new(),
     * so LDK borrows UniFFI's runtime handle instead of creating its own.
     * The background sync task then runs on a JNI thread with no active
     * reactor, and header_cache.lock().await hangs forever.
     *
     * Fix: startInternal is a plain fun with zero coroutine machinery.
     * All RPC calls before node.start() use callSync() / getBlockchainInfoSync()
     * which are plain blocking HttpURLConnection — no coroutine context attached.
     */
    fun start(rpcUser: String, rpcPassword: String, rpcPort: Int = 8332) {
        // If a pending seed restore exists, stop the running node first so we restart fresh
        val pendingFile = File(context.filesDir, "pending_seed_restore")
        if (pendingFile.exists() && node != null) {
            Log.i(TAG, "Pending seed restore found while node running. Stopping for restart.")
            try { stop() } catch (_: Exception) {}
            Thread.sleep(500)
        }

        synchronized(this) {
            if (node != null || starting) {
                Log.w(TAG, "Lightning node already running or starting")
                return
            }
            starting = true
        }

        scope.launch {
            _state.value = _state.value.copy(status = LightningState.Status.STARTING, error = null)
            delay(100)
        }

        Thread({
            startInternal(rpcUser, rpcPassword, rpcPort)
        }, "ldk-start").start()
    }

    // Plain fun — no suspend, no coroutine context, no runBlocking.
    // Every call in this function that happens before node.start() must be
    // coroutine-free. After node.start() returns, tokio's runtime is fully
    // initialised and it's safe to touch coroutine machinery again.
    private fun startInternal(rpcUser: String, rpcPassword: String, rpcPort: Int) {
        Log.i(TAG, "startInternal: entering")
        try {
            // Apply pending seed restore before LDK touches any files
            recovery.applyPendingSeedRestore(onchain)

            val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)
            rpcClient = rpc

            // --- Prune check (sync, no coroutines) ---
            val lastLdkHeight = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .getLong("last_ldk_sync_height", 0)
            if (lastLdkHeight > 0) {
                val chainInfo = rpc.getBlockchainInfoSync()
                if (chainInfo != null && !chainInfo.has("_rpc_error")) {
                    val pruneHeight = chainInfo.optLong("pruneheight", 0)
                    if (pruneHeight > lastLdkHeight) {
                        Log.w(TAG, "Pruned blocks detected: LDK last synced at $lastLdkHeight but prune height is $pruneHeight")
                        starting = false
                        // recoverPrunedBlocks is suspend — hand off to coroutine scope
                        scope.launch { recoverPrunedBlocks(rpcUser, rpcPassword, rpcPort) }
                        return
                    }
                }
            }

            // --- Wait for bitcoind RPC ready (sync, no coroutines) ---
            var rpcReady = false
            for (attempt in 1..30) {
                val info = rpc.getBlockchainInfoSync()
                if (info != null && !info.has("_rpc_error")) {
                    Log.i(TAG, "bitcoind RPC ready (attempt $attempt), height=${info.optLong("blocks")}")
                    rpcReady = true
                    break
                }
                Log.d(TAG, "Waiting for bitcoind RPC (attempt $attempt/30)...")
                Thread.sleep(2_000)
            }
            if (!rpcReady) throw Exception("bitcoind RPC not reachable after 60 seconds")

            // --- Stale chain state detection ---
            // If LDK's stored height is far behind bitcoind, synchronize_listeners
            // will hang trying to fetch pruned blocks. Proactively reset chain state
            // while preserving the seed and any channel data.
            storageDir = File(context.filesDir, STORAGE_DIR)
            if (storageDir.exists()) {
                val chainInfo = rpc.getBlockchainInfoSync()
                val bitcoindHeight = chainInfo?.optLong("blocks", 0) ?: 0
                val lastLdkHeight = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                    .getLong("last_ldk_sync_height", 0)
                val staleThreshold = 500 // blocks behind before we reset
                if (lastLdkHeight > 0 && bitcoindHeight > 0 && (bitcoindHeight - lastLdkHeight) > staleThreshold) {
                    Log.w(TAG, "LDK chain state is stale: LDK at $lastLdkHeight, bitcoind at $bitcoindHeight (${bitcoindHeight - lastLdkHeight} blocks behind)")
                    Log.w(TAG, "Requesting chain state reset to avoid synchronize_listeners hang (refused if channel monitors exist).")
                    recovery.resetChainState(storageDir)
                }
            }

            // --- Build LDK node ---
            if (!storageDir.exists()) storageDir.mkdirs()

            val builder = Builder()
            builder.setStorageDirPath(storageDir.absolutePath)

            // Route LDK internal Rust logs to Android logcat
            builder.setCustomLogger(object : LogWriter {
                override fun log(record: LogRecord) {
                    val tag = "LDK"
                    when (record.level) {
                        LogLevel.ERROR -> Log.e(tag, record.args)
                        LogLevel.WARN  -> Log.w(tag, record.args)
                        LogLevel.INFO  -> Log.i(tag, record.args)
                        LogLevel.DEBUG -> Log.d(tag, record.args)
                        LogLevel.TRACE -> Log.v(tag, record.args)
                        LogLevel.GOSSIP -> {} // Drop gossip logs (floods logcat ring buffer)
                    }
                }
            })

            builder.setNetwork(Network.BITCOIN)

            // Accept any peer's cooperative close fee (relay minimum = 253 sat/kw).
            // Prevents force-closes over minor fee disagreements.
            builder.setClosingFeeFloorSatPerKw(253u)

            builder.setChainSourceBitcoindRpc(
                "127.0.0.1",
                rpcPort.toUShort(),
                rpcUser,
                rpcPassword
            )

            // RGS for pathfinding graph — always enabled, Tor routes it through SOCKS5
            builder.setGossipSourceRgs(RGS_URL)

            // Configure Tor: when enabled, route ALL traffic through SOCKS5
            val torEnabled = com.pocketnode.tor.TorManager.enabledFlow.value
            if (torEnabled) {
                val torConfig = org.lightningdevkit.ldknode.TorConfig(
                    proxyAddress = "127.0.0.1:${com.pocketnode.tor.TorManager.SOCKS_PORT}",
                    routeAllTraffic = true
                )
                builder.setTorConfig(torConfig)
                Log.i(TAG, "Tor: all traffic routed through SOCKS5 proxy (port ${com.pocketnode.tor.TorManager.SOCKS_PORT})")
            }

            // --- Wallet birthday for seed recovery ---
            val seedFile = File(storageDir, "keys_seed")
            val birthdayFile = File(storageDir, "wallet_birthday")
            val hasMnemonic = File(storageDir, "mnemonic").exists()

            // Recovery: if wallet has a birthday but no persisted BDK state,
            val hasPersistedState = File(storageDir, "ldk_node_data.sqlite").exists()
            // set the wallet birthday height so BDK creates its first checkpoint
            // at that block instead of the current tip. This lets the chain
            // listener sync from the birthday forward, finding historical UTXOs
            // even on pruned nodes.
            val needsBirthdayScan = (seedFile.exists() || hasMnemonic) && !hasPersistedState && birthdayFile.exists()
            if (needsBirthdayScan) {
                val birthdayHeight = try { birthdayFile.readText().trim().toUInt() } catch (_: Exception) { 0u }
                if (birthdayHeight > 0u) {
                    Log.i(TAG, "Wallet birthday found: $birthdayHeight. Setting birthday checkpoint.")
                    builder.setWalletBirthdayHeight(birthdayHeight)
                }
            }

            // --- Seed / Entropy ---
            // Use BIP39 mnemonic as the canonical entropy source. This makes
            // the mnemonic alone sufficient for full wallet recovery (standard
            // BIP39 PBKDF2 derivation). Legacy wallets that only have a
            // keys_seed file (random 64 bytes) continue to work via fromSeedPath
            // but their mnemonic backup is incomplete.
            val mnemonicFile = File(storageDir, "mnemonic")
            val entropy: NodeEntropy
            if (mnemonicFile.exists()) {
                // BIP39 path: mnemonic -> PBKDF2 -> 64-byte seed (fully recoverable)
                // Delete any stale keys_seed so ldk-node re-derives from the mnemonic.
                // Different LDK versions may write incompatible keys_seed files.
                if (seedFile.exists()) {
                    seedFile.delete()
                    Log.w(TAG, "Deleted stale keys_seed to force re-derivation from mnemonic")
                }
                val words = mnemonicFile.readText().trim()
                entropy = NodeEntropy.Companion.fromBip39Mnemonic(words, "")
                Log.i(TAG, "Using BIP39 mnemonic entropy (${words.split(" ").size} words)")
            } else if (seedFile.exists()) {
                // Legacy path: raw 64-byte seed (mnemonic only covers first 32 bytes)
                entropy = NodeEntropy.fromSeedPath(seedFile.absolutePath)
                Log.w(TAG, "Using legacy keys_seed (mnemonic backup incomplete)")
            } else {
                // New wallet: generate BIP39 mnemonic and store it
                val mnemonic = org.lightningdevkit.ldknode.generateEntropyMnemonic(null)
                mnemonicFile.writeText(mnemonic)
                // Also backup immediately
                val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup")
                if (!backupDir.exists()) backupDir.mkdirs()
                File(backupDir, "mnemonic").writeText(mnemonic)
                entropy = NodeEntropy.Companion.fromBip39Mnemonic(mnemonic, "")
                Log.i(TAG, "New BIP39 wallet created (${mnemonic.split(" ").size} words)")
            }
            // --- Restore archived channel monitors ---
            // LDK may archive monitors it considers "fully resolved", but if the
            // commitment tx was never broadcast, the monitor is still needed.
            // Move any archived monitors back to active before building the node.
            val monitorsDir = File(storageDir, "monitors")
            val archivedDir = File(storageDir, "archived_monitors")
            if (archivedDir.exists() && archivedDir.isDirectory) {
                val archivedFiles = archivedDir.listFiles()
                if (archivedFiles != null && archivedFiles.isNotEmpty()) {
                    if (!monitorsDir.exists()) monitorsDir.mkdirs()
                    for (f in archivedFiles) {
                        val dest = File(monitorsDir, f.name)
                        if (!dest.exists()) {
                            f.copyTo(dest)
                            f.delete()
                            Log.w(TAG, "Restored archived channel monitor: ${f.name}")
                        } else {
                            Log.d(TAG, "Monitor already active, removing archive: ${f.name}")
                            f.delete()
                        }
                    }
                }
            }

            // --- One-time recovery: delete corrupt SQLite from failed auto-restore ---
            val corruptRecoveryPrefs = context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
            val resetVersion = corruptRecoveryPrefs.getInt("sqlite_reset_version", 0)
            if (resetVersion < 2) {
                corruptRecoveryPrefs.edit()
                    .putBoolean("needs_sqlite_reset", true)
                    .putInt("sqlite_reset_version", 2)
                    .apply()
            }
            if (corruptRecoveryPrefs.getBoolean("needs_sqlite_reset", false)) {
                val dbFile = File(storageDir, "ldk_node_data.sqlite")
                val walFile = File(storageDir, "ldk_node_data.sqlite-wal")
                val shmFile = File(storageDir, "ldk_node_data.sqlite-shm")
                dbFile.delete(); walFile.delete(); shmFile.delete()
                corruptRecoveryPrefs.edit().putBoolean("needs_sqlite_reset", false).apply()
                Log.w(TAG, "Deleted corrupt SQLite for fresh LDK start")
            }

            // Force WAL checkpoint before build to ensure all pending writes are flushed.
            // Prevents stale channel_manager reads after unexpected process termination.
            try {
                val walDbFile = File(storageDir, "ldk_node_data.sqlite")
                if (walDbFile.exists()) {
                    val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        walDbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                    // Integrity check first — detects corrupt WAL frames
                    val integ = db.rawQuery("PRAGMA integrity_check", null)
                    integ.use {
                        it.moveToFirst()
                        val result = it.getString(0)
                        if (result != "ok") Log.e(TAG, "SQLite integrity issue: $result")
                        else Log.i(TAG, "SQLite integrity: ok")
                    }
                    // TRUNCATE mode: checkpoint + delete the WAL file entirely
                    // Guarantees main DB file is the single source of truth on next open
                    val ckpt = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                    ckpt.use {
                        it.moveToFirst()
                        val busy = it.getInt(0)    // 0 = success, 1 = blocked
                        val total = it.getInt(1)   // total WAL frames
                        val merged = it.getInt(2)  // frames checkpointed
                        Log.i(TAG, "WAL checkpoint: busy=$busy total=$total merged=$merged")
                        if (busy == 1) Log.w(TAG, "WAL checkpoint blocked — another connection holds a read lock")
                    }
                    db.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "WAL checkpoint failed: ${e.message}")
            }

            val ldkNode = builder.build(entropy)

            // --- Start LDK (sync, blocks until tokio runtime is running) ---
            // After this point, LDK owns its tokio runtime. Coroutine machinery
            // is safe to use again.
            var lastError: Exception? = null
            for (attempt in 1..10) {
                try {
                    ldkNode.start()
                    // DISABLED: Auto monitor injection causes native crashes in LDK event processing.
                    // "Failed to process events" SIGABRT when stale monitors are injected.
                    // SCB is the correct recovery path, not monitor injection.
                    Log.i(TAG, "Post-start: SCB available for recovery, monitor injection disabled")
                    // Cleanup: remove SCB entries for channels that no longer exist
                    try {
                        val activeIds = ldkNode.listChannels().map { it.channelId }.toSet()
                        val scbEntries = scb.loadAll()
                        for (entry in scbEntries) {
                            if (entry.channelId !in activeIds) {
                                scb.removeChannel(entry.channelId)
                                Log.i(TAG, "SCB cleanup: removed stale entry ${entry.channelId.take(12)}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SCB cleanup failed: ${e.message}")
                    }
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    if (e.message?.contains("fee rate", ignoreCase = true) == true && attempt < 10) {
                        Log.w(TAG, "Fee estimates not ready, retry $attempt/10 in 60s...")
                        _state.value = _state.value.copy(
                            status = LightningState.Status.STARTING,
                            error = "Waiting for fee estimates from bitcoind ($attempt/10). " +
                                "This is normal on first start. Bitcoind needs mempool data to estimate fees."
                        )
                        Thread.sleep(60_000)
                    } else {
                        throw e
                    }
                }
            }
            if (lastError != null) throw lastError

            // Tor is now configured at build time via TorConfig — no runtime proxy setup needed

            node = ldkNode

            // Wire up extracted managers
            payments.node = ldkNode
            payments.handleEvents = { handleEvents() }
            channels.node = ldkNode
            channels.handleEvents = { handleEvents() }
            channels.updateState = { updateState() }
            channels.savePeerAnchorsCallback = { peerId, anchors -> savePeerAnchors(peerId, anchors) }
            onchain.node = ldkNode
            onchain.rpcClient = rpcClient
            recovery.stopNode = { stop() }
            recovery.startNode = { u, p, port -> start(u, p, port) }
            recovery.clearStartingFlag = { synchronized(this) { starting = false } }

            // One-time: clear stale peer channel limits from pre-fix caching bug
            val limitsPrefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
            if (!limitsPrefs.getBoolean("cache_v2_cleared", false)) {
                limitsPrefs.edit().clear().putBoolean("cache_v2_cleared", true).apply()
                Log.i(TAG, "Cleared stale peer channel limits cache")
            }

            val nodeId = ldkNode.nodeId()
            val initBalances = ldkNode.listBalances()
            Log.i(TAG, "Lightning node started. Node ID: $nodeId")
            Log.i(TAG, "Initial balances: onchain=${initBalances.totalOnchainBalanceSats} spendable=${initBalances.spendableOnchainBalanceSats} lightning=${initBalances.totalLightningBalanceSats}")

            // Initial state backup after successful start
            try {
                val stateBackup = StateBackupManager(context, storageDir)
                val initialBackup = stateBackup.backup()
                Log.i(TAG, "Initial state backup: ${if (initialBackup) "written" else "skipped"} | ${stateBackup.getStatus()}")
            } catch (e: Exception) {
                Log.e(TAG, "Initial state backup failed: ${e.message}")
            }

            // Diagnostic: list backup directory contents
            val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup")
            if (backupDir.exists()) {
                val files = backupDir.listFiles()?.map { f ->
                    "${f.name}${if (f.isDirectory) "/ (${f.listFiles()?.size ?: 0} files)" else " (${f.length()}b)"}"
                } ?: emptyList()
                Log.i(TAG, "Backup dir contents: $files")
                // Dump monitors_meta.json and copy monitor binary to Download
                val metaFile = File(backupDir, "monitors_meta.json")
                if (metaFile.exists()) {
                    Log.i(TAG, "monitors_meta.json: ${metaFile.readText()}")
                }
                val backupMonitorDir = File(backupDir, "monitors")
                if (backupMonitorDir.exists()) {
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                    backupMonitorDir.listFiles()?.forEach { f ->
                        val dest = File(downloadDir, f.name)
                        f.copyTo(dest, overwrite = true)
                        Log.i(TAG, "Copied monitor ${f.name} (${f.length()}b) to Download")
                    }
                }
            } else {
                Log.i(TAG, "No backup directory found")
            }
            // Also check active storage for monitors
            val activeMonitors = File(storageDir, "monitors")
            val archivedMonitors = File(storageDir, "archived_monitors")
            Log.i(TAG, "Active monitors: ${activeMonitors.listFiles()?.size ?: 0}, Archived: ${archivedMonitors.listFiles()?.size ?: 0}")
            // Check watchtower blobs (stored outside lightning/ dir, may survive restore)
            val wtBlobDir = File(context.filesDir, "watchtower_blobs")
            if (wtBlobDir.exists()) {
                val blobs = wtBlobDir.listFiles()?.map { "${it.name} (${it.length()}b)" } ?: emptyList()
                Log.i(TAG, "Watchtower blobs: $blobs")
            } else {
                Log.i(TAG, "No watchtower_blobs directory")
            }
            val wtKeyFile = File(context.filesDir, "watchtower_client_key")
            Log.i(TAG, "Watchtower client key: ${if (wtKeyFile.exists()) "${wtKeyFile.length()}b" else "missing"}")

            // Dump watchtower_prefs for diagnostics
            val wtDiagPrefs = context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE)
            val wtAll = wtDiagPrefs.all
            Log.i(TAG, "Watchtower prefs (${wtAll.size} entries):")
            for ((k, v) in wtAll) {
                val display = if (k.contains("pubkey") || k.contains("onion")) "${v.toString().take(20)}..." else v.toString()
                Log.i(TAG, "  wt_pref: $k = $display")
            }

            // After birthday-based recovery, check if balance was found and clean up
            if (needsBirthdayScan && initBalances.totalOnchainBalanceSats > 0UL) {
                Log.i(TAG, "Birthday recovery: found ${initBalances.totalOnchainBalanceSats} sats on first sync!")
                birthdayFile.delete()
            }

            // Check if a seed restore just happened and needs a recovery scan.
            // Uses SharedPreferences flag (survives file-level resets) — set in applyPendingSeedRestore,
            // cleared here after reading. Only scans once per restore.
            val prefs = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
            val needsRecoveryScan = prefs.getBoolean("pending_recovery_scan", false)
                && !birthdayFile.exists() && initBalances.totalOnchainBalanceSats == 0UL
            prefs.edit().putBoolean("pending_recovery_scan", false).apply()
            // Clean up legacy file marker if present
            val restoredMarker = File(storageDir, "restored_wallet")
            if (restoredMarker.exists()) restoredMarker.delete()

            // For recovery scans, derive BIP84 descriptors from the mnemonic
            // WITHOUT consuming LDK's address index. newAddress() permanently
            // advances the BDK index, so we never call it for scanning.
            val scanDescriptors = if (needsRecoveryScan) {
                val mnemonicFile = File(storageDir, "mnemonic")
                if (mnemonicFile.exists()) {
                    val words = mnemonicFile.readText().trim()
                    val recoveryService = WalletRecoveryService(context)
                    val descs = recoveryService.descriptorsFromMnemonic(words)
                    Log.i(TAG, "Derived ${descs.size} BIP84 descriptors for recovery scan (non-destructive)")
                    descs
                } else {
                    // Legacy: use WalletRecoveryService with raw seed
                    val recoveryService = WalletRecoveryService(context)
                    val seed = recoveryService.readSeed() ?: recoveryService.readBackupSeed()
                    if (seed != null) {
                        val masterKey = recoveryService.scanForFunds(seed, rpc)
                        Log.i(TAG, "Legacy recovery scan completed")
                    }
                    emptyList()
                }
            } else { emptyList() }

            try {
                val bestBlock = ldkNode.status().currentBestBlock
                Log.i(TAG, "LDK best block: height=${bestBlock.height} hash=${bestBlock.blockHash}")

                // Save wallet birthday on first creation (not on restore — scan handles that)
                if (!birthdayFile.exists() && !needsRecoveryScan) {
                    val height = bestBlock.height.toInt()
                    birthdayFile.writeText(height.toString())
                    Log.i(TAG, "Saved wallet birthday: $height")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get LDK status: ${e.message}")
            }

            // Watchtower sweep address
            watchtowerBridge = WatchtowerBridge(context)
            try {
                val prefs = context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE)
                val sweepKey = "sweep_address_${nodeId.take(16)}"
                var sweepAddr = prefs.getString(sweepKey, null)
                if (sweepAddr == null) {
                    sweepAddr = ldkNode.onchainPayment().newAddress()
                    prefs.edit().putString(sweepKey, sweepAddr).apply()
                    Log.i(TAG, "Generated watchtower sweep address: $sweepAddr")
                } else {
                    Log.i(TAG, "Reusing watchtower sweep address: $sweepAddr")
                }
                val scriptPubKey = recovery.bech32ToScriptPubKey(sweepAddr)
                if (scriptPubKey != null) ldkNode.watchtowerSetSweepAddress(scriptPubKey)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set watchtower sweep address: ${e.message}")
            }

            // Initialize watchtower bridge (create client key, test connectivity)
            Thread {
                try {
                    val reachable = watchtowerBridge?.initialize() ?: false
                    Log.i(TAG, "Watchtower bridge initialized: reachable=$reachable")
                    _state.value = _state.value.copy(watchtowerReachable = reachable)
                } catch (e: Exception) {
                    Log.w(TAG, "Watchtower bridge init failed: ${e.message}")
                    _state.value = _state.value.copy(watchtowerReachable = false)
                }
            }.start()

            lndHubServer = LndHubServer(context).also { it.start() }
            Log.i(TAG, "LNDHub server started on localhost:${LndHubServer.PORT}")

            // Wire up LDK height for burst sync (so it waits for LDK, not just bitcoind)
            com.pocketnode.power.PowerModeManager.getLdkHeight = {
                try { ldkNode.status().currentBestBlock.height.toLong() } catch (_: Exception) { 0L }
            }

            // --- Broadcast stuck force-close commitment transactions ---
            // If there are channel monitors with unbroadcast commitment txs
            // (e.g., force-close happened while network was off in burst mode),
            // broadcast them now. This is safe to call even with no pending closes.
            try {
                ldkNode.broadcastHolderCommitmentTxns()
                Log.i(TAG, "Broadcast holder commitment txns check complete")
            } catch (e: Exception) {
                Log.w(TAG, "broadcastHolderCommitmentTxns: ${e.message}")
            }

            // --- Background recovery scan fallback ---
            if (needsRecoveryScan && scanDescriptors.isNotEmpty()) {
                Thread({
                    recovery.backgroundRecoveryScanWithDescriptors(rpc, storageDir, rpcUser, rpcPassword, rpcPort, scanDescriptors)
                }, "recovery-scan").start()
            }

            val startPrefs = context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
            startPrefs.edit().putBoolean("lightning_was_running", true).apply()
            // Track consecutive starts without clean stop (crash detection)
            val crashCount = startPrefs.getInt("lightning_crash_count", 0) + 1
            startPrefs.edit().putInt("lightning_crash_count", crashCount).apply()
            Log.i(TAG, "Lightning started (crash counter: $crashCount)")

            // Clear stale error messages on successful start
            _state.value = _state.value.copy(lastChannelError = null)

            starting = false
            updateState()

            // Periodic state refresh, orphan detection, backup orchestration
            refreshLoop = StateRefreshLoop(context, storageDir).apply {
                getNode = { node }
                getRpcClient = { rpcClient }
                this.updateState = { this@LightningService.updateState() }
                updateBitcoindHeight = { lastBitcoindHeight = it }
                getState = { _state.value }
                stopAndRestart = { _, _ ->
                    // DISABLED: auto-restart risks channel loss. Log only.
                    Log.e(TAG, "Auto-restart requested (orphan rebroadcast) but DISABLED for safety")
                }
                this.drainWatchtowerBlobs = { this@LightningService.drainWatchtowerBlobs() }
                backupMonitors = { node?.let { recovery.backupChannelMonitors(it) } }
                hasCalledBroadcastThisSession = LightningService.hasCalledBroadcastThisSession
            }
            stateRefreshJob = refreshLoop.start(ioScope)

            // Sync watchdog
            val startHeight = try { ldkNode.status().currentBestBlock.height.toLong() } catch (_: Exception) { 0L }
            refreshLoop.startSyncWatchdog(
                startHeight, rpcUser, rpcPassword, rpcPort,
                isScanningForFunds = { _state.value.scanningForFunds },
                onReset = {
                    // DISABLED: auto-restart risks channel loss. Log only.
                    Log.e(TAG, "Sync watchdog triggered but auto-restart DISABLED for safety")
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Lightning node", e)
            starting = false
            val errorMsg = e.message ?: "Unknown error"

            if (errorMsg.contains("WalletSetupFailed") || errorMsg.contains("wallet")) {
                val recovered = recovery.tryRestoreSeedBackup()
                if (recovered) {
                    _state.value = _state.value.copy(
                        status = LightningState.Status.ERROR,
                        error = "Wallet seed restored from backup. Please try starting again."
                    )
                    return
                }
            }

            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = errorMsg
            )
        }
    }

    /**
     * Try to restore the most recent seed backup that differs from the current seed.
     * Returns true if a backup was restored.
     */
    // === Prune Recovery ===

    private suspend fun recoverPrunedBlocks(
        rpcUser: String, rpcPassword: String, rpcPort: Int
    ) {
        recovery.recoverPrunedBlocks(rpcUser, rpcPassword, rpcPort) { !starting }
    }

    /** Get connected Lightning peers. Returns list of PeerDetails from LDK. */
    fun networkGraph(): org.lightningdevkit.ldknode.NetworkGraph? = node?.networkGraph()

    // RGS fetching is now handled natively by ldk-node through TorConfig SOCKS5

        // RGS fetching via Tor is now handled natively by ldk-node through TorConfig SOCKS5

    fun listPeers(): List<org.lightningdevkit.ldknode.PeerDetails> {
        return try {
            node?.listPeers() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Get stored RPC credentials for restart. Returns (user, password) or null. */
    fun getStoredCredentials(): Pair<String, String>? {
        val prefs = context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
        val user = prefs.getString("rpc_user", null) ?: return null
        val pass = prefs.getString("rpc_password", null) ?: return null
        return Pair(user, pass)
    }

    fun stop() {
        try {
            stateRefreshJob?.cancel()
            stateRefreshJob = null
            lndHubServer?.stop()
            lndHubServer = null
            node?.stop()
            context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("lightning_was_running", false)
                .putInt("lightning_crash_count", 0)  // clean stop resets crash counter
                .apply()
            Log.i(TAG, "Lightning node stopped (crash counter reset)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Lightning node", e)
        } finally {
            node = null
            payments.node = null
            channels.node = null
            onchain.node = null
            _state.value = LightningState()
        }
    }

    fun updateState() {
        val n = node ?: return
        // Drain any pending events (ChannelClosed, etc.) before reading state
        try { handleEvents() } catch (_: Exception) {}
        try {
            val channels = n.listChannels()
            val balances = n.listBalances()

            try {
                val bestBlock = n.status().currentBestBlock
                val height = bestBlock.height.toLong()
                if (height > 0) {
                    context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                        .edit().putLong("last_ldk_sync_height", height).apply()
                }
            } catch (_: Exception) {}

            val bestBlock = n.status().currentBestBlock
            val ldkH = bestBlock.height.toLong()
            // Check if LDK is synced with bitcoind (use cached value to avoid NetworkOnMainThreadException)
            val bitcoindH = lastBitcoindHeight
            val synced = ldkH > 0 && (bitcoindH == 0L || ldkH >= bitcoindH)
            val outboundMsat = channels.sumOf { it.outboundCapacityMsat.toLong() }
            val inboundMsat = channels.sumOf { it.inboundCapacityMsat.toLong() }
            val usableChannels = channels.count { it.isUsable }
            Log.d(TAG, "updateState: onchain=${balances.totalOnchainBalanceSats} lightning=${balances.totalLightningBalanceSats} spendable=${balances.spendableOnchainBalanceSats} channels=${channels.size} usable=$usableChannels ldkHeight=${bestBlock.height} outbound=${outboundMsat/1000}sats inbound=${inboundMsat/1000}sats")
            channels.forEach { ch ->
                Log.d(TAG, "  ch=${ch.channelId.take(12)} usable=${ch.isUsable} ready=${ch.isChannelReady} value=${ch.channelValueSats} outbound=${ch.outboundCapacityMsat.toLong()/1000} inbound=${ch.inboundCapacityMsat.toLong()/1000} confs=${ch.confirmations} required=${ch.confirmationsRequired} peer=${ch.counterpartyNodeId.take(16)}")
            }

            // Auto-reconnect to channel peers that have enough confs but aren't ready.
            // This handles the case where the peer address wasn't persisted (e.g. from probe).
            // Rate-limited: only attempt every 60s per peer.
            val connectedPeers = try { n.listPeers().map { it.nodeId }.toSet() } catch (_: Exception) { emptySet() }
            val now = System.currentTimeMillis()
            channels.filter { ch ->
                !ch.isChannelReady
                && (ch.confirmations?.toInt() ?: 0) >= (ch.confirmationsRequired?.toInt() ?: 3)
                && ch.counterpartyNodeId !in connectedPeers
                && now - reconnectAttempts.getOrDefault(ch.counterpartyNodeId, 0L) > 60_000
            }.forEach { ch ->
                reconnectAttempts[ch.counterpartyNodeId] = now
                val peerId = ch.counterpartyNodeId
                Log.i(TAG, "Channel ${ch.channelId.take(12)} has ${ch.confirmations} confs but not ready. Attempting reconnect to $peerId")
                ioScope.launch {
                    try {
                        // 1. Check LDK's gossip graph first (already have the data locally)
                        var addr: String? = null
                        try {
                            val nodeInfo = n.networkGraph().node(peerId)
                            val addrs = nodeInfo?.announcementInfo?.addresses ?: emptyList()
                            addr = addrs.firstOrNull { it.contains(".onion") }
                                ?: addrs.firstOrNull()
                            if (addr != null) Log.i(TAG, "Found peer address from gossip graph: $addr")
                        } catch (_: Exception) {}
                        // 2. Fallback to mempool.space API
                        if (addr.isNullOrEmpty()) {
                            addr = NodeDirectory.getNodeDetails(peerId)?.address
                        }
                        if (addr != null && addr.isNotEmpty()) {
                            n.connect(peerId, addr, true)
                            Log.i(TAG, "Reconnected to channel peer $peerId at $addr")
                        } else {
                            Log.w(TAG, "No address found for channel peer $peerId")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Reconnect to $peerId failed: ${e.message}")
                    }
                }
            }

            // Routing readiness: graph size, peers, sync timestamps
            var currentGraphNodes = 0
            var currentLnPeerCount = 0
            var currentBtcPeerCount = 0
            try {
                val graph = n.networkGraph()
                val graphChannels = graph.listChannels().size
                currentGraphNodes = graph.listNodes().size
                val peers = n.listPeers()
                currentLnPeerCount = peers.size
                val status = n.status()
                val walletSync = status.latestLightningWalletSyncTimestamp
                val rgsSync = status.latestRgsSnapshotTimestamp
                Log.d(TAG, "routing: graph=${graphChannels}ch/${currentGraphNodes}nodes peers=${peers.size} walletSync=$walletSync rgsSync=$rgsSync")
            } catch (e: Exception) {
                Log.w(TAG, "routing info unavailable: ${e.message}")
            }
            // bitcoind peer count via RPC
            try {
                val rpc = rpcClient
                if (rpc != null) {
                    currentBtcPeerCount = kotlinx.coroutines.runBlocking { rpc.getPeerCount() }
                }
            } catch (_: Exception) {}

            val pending = channels.filter { it.isChannelReady == false }.map { ch ->
                LightningState.PendingChannel(
                    channelId = ch.channelId.toString().take(16),
                    peerAlias = ch.counterpartyNodeId.toString().take(16),
                    confirmations = ch.confirmations?.toInt() ?: 0,
                    confirmationsRequired = ch.confirmationsRequired?.toInt() ?: 3,
                    capacitySats = ch.channelValueSats.toLong()
                )
            }

            // Look up funding tx fee rates for pending channels (once per channel, async)
            val feeRates = _state.value.channelFeeRates.toMutableMap()
            val uncachedChannels = channels.filter { ch ->
                ch.fundingTxo != null && !feeRates.containsKey(ch.channelId)
            }
            if (uncachedChannels.isNotEmpty()) {
                val rpc = rpcClient
                if (rpc != null) {
                    scope.launch(Dispatchers.IO) {
                        for (ch in uncachedChannels) {
                            try {
                                val txid = ch.fundingTxo!!.txid
                                val entry = rpc.callSync("getmempoolentry", org.json.JSONArray().put(txid))
                                if (entry != null && !entry.has("_rpc_error")) {
                                    val vsize = entry.optLong("vsize", 0)
                                    val feeBtc = entry.optJSONObject("fees")?.optDouble("base", 0.0) ?: 0.0
                                    if (vsize > 0 && feeBtc > 0) {
                                        val feeSats = (feeBtc * 100_000_000).toLong()
                                        val satVb = feeSats / vsize
                                        Log.i(TAG, "Funding tx $txid fee: $feeSats sats, $vsize vB = $satVb sat/vB")
                                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                                            val updated = _state.value.channelFeeRates.toMutableMap()
                                            updated[ch.channelId] = satVb
                                            _state.value = _state.value.copy(channelFeeRates = updated)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to look up funding tx fee: ${e.message}")
                            }
                        }
                    }
                }
            }

            // Parse pending balances from channel closures
            val currentH = bestBlock.height.toInt()
            val pendingCloses = BalanceTracker.parsePendingCloses(
                balances.pendingBalancesFromChannelClosures, currentH
            )
            BalanceTracker.logPendingCloses(pendingCloses, balances.pendingBalancesFromChannelClosures.size)
            val pendingCloseTotalSats = pendingCloses.sumOf { it.amountSats }

            // Mark deposit address as used if total on-chain balance increased
            val newBalance = balances.totalOnchainBalanceSats.toLong()
            val prevTotal = _state.value.onchainBalanceSats + _state.value.pendingCloseSats
            if (newBalance > prevTotal && prevTotal >= 0) {
                onchain.getOnchainAddress() // trigger rotation check
                onchain.clearDepositAddress()
            }

            val displayOnchain = BalanceTracker.displayOnchain(newBalance, pendingCloseTotalSats)

            _state.value = LightningState(
                status = LightningState.Status.RUNNING,
                nodeId = n.nodeId(),
                onchainBalanceSats = displayOnchain,
                spendableOnchainSats = balances.spendableOnchainBalanceSats.toLong(),
                lightningBalanceSats = balances.totalLightningBalanceSats.toLong(),
                channelCount = channels.size,
                usableChannels = usableChannels,
                totalCapacitySats = channels.sumOf { it.channelValueSats.toLong() }.also {
                    val prefs = context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                    if (BalanceTracker.shouldUnlockLightningPay(channels.size)) {
                        try { prefs.edit().putBoolean("lightning_unlocked", true).apply() } catch (_: Exception) {}
                    } else if (BalanceTracker.shouldRelockLightningPay(channels.size, balances.totalLightningBalanceSats.toLong())) {
                        try { prefs.edit().putBoolean("lightning_unlocked", false).apply() } catch (_: Exception) {}
                    }
                },
                totalInboundSats = channels.sumOf {
                    (it.channelValueSats.toLong() - (it.outboundCapacityMsat.toLong() / 1000))
                },
                error = null,
                scanningForFunds = _state.value.scanningForFunds,
                scanProgress = _state.value.scanProgress,
                lastChannelError = _state.value.lastChannelError,
                watchtowerReachable = _state.value.watchtowerReachable,
                pendingChannels = pending,
                channelFeeRates = feeRates,
                pendingCloseSats = pendingCloseTotalSats,
                pendingCloseDetails = pendingCloses,
                ldkHeight = ldkH,
                bitcoindHeight = bitcoindH,
                chainSynced = synced,
                graphNodes = currentGraphNodes,
                lnPeerCount = currentLnPeerCount,
                btcPeerCount = currentBtcPeerCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update state", e)
        }
    }

    fun handleEvents() {
        val n = node ?: return
        try {
            val event = n.nextEvent() ?: return
            when (event) {
                is Event.PaymentSuccessful -> {
                    Log.i(TAG, "Payment successful: ${event.paymentId}")
                    event.paymentId?.let { payments.tracker.onPaymentSucceeded(it) }
                }
                is Event.PaymentFailed     -> {
                    Log.w(TAG, "Payment failed: ${event.paymentId} reason: ${event.reason}")
                    event.paymentId?.let { payments.tracker.onPaymentFailed(it, null, event.reason?.toString()) }
                }
                is Event.PaymentReceived   -> {
                    Log.i(TAG, "Payment received: ${event.amountMsat} msat")
                    onchain.getOnchainAddress() // trigger rotation check
                    onchain.clearDepositAddress()
                }
                is Event.ChannelPending    -> {
                    Log.i(TAG, "Channel pending: ${event.channelId} (funding txo: ${event.fundingTxo})")
                    // Save to Static Channel Backup
                    try {
                        val fundingTxo = event.fundingTxo
                        val peerId = event.counterpartyNodeId
                        if (fundingTxo != null && peerId != null) {
                            val peerAddrs = try {
                                val nodeInfo = node?.networkGraph()?.node(peerId)
                                nodeInfo?.announcementInfo?.addresses ?: emptyList()
                            } catch (_: Exception) { emptyList() }
                            val alias = try {
                                node?.networkGraph()?.node(peerId)?.announcementInfo?.alias ?: ""
                            } catch (_: Exception) { "" }
                            // Get capacity from channel list (not available on ChannelPending event)
                            val capacity = try {
                                node?.listChannels()?.find { it.channelId == event.channelId }
                                    ?.channelValueSats?.toLong() ?: 0
                            } catch (_: Exception) { 0L }
                            // Cache alias for channel list display
                            if (alias.isNotEmpty()) {
                                context.getSharedPreferences("peer_aliases", Context.MODE_PRIVATE)
                                    .edit().putString(peerId, alias).apply()
                            }
                            scb.saveChannel(
                                channelId = event.channelId,
                                peerPubkey = peerId,
                                peerAlias = alias,
                                fundingTxid = fundingTxo.txid,
                                fundingVout = fundingTxo.vout.toInt(),
                                capacitySats = capacity,
                                peerAddresses = peerAddrs
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SCB save failed: ${e.message}")
                    }
                    // Hold network while channel is pending confirmation
                    try {
                        com.pocketnode.power.PowerModeManager.getInstance(context).holdNetwork()
                        pendingChannelHolds.add(event.channelId)
                        Log.i(TAG, "Network held for pending channel ${event.channelId.take(12)}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to hold network for pending channel: ${e.message}")
                    }
                    channelEventLatch?.countDown()
                }
                is Event.ChannelReady      -> {
                    Log.i(TAG, "Channel ready: ${event.channelId}")
                    // Release network hold for this channel
                    if (pendingChannelHolds.remove(event.channelId)) {
                        try {
                            com.pocketnode.power.PowerModeManager.getInstance(context).releaseNetworkHold()
                            Log.i(TAG, "Network hold released for ready channel ${event.channelId.take(12)}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to release network hold: ${e.message}")
                        }
                    }
                    // Unlock Lightning Pay as default home screen
                    try {
                        context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("lightning_unlocked", true).apply()
                    } catch (_: Exception) {}
                    channelEventLatch?.countDown()
                }
                is Event.ChannelClosed     -> {
                    val reason = event.reason?.toString() ?: "unknown"
                    val result = channelEvents.processCloseReason(reason)
                    Log.w(TAG, "Channel closed: ${event.channelId} reason: ${result.displayReason}")
                    _state.value = _state.value.copy(lastChannelError = result.displayReason)
                    channelEvents.saveCloseInfo(event.channelId, result, event.counterpartyNodeId)
                    // Remove from SCB (channel is closing or closed, funds returning)
                    Log.i(TAG, "SCB remove: channelId=${event.channelId}")
                    scb.removeChannel(event.channelId)
                    // Release network hold if we were holding for this channel
                    if (pendingChannelHolds.remove(event.channelId)) {
                        try {
                            com.pocketnode.power.PowerModeManager.getInstance(context).releaseNetworkHold()
                            Log.i(TAG, "Network hold released for closed channel ${event.channelId.take(12)}")
                        } catch (_: Exception) {}
                    }
                    // Cache peer's min channel size if parsed from rejection
                    val peerId = event.counterpartyNodeId
                    if (peerId != null && result.minChannelSats != null) {
                        savePeerMinChannel(peerId, result.minChannelSats)
                    }
                    channelEventLatch?.countDown()
                }
                else -> Log.d(TAG, "Event: $event")
            }
            n.eventHandled()
            updateState()
            if (event is Event.ChannelPending || event is Event.ChannelReady
                || event is Event.ChannelClosed
                || event is Event.PaymentSuccessful || event is Event.PaymentReceived) {
                drainWatchtowerBlobs()
                node?.let { recovery.backupChannelMonitors(it) }
                // Flush WAL to disk immediately — prevents channel loss on process kill
                walCheckpoint()
                // Immediate state backup on channel events
                try {
                    StateBackupManager(context, storageDir).backup()
                    Log.i(TAG, "State backup triggered by channel event")
                } catch (e2: Exception) {
                    Log.e(TAG, "Channel event state backup failed: ${e2.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling events", e)
        }
    }

    // Peer channel limits — delegated to ChannelManager, kept as private bridges for handleEvents()
    private fun savePeerMinChannel(peerId: PublicKey, minSats: Long) = channels.savePeerMinChannel(peerId, minSats)
    private fun savePeerMinCeiling(peerId: String, acceptedSats: Long) = channels.savePeerMinCeiling(peerId, acceptedSats)
    private fun savePeerMinFloor(peerId: String, attemptedSats: Long) = channels.savePeerMinFloor(peerId, attemptedSats)

    private fun drainWatchtowerBlobs() {
        val n = node ?: return
        val bridge = watchtowerBridge ?: return
        Thread {
            try {
                val count = bridge.drainAndPush(n)
                if (count > 0) Log.i(TAG, "Watchtower: pushed $count justice blob(s) to tower")
            } catch (e: Exception) {
                Log.e(TAG, "Watchtower drain failed: ${e.message}")
            }
        }.start()
    }

    // === Payment operations (delegated to PaymentManager) ===

    fun payInvoice(invoiceStr: String, routeConfig: org.lightningdevkit.ldknode.RouteParametersConfig? = null): Result<String> = payments.payInvoice(invoiceStr, routeConfig)
    fun payOffer(offerStr: String, amountMsat: Long? = null): Result<String> = payments.payOffer(offerStr, amountMsat)
    fun createInvoice(amountMsat: Long, description: String, expirySecs: Int = 3600): Result<String> = payments.createInvoice(amountMsat, description, expirySecs)
    fun createOffer(amountMsat: Long, description: String): Result<String> = payments.createOffer(amountMsat, description)
    fun createVariableOffer(description: String): Result<String> = payments.createVariableOffer(description)
    fun listPayments(): List<PaymentDetails> = payments.listPayments()
    fun removePayment(id: String): Result<Unit> = payments.removePayment(id)

    // === Channel operations (delegated to ChannelManager) ===

    fun connectPeer(nodeId: String, address: String): Result<Unit> = channels.connectPeer(nodeId, address)
    fun openChannel(nodeId: String, address: String, amountSats: Long): Result<String> = channels.openChannel(nodeId, address, amountSats)
    fun closeChannel(userChannelId: String, counterpartyNodeId: String): Result<Unit> = channels.closeChannel(userChannelId, counterpartyNodeId)
    fun forceCloseChannel(userChannelId: String, counterpartyNodeId: String, reason: String = "User requested"): Result<Unit> = channels.forceCloseChannel(userChannelId, counterpartyNodeId, reason)
    fun listChannels(): List<ChannelDetails> = channels.listChannels()
    fun getPeerMinChannel(peerId: String): Long = channels.getPeerMinChannel(peerId)
    fun isPeerMinExact(peerId: String): Boolean = channels.isPeerMinExact(peerId)
    fun isPeerMinCeiling(peerId: String): Boolean = channels.isPeerMinCeiling(peerId)
    fun peerSupportsAnchors(nodeId: String): Boolean? = channels.peerSupportsAnchors(nodeId)
    fun getCachedPeerAnchors(peerId: String): Boolean? = channels.getCachedPeerAnchors(peerId)

    // === On-chain wallet (delegated to OnchainWallet) ===

    fun getOnchainAddress(): Result<String> = onchain.getOnchainAddress()
    fun markDepositAddressUsed(address: String) = onchain.markDepositAddressUsed(address)
    fun sendOnchain(address: String, amountSats: Long, feeRate: FeeRate? = null): Result<String> = onchain.sendOnchain(address, amountSats, feeRate)
    fun sendAllOnchain(address: String, feeRate: FeeRate? = null): Result<String> = onchain.sendAllOnchain(address, feeRate)

    fun getLdkHeight(): Int = try { node?.status()?.currentBestBlock?.height?.toInt() ?: 0 } catch (_: Exception) { 0 }
    fun isRunning(): Boolean = node != null

    // Old implementations removed — now in PaymentManager, ChannelManager, OnchainWallet
    // savePeerAnchors kept here for handleEvents callback
    private fun savePeerAnchors(peerId: String, supportsAnchors: Boolean) = channels.savePeerAnchors(peerId, supportsAnchors)


    // === Seed & Recovery (delegated to RecoveryManager) ===

    fun getSeedWords(): List<String>? = recovery.getSeedWords()
    fun hasSeed(): Boolean = recovery.hasSeed()
    fun restoreFromMnemonic(words: List<String>) = recovery.restoreFromMnemonic(
        words, node != null, { stop() }, { synchronized(this) { starting = false } }
    )
    fun backupChannelMonitors() = node?.let { recovery.backupChannelMonitors(it) }
}
