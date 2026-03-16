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
        val lightningBalanceSats: Long = 0,
        val channelCount: Int = 0,
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
        val chainSynced: Boolean = false,
        // Watchtower bridge connectivity
        val watchtowerReachable: Boolean? = null  // null=unknown, true=connected, false=failed
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
    private var rpcClient: BitcoinRpcClient? = null
    private var watchtowerBridge: WatchtowerBridge? = null
    private var lndHubServer: LndHubServer? = null
    private var stateRefreshJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Extracted managers (delegate pattern — public API stays the same)
    val payments = PaymentManager(context)
    val channels = ChannelManager(context)
    val onchain = OnchainWallet(context)

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
        try {
            // Apply pending seed restore before LDK touches any files
            applyPendingSeedRestore()

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
            val storageDir = File(context.filesDir, STORAGE_DIR)
            if (storageDir.exists()) {
                val chainInfo = rpc.getBlockchainInfoSync()
                val bitcoindHeight = chainInfo?.optLong("blocks", 0) ?: 0
                val lastLdkHeight = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                    .getLong("last_ldk_sync_height", 0)
                val staleThreshold = 500 // blocks behind before we reset
                if (lastLdkHeight > 0 && bitcoindHeight > 0 && (bitcoindHeight - lastLdkHeight) > staleThreshold) {
                    Log.w(TAG, "LDK chain state is stale: LDK at $lastLdkHeight, bitcoind at $bitcoindHeight (${bitcoindHeight - lastLdkHeight} blocks behind)")
                    Log.w(TAG, "Resetting chain state to avoid synchronize_listeners hang. Seed and channels preserved.")
                    resetChainState(storageDir)
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
                        LogLevel.GOSSIP -> Log.v(tag, "[gossip] ${record.args}")
                    }
                }
            })

            builder.setNetwork(Network.BITCOIN)

            builder.setChainSourceBitcoindRpc(
                "127.0.0.1",
                rpcPort.toUShort(),
                rpcUser,
                rpcPassword
            )

            builder.setGossipSourceRgs(RGS_URL)

            // --- Wallet birthday for seed recovery ---
            val seedFile = File(storageDir, "keys_seed")
            val birthdayFile = File(storageDir, "wallet_birthday")
            val hasPersistedState = File(storageDir, "bdk_wallet").exists()
            val hasMnemonic = File(storageDir, "mnemonic").exists()
            // Recovery: if wallet has a birthday but no persisted BDK state,
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

            val ldkNode = builder.build(entropy)

            // --- Start LDK (sync, blocks until tokio runtime is running) ---
            // After this point, LDK owns its tokio runtime. Coroutine machinery
            // is safe to use again.
            var lastError: Exception? = null
            for (attempt in 1..10) {
                try {
                    ldkNode.start()
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    if (e.message?.contains("fee rate", ignoreCase = true) == true && attempt < 10) {
                        Log.w(TAG, "Fee estimates not ready, retry $attempt/10 in 60s...")
                        Thread.sleep(60_000)
                    } else {
                        throw e
                    }
                }
            }
            if (lastError != null) throw lastError

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

            // Diagnostic: list backup directory contents
            val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup")
            if (backupDir.exists()) {
                val files = backupDir.listFiles()?.map { f ->
                    "${f.name}${if (f.isDirectory) "/ (${f.listFiles()?.size ?: 0} files)" else " (${f.length()}b)"}"
                } ?: emptyList()
                Log.i(TAG, "Backup dir contents: $files")
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
                val scriptPubKey = bech32ToScriptPubKey(sweepAddr)
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
                    backgroundRecoveryScanWithDescriptors(ldkNode, rpc, storageDir, rpcUser, rpcPassword, rpcPort, scanDescriptors)
                }, "recovery-scan").start()
            }

            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("lightning_was_running", true).apply()

            starting = false
            updateState()

            // Periodic state refresh — safe to use coroutines here, LDK is running
            var lastWalletSync = 0L
            stateRefreshJob = scope.launch {
                while (isActive) {
                    delay(10_000)
                    try {
                        // Update bitcoind height on background thread (avoids NetworkOnMainThreadException)
                        try {
                            lastBitcoindHeight = rpcClient?.callSync("getblockcount", org.json.JSONArray())?.optLong("value", 0) ?: 0
                        } catch (_: Exception) {}
                        updateState()
                        // Handle pending close: LDK's broadcast queue is fire-and-forget.
                        // If the commitment tx broadcast failed (e.g. network off during burst),
                        // the tx is lost from the queue. Only fix: restart LDK so it
                        // reconstructs from channel monitor and rebroadcasts on startup.
                        val st = _state.value
                        val now = System.currentTimeMillis()
                        val hasOrphanFunds = st.channelCount == 0 && st.lightningBalanceSats > 0
                        if ((hasOrphanFunds || st.pendingCloseSats > 0) && now - lastWalletSync > 300_000) {
                            lastWalletSync = now
                            if (hasOrphanFunds && st.pendingCloseDetails.isEmpty() && !hasAttemptedRebroadcastRestart) {
                                // Commitment tx likely never broadcast — restart LDK
                                Log.w(TAG, "Orphan lightning balance detected with no pending sweeps. Restarting LDK to rebroadcast commitment tx (one-time).")
                                hasAttemptedRebroadcastRestart = true
                                val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                                if (creds != null) {
                                    // Run on separate thread since stop() cancels the coroutine scope
                                    Thread({
                                        try {
                                            stop()
                                            Thread.sleep(2000)
                                            start(creds.first, creds.second)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to restart LDK for rebroadcast: ${e.message}")
                                        }
                                    }, "ldk-rebroadcast-restart").start()
                                    return@launch // Exit this coroutine, new one starts after restart
                                }
                            } else {
                                // Pending sweeps exist: broadcast stuck commitment txs + sync wallets
                                try {
                                    node?.broadcastHolderCommitmentTxns()
                                    node?.syncWallets()
                                    Log.d(TAG, "syncWallets+broadcast: triggered for pending close funds")
                                } catch (e: Exception) {
                                    Log.d(TAG, "syncWallets+broadcast: ${e.message}")
                                }
                            }
                        }
                        // Periodic monitor backup + watchtower drain (every 5 min if channels exist)
                        if (st.channelCount > 0 && now - lastWalletSync > 300_000) {
                            backupChannelMonitors()
                            drainWatchtowerBlobs()
                        }
                    } catch (_: Exception) {}
                }
            }

            // Sync watchdog: if LDK is behind bitcoind after 120s, chain state
            // may be corrupted. Only resets if LDK height < bitcoind height.
            // If LDK is at tip (no new blocks mined), that's normal — don't reset.
            val startHeight = try { ldkNode.status().currentBestBlock.height.toLong() } catch (_: Exception) { 0L }
            val watchdogRpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)
            Thread({
                Thread.sleep(120_000) // Wait 2 minutes
                try {
                    val ldkHeight = ldkNode.status().currentBestBlock.height.toLong()
                    val chainInfo = watchdogRpc.getBlockchainInfoSync()
                    val bitcoindHeight = chainInfo?.optLong("blocks", 0) ?: 0
                    if (ldkHeight < bitcoindHeight && ldkHeight <= startHeight && !_state.value.scanningForFunds) {
                        Log.e(TAG, "Sync watchdog: LDK stuck at $ldkHeight, bitcoind at $bitcoindHeight. Resetting chain state.")
                        stop()
                        resetChainState(storageDir)
                        // Clear the stale sync height so prune check doesn't block restart
                        context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                            .edit().putLong("last_ldk_sync_height", 0).apply()
                        // Restart on a new thread
                        Thread({
                            Thread.sleep(2_000)
                            start(rpcUser, rpcPassword, rpcPort)
                        }, "ldk-restart").start()
                    } else {
                        Log.i(TAG, "Sync watchdog: LDK at $ldkHeight, bitcoind at $bitcoindHeight. Sync healthy.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sync watchdog check failed: ${e.message}")
                }
            }, "ldk-sync-watchdog").start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Lightning node", e)
            starting = false
            val errorMsg = e.message ?: "Unknown error"

            if (errorMsg.contains("WalletSetupFailed") || errorMsg.contains("wallet")) {
                val recovered = tryRestoreSeedBackup()
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
    /**
     * Reset LDK chain state while preserving seed and channel data.
     * Deletes files that synchronize_listeners uses to determine its starting point.
     * On next start, LDK will sync from the current chain tip instead of the stale height.
     */
    private fun resetChainState(storageDir: File) {
        val preserveNames = setOf("keys_seed", "keys_seed.bak", "mnemonic", "channel_manager", "monitors", "wallet_birthday")
        storageDir.listFiles()?.forEach { file ->
            if (file.name !in preserveNames) {
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                Log.d(TAG, "resetChainState: ${if (deleted) "deleted" else "FAILED to delete"} ${file.name}")
            } else {
                Log.d(TAG, "resetChainState: preserved ${file.name}")
            }
        }
    }

    private fun tryRestoreSeedBackup(): Boolean {
        val storageDir = File(context.filesDir, STORAGE_DIR)
        val seedFile = File(storageDir, "keys_seed")
        val currentSeed = if (seedFile.exists()) seedFile.readBytes() else return false

        val backups = storageDir.listFiles()?.filter {
            it.name.startsWith("keys_seed.bak.")
        }?.sortedByDescending { it.lastModified() } ?: return false

        for (backup in backups) {
            val backupSeed = backup.readBytes()
            if (backupSeed.size == 64 && !backupSeed.contentEquals(currentSeed)) {
                seedFile.writeBytes(backupSeed)
                Log.i(TAG, "Auto-restored seed from ${backup.name}")
                return true
            }
        }
        return false
    }

    // === Prune Recovery ===

    private suspend fun recoverPrunedBlocks(
        rpcUser: String, rpcPassword: String, rpcPort: Int
    ) = withContext(Dispatchers.IO) {
        val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)

        val chainInfo = rpc.getBlockchainInfo() ?: run {
            Log.e(TAG, "Prune recovery: can't reach bitcoind")
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = "Cannot reach bitcoind for block recovery"
            )
            return@withContext
        }

        val pruneHeight = chainInfo.optLong("pruneheight", 0)
        val currentHeight = chainInfo.optLong("blocks", 0)
        if (pruneHeight <= 0 || currentHeight <= 0) {
            Log.e(TAG, "Prune recovery: invalid chain info (prune=$pruneHeight, height=$currentHeight)")
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = "Could not determine pruned block range"
            )
            return@withContext
        }

        val blocksNeeded = (currentHeight - pruneHeight).toInt().coerceAtLeast(1)
        Log.i(TAG, "Prune recovery: need to re-download ~$blocksNeeded blocks (prune height: $pruneHeight, tip: $currentHeight)")

        val networkMonitor = NetworkMonitor(context)
        if (networkMonitor.networkState.value != NetworkState.WIFI) {
            Log.i(TAG, "Prune recovery: waiting for WiFi...")
            _state.value = _state.value.copy(
                status = LightningState.Status.RECOVERING,
                recoveryBlocksNeeded = blocksNeeded,
                recoveryBlocksDone = 0,
                recoveryWaitingForWifi = true,
                error = null
            )
            while (networkMonitor.networkState.value != NetworkState.WIFI) {
                delay(5000)
                if (!starting) {
                    Log.i(TAG, "Prune recovery: cancelled while waiting for WiFi")
                    return@withContext
                }
            }
            Log.i(TAG, "Prune recovery: WiFi connected, starting recovery")
        }

        _state.value = _state.value.copy(
            status = LightningState.Status.RECOVERING,
            recoveryBlocksNeeded = blocksNeeded,
            recoveryBlocksDone = 0,
            recoveryWaitingForWifi = false,
            error = null
        )

        try {
            val hashResult = rpc.call("getblockhash", JSONArray().apply { put(pruneHeight) })
            val pruneHash = hashResult?.optString("value") ?: run {
                Log.e(TAG, "Prune recovery: can't get block hash at height $pruneHeight")
                _state.value = _state.value.copy(
                    status = LightningState.Status.ERROR,
                    error = "Could not get block hash for recovery"
                )
                return@withContext
            }

            Log.i(TAG, "Prune recovery: invalidating block $pruneHash at height $pruneHeight")
            rpc.call("invalidateblock", JSONArray().apply { put(pruneHash) })

            Log.i(TAG, "Prune recovery: reconsidering block to trigger re-download")
            rpc.call("reconsiderblock", JSONArray().apply { put(pruneHash) })

            var lastHeight = 0L
            var stallCount = 0
            while (true) {
                delay(2000)
                if (!starting) {
                    Log.i(TAG, "Prune recovery: cancelled during re-download")
                    return@withContext
                }
                val info = rpc.getBlockchainInfo() ?: continue
                val height = info.optLong("blocks", 0)

                if (height >= currentHeight) {
                    val done = (height - pruneHeight).toInt().coerceAtLeast(0)
                    _state.value = _state.value.copy(recoveryBlocksDone = done.coerceAtMost(blocksNeeded))
                    Log.i(TAG, "Prune recovery: complete! Chain at $height")
                    break
                }

                val done = (height - pruneHeight).toInt().coerceAtLeast(0)
                _state.value = _state.value.copy(recoveryBlocksDone = done.coerceAtMost(blocksNeeded))

                if (height == lastHeight) {
                    stallCount++
                    if (stallCount > 30) {
                        Log.w(TAG, "Prune recovery: stalled at $height for 60s")
                        _state.value = _state.value.copy(
                            status = LightningState.Status.ERROR,
                            error = "Block recovery stalled at $height. Try again on a faster connection."
                        )
                        return@withContext
                    }
                } else {
                    stallCount = 0
                }
                lastHeight = height
            }

            Log.i(TAG, "Prune recovery: retrying Lightning start...")
            _state.value = _state.value.copy(
                status = LightningState.Status.STARTING,
                recoveryBlocksNeeded = 0,
                recoveryBlocksDone = 0,
                error = null
            )
            delay(100)
            // Hand back to a plain thread for the retry — same rule applies
            Thread({ startInternal(rpcUser, rpcPassword, rpcPort) }, "ldk-start").start()

        } catch (e: Exception) {
            Log.e(TAG, "Prune recovery failed", e)
            _state.value = _state.value.copy(
                status = LightningState.Status.ERROR,
                error = "Block recovery failed: ${e.message}"
            )
        }
    }

    fun stop() {
        try {
            stateRefreshJob?.cancel()
            stateRefreshJob = null
            lndHubServer?.stop()
            lndHubServer = null
            node?.stop()
            context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .edit().putBoolean("lightning_was_running", false).apply()
            Log.i(TAG, "Lightning node stopped")
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
                Log.d(TAG, "  ch=${ch.channelId.take(12)} usable=${ch.isUsable} ready=${ch.isChannelReady} value=${ch.channelValueSats} outbound=${ch.outboundCapacityMsat.toLong()/1000} inbound=${ch.inboundCapacityMsat.toLong()/1000} confs=${ch.confirmations}")
            }
            // Routing readiness: graph size, peers, sync timestamps
            try {
                val graph = n.networkGraph()
                val graphChannels = graph.listChannels().size
                val graphNodes = graph.listNodes().size
                val peers = n.listPeers()
                val status = n.status()
                val walletSync = status.latestLightningWalletSyncTimestamp
                val rgsSync = status.latestRgsSnapshotTimestamp
                Log.d(TAG, "routing: graph=${graphChannels}ch/${graphNodes}nodes peers=${peers.size} walletSync=$walletSync rgsSync=$rgsSync")
            } catch (e: Exception) {
                Log.w(TAG, "routing info unavailable: ${e.message}")
            }

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
            val currentHeight = bestBlock.height.toInt()
            val currentH = bestBlock.height.toInt()
            val pendingCloses = balances.pendingBalancesFromChannelClosures.map { psb ->
                when (psb) {
                    is org.lightningdevkit.ldknode.PendingSweepBalance.PendingBroadcast ->
                        LightningState.PendingClose(psb.channelId ?: "", psb.amountSatoshis.toLong(), "Pending broadcast")
                    is org.lightningdevkit.ldknode.PendingSweepBalance.BroadcastAwaitingConfirmation ->
                        LightningState.PendingClose(psb.channelId ?: "", psb.amountSatoshis.toLong(),
                            "Awaiting confirmation", psb.latestBroadcastHeight.toInt(),
                            txid = psb.latestSpendingTxid)
                    is org.lightningdevkit.ldknode.PendingSweepBalance.AwaitingThresholdConfirmations -> {
                        val blocksLeft = maxOf(0, psb.confirmationHeight.toInt() - currentH)
                        val confs = if (blocksLeft > 0) 144 - blocksLeft else 144  // estimate based on typical 144-block delay
                        LightningState.PendingClose(psb.channelId ?: "", psb.amountSatoshis.toLong(),
                            "Awaiting threshold", psb.confirmationHeight.toInt(),
                            txid = psb.latestSpendingTxid, blocksRemaining = blocksLeft, confirmations = confs)
                    }
                    else -> LightningState.PendingClose("", 0, "Unknown")
                }
            }.filter { it.amountSats > 0 }
            if (pendingCloses.isNotEmpty() || balances.pendingBalancesFromChannelClosures.isNotEmpty()) {
                Log.d(TAG, "pendingCloses: raw=${balances.pendingBalancesFromChannelClosures.size} parsed=${pendingCloses.size}")
                pendingCloses.forEach { pc ->
                    Log.d(TAG, "  close: ${pc.amountSats}sats status=${pc.status} blocks=${pc.blocksRemaining} txid=${pc.txid?.take(16)}")
                }
                balances.pendingBalancesFromChannelClosures.forEach { psb ->
                    Log.d(TAG, "  raw: ${psb::class.simpleName}")
                }
            }
            val pendingCloseTotalSats = pendingCloses.sumOf { it.amountSats }

            // Mark deposit address as used if on-chain balance increased
            val prevBalance = _state.value.onchainBalanceSats
            val newBalance = balances.totalOnchainBalanceSats.toLong()
            if (newBalance > prevBalance && prevBalance >= 0) {
                onchain.getOnchainAddress() // trigger rotation check
                onchain.clearDepositAddress()
            }

            _state.value = LightningState(
                status = LightningState.Status.RUNNING,
                nodeId = n.nodeId(),
                onchainBalanceSats = newBalance,
                lightningBalanceSats = balances.totalLightningBalanceSats.toLong(),
                channelCount = channels.size,
                totalCapacitySats = channels.sumOf { it.channelValueSats.toLong() }.also {
                    val prefs = context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                    if (channels.isNotEmpty()) {
                        // Auto-unlock Lightning Pay when channels exist
                        try { prefs.edit().putBoolean("lightning_unlocked", true).apply() } catch (_: Exception) {}
                    } else if (balances.totalLightningBalanceSats == 0UL && pendingCloseTotalSats == 0L) {
                        // Re-lock when no channels, no lightning balance, no pending close
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
                pendingChannels = pending,
                channelFeeRates = feeRates,
                pendingCloseSats = pendingCloseTotalSats,
                pendingCloseDetails = pendingCloses,
                ldkHeight = ldkH,
                chainSynced = synced
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
                is Event.PaymentSuccessful -> Log.i(TAG, "Payment successful: ${event.paymentId}")
                is Event.PaymentFailed     -> Log.w(TAG, "Payment failed: ${event.paymentId}")
                is Event.PaymentReceived   -> {
                    Log.i(TAG, "Payment received: ${event.amountMsat} msat")
                    onchain.getOnchainAddress() // trigger rotation check
                    onchain.clearDepositAddress()
                }
                is Event.ChannelPending    -> {
                    Log.i(TAG, "Channel pending: ${event.channelId} (funding txo: ${event.fundingTxo})")
                    channelEventLatch?.countDown()
                }
                is Event.ChannelReady      -> {
                    Log.i(TAG, "Channel ready: ${event.channelId}")
                    // Unlock Lightning Pay as default home screen
                    try {
                        context.getSharedPreferences("pocketnode_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("lightning_unlocked", true).apply()
                    } catch (_: Exception) {}
                    channelEventLatch?.countDown()
                }
                is Event.ChannelClosed     -> {
                    val reason = event.reason?.toString() ?: "unknown"
                    Log.w(TAG, "Channel closed: ${event.channelId} reason: $reason")
                    _state.value = _state.value.copy(lastChannelError = reason)
                    // Save channel close info for tracking
                    try {
                        val closePrefs = context.getSharedPreferences("channel_closes", android.content.Context.MODE_PRIVATE)
                        closePrefs.edit()
                            .putString("close_${event.channelId}_reason", reason)
                            .putLong("close_${event.channelId}_time", System.currentTimeMillis())
                            .apply()
                        Log.i(TAG, "Saved close info for channel ${event.channelId}")
                    } catch (_: Exception) {}
                    // Cache peer's min channel size from rejection message
                    val peerId = event.counterpartyNodeId
                    if (peerId != null) {
                        // Parse min channel size from various rejection formats
                        val minBtc = Regex("""min chan size of (\d+\.\d+) BTC""").find(reason)?.groupValues?.get(1)
                        val minSatDirect = Regex("""min=(\d+)\s*sat""").find(reason)?.groupValues?.get(1)
                        if (minBtc != null) {
                            val minSats = (minBtc.toDouble() * 100_000_000).toLong()
                            savePeerMinChannel(peerId, minSats)
                        } else if (minSatDirect != null) {
                            savePeerMinChannel(peerId, minSatDirect.toLong())
                        }
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
                backupChannelMonitors()
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

    fun payInvoice(invoiceStr: String): Result<String> = payments.payInvoice(invoiceStr)
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


    // === Seed Backup & Restore ===

    fun getSeedWords(): List<String>? {
        // BIP39 path: read stored mnemonic directly
        val mnemonicFile = File(context.filesDir, "$STORAGE_DIR/mnemonic")
        if (mnemonicFile.exists()) {
            val words = mnemonicFile.readText().trim()
            Log.d(TAG, "getSeedWords: from mnemonic file (${words.split(" ").size} words)")
            return words.split(" ")
        }
        // Legacy path: derive mnemonic from keys_seed first 32 bytes
        val seedFile = File(context.filesDir, "$STORAGE_DIR/keys_seed")
        Log.d(TAG, "getSeedWords: checking ${seedFile.absolutePath}, exists=${seedFile.exists()}")
        if (!seedFile.exists()) return null
        val rawBytes = seedFile.readBytes()
        Log.d(TAG, "getSeedWords: read ${rawBytes.size} bytes (legacy keys_seed)")
        val entropy = when (rawBytes.size) {
            32 -> rawBytes
            64 -> rawBytes.sliceArray(0 until 32)
            else -> { Log.e(TAG, "Unexpected seed size: ${rawBytes.size}"); return null }
        }
        return try {
            Bip39.entropyToMnemonic(entropy, context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert seed to mnemonic: ${e.message}")
            null
        }
    }

    fun hasSeed(): Boolean =
        File(context.filesDir, "$STORAGE_DIR/mnemonic").exists() ||
        File(context.filesDir, "$STORAGE_DIR/keys_seed").exists()

    fun restoreFromMnemonic(words: List<String>) {
        val mnemonicStr = words.joinToString(" ")

        // Write the mnemonic to a pending restore file.
        // On next start(), applyPendingSeedRestore clears old state and writes the mnemonic file.
        // fromBip39Mnemonic handles the PBKDF2 derivation, making the mnemonic the single
        // source of truth for the entire wallet (on-chain + Lightning keys).
        val pendingFile = File(context.filesDir, "pending_mnemonic_restore")
        pendingFile.writeText(mnemonicStr)
        Log.i(TAG, "Pending mnemonic restore written (${words.size} words). Will apply on next start.")

        // Stop node if running — must fully stop so start() can run fresh
        if (node != null) {
            try { stop() } catch (_: Exception) {}
            // Reset the starting flag so start() won't return early
            synchronized(this) { starting = false }
        }

        // Only clear the sweep address (new node = new keys). Keep tower connection settings.
        val wtPrefs = context.getSharedPreferences("watchtower_prefs", MODE_PRIVATE)
        wtPrefs.edit().also { editor ->
            wtPrefs.all.keys.filter { it.startsWith("sweep_address_") }.forEach { editor.remove(it) }
        }.apply()
    }

    /**
     * Apply a pending seed restore before LDK starts.
     * Called at the top of start() before any file handles are opened.
     */
    private fun applyPendingSeedRestore() {
        // New BIP39 path: mnemonic file
        val pendingMnemonic = File(context.filesDir, "pending_mnemonic_restore")
        // Legacy path: raw seed bytes
        val pendingFile = File(context.filesDir, "pending_seed_restore")

        val mnemonicStr: String?
        val legacySeed64: ByteArray?

        if (pendingMnemonic.exists()) {
            mnemonicStr = pendingMnemonic.readText().trim()
            legacySeed64 = null
            if (mnemonicStr.split(" ").size !in listOf(12, 15, 18, 21, 24)) {
                Log.e(TAG, "Invalid pending mnemonic (${mnemonicStr.split(" ").size} words). Deleting.")
                pendingMnemonic.delete()
                return
            }
        } else if (pendingFile.exists()) {
            mnemonicStr = null
            legacySeed64 = pendingFile.readBytes()
            if (legacySeed64.size != 64) {
                Log.e(TAG, "Invalid pending seed restore file (${legacySeed64.size} bytes). Deleting.")
                pendingFile.delete()
                return
            }
        } else {
            return // Nothing to restore
        }

        val storageDir = File(context.filesDir, STORAGE_DIR)
        if (!storageDir.exists()) storageDir.mkdirs()

        // Clear ALL state (including wallet_birthday from previous wallet)
        storageDir.listFiles()?.forEach { file ->
            file.deleteRecursively()
            Log.d(TAG, "Cleared for restore: ${file.name}")
        }

        if (mnemonicStr != null) {
            // BIP39 path: write mnemonic file (fromBip39Mnemonic handles derivation)
            File(storageDir, "mnemonic").writeText(mnemonicStr)
            // Backup mnemonic
            val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup")
            if (!backupDir.exists()) backupDir.mkdirs()
            File(backupDir, "mnemonic").writeText(mnemonicStr)
            Log.i(TAG, "BIP39 mnemonic restore applied.")
        } else if (legacySeed64 != null) {
            // Legacy path: write raw keys_seed
            File(storageDir, "keys_seed").writeBytes(legacySeed64)
            Log.i(TAG, "Legacy seed restore applied.")
        }

        // Copy wallet_birthday from backup if available and mnemonic matches
        val backupBirthday = File(context.filesDir, "${STORAGE_DIR}_backup/wallet_birthday")
        val backupMnemonicFile = File(context.filesDir, "${STORAGE_DIR}_backup/mnemonic")
        if (backupBirthday.exists() && mnemonicStr != null && backupMnemonicFile.exists()) {
            if (backupMnemonicFile.readText().trim() == mnemonicStr) {
                backupBirthday.copyTo(File(storageDir, "wallet_birthday"), overwrite = true)
                Log.i(TAG, "Restored wallet birthday: ${backupBirthday.readText().trim()}")
            }
        }

        // Restore channel monitors from backup if mnemonic matches
        val backupMonitorsDir = File(context.filesDir, "${STORAGE_DIR}_backup/monitors")
        if (backupMonitorsDir.exists() && mnemonicStr != null && backupMnemonicFile.exists()) {
            if (backupMnemonicFile.readText().trim() == mnemonicStr) {
                val monitorsDir = File(storageDir, "monitors")
                if (!monitorsDir.exists()) monitorsDir.mkdirs()
                var restored = 0
                backupMonitorsDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".bin")) {
                        file.copyTo(File(monitorsDir, file.nameWithoutExtension), overwrite = true)
                        restored++
                    }
                }
                if (restored > 0) {
                    Log.i(TAG, "Restored $restored channel monitor(s) from backup")
                }
            }
        }

        pendingMnemonic.delete()
        pendingFile.delete()
        // Clear deposit address cache (stale after restore)
        context.getSharedPreferences("deposit_address", MODE_PRIVATE)
            .edit().clear().apply()
        onchain.clearDepositAddress()
        // Mark as restored wallet so we trigger recovery scan (not birthday save) on first start
        context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
            .edit().putBoolean("pending_recovery_scan", true).apply()
        Log.i(TAG, "Seed restore applied. Fresh wallet state ready.")
    }

    /**
     * Background UTXO scan fallback for wallets without a saved birthday.
     * Gets LDK's actual addresses, scans with scantxoutset, and if UTXOs
     * are found at an older height, saves the birthday and restarts LDK.
     */
    /**
     * Recovery scan using BIP84 xprv descriptors (non-destructive, no address index consumed).
     */
    private fun backgroundRecoveryScanWithDescriptors(
        @Suppress("UNUSED_PARAMETER") ldkNode: org.lightningdevkit.ldknode.Node,
        rpc: com.pocketnode.rpc.BitcoinRpcClient,
        storageDir: File,
        rpcUser: String,
        rpcPassword: String,
        rpcPort: Int,
        descriptors: List<String>
    ) {
        try {
            _state.value = _state.value.copy(scanningForFunds = true)

            val scanObjects = org.json.JSONArray()
            for (desc in descriptors) {
                val obj = org.json.JSONObject()
                obj.put("desc", desc)
                obj.put("range", 20)
                scanObjects.put(obj)
            }

            val birthdayHeight = tryScanTxOutSet(rpc, scanObjects)

            if (birthdayHeight == null) {
                Log.i(TAG, "Descriptor recovery scan: no funds found.")
                _state.value = _state.value.copy(scanningForFunds = false)
                File(storageDir, "restored_wallet").delete()
                return
            }

            File(storageDir, "wallet_birthday").writeText(birthdayHeight.toString())
            File(storageDir, "restored_wallet").delete()
            Log.i(TAG, "Descriptor recovery scan: saved birthday $birthdayHeight. Restarting LDK...")

            stop()
            Thread.sleep(500)
            resetChainState(storageDir)
            synchronized(this) { starting = false }
            start(rpcUser, rpcPassword, rpcPort)

        } catch (e: Exception) {
            Log.e(TAG, "Descriptor recovery scan failed: ${e.message}", e)
            _state.value = _state.value.copy(scanningForFunds = false)
        }
    }

    private fun backgroundRecoveryScan(
        @Suppress("UNUSED_PARAMETER") ldkNode: org.lightningdevkit.ldknode.Node,
        rpc: com.pocketnode.rpc.BitcoinRpcClient,
        storageDir: File,
        rpcUser: String,
        rpcPassword: String,
        rpcPort: Int,
        addresses: List<String>
    ) {
        try {
            _state.value = _state.value.copy(scanningForFunds = true)

            if (addresses.isEmpty()) {
                Log.w(TAG, "Background recovery scan: no addresses provided")
                return
            }

            // Build addr() descriptors
            val scanObjects = org.json.JSONArray()
            for (addr in addresses) {
                val obj = org.json.JSONObject()
                obj.put("desc", "addr($addr)")
                scanObjects.put(obj)
            }

            // Scan UTXO set for our addresses (~4 min on phone hardware)
            val birthdayHeight = tryScanTxOutSet(rpc, scanObjects)

            if (birthdayHeight == null) {
                Log.i(TAG, "Background recovery scan: no funds found in ${addresses.size} addresses.")
                _state.value = _state.value.copy(scanningForFunds = false)
                File(storageDir, "restored_wallet").delete()
                return
            }

            // Save the birthday and clear restored marker
            File(storageDir, "wallet_birthday").writeText(birthdayHeight.toString())
            File(storageDir, "restored_wallet").delete()
            Log.i(TAG, "Background recovery scan: saved birthday $birthdayHeight. Restarting LDK...")

            // Restart LDK with the birthday — must clear bdk_wallet so birthday takes effect
            stop()
            Thread.sleep(500)
            resetChainState(storageDir)
            synchronized(this) { starting = false }
            start(rpcUser, rpcPassword, rpcPort)

        } catch (e: Exception) {
            Log.e(TAG, "Background recovery scan failed: ${e.message}", e)
            _state.value = _state.value.copy(scanningForFunds = false)
        }
    }

    /**
     * Fast scan using block filter index (BIP 157). Returns in seconds.
     * Returns birthday height or null if no matches / index not available.
     */
    /**
     * Scan entire UTXO set for matching addresses (~4 min on phone hardware).
     * Returns birthday height or null if no UTXOs found.
     */
    private fun tryScanTxOutSet(
        rpc: com.pocketnode.rpc.BitcoinRpcClient,
        scanObjects: org.json.JSONArray
    ): Int? {
        try {
            Log.i(TAG, "Recovery scan: falling back to scantxoutset (UTXO set scan)...")

            // Abort any previous scan
            try {
                val abortParams = org.json.JSONArray()
                abortParams.put("abort")
                rpc.callSync("scantxoutset", abortParams, readTimeoutMs = 10_000)
            } catch (_: Exception) {}

            val params = org.json.JSONArray()
            params.put("start")
            params.put(scanObjects)

            // Poll progress while scan runs
            val progressPoller = Thread({
                try {
                    while (!Thread.interrupted()) {
                        Thread.sleep(2_000)
                        val statusParams = org.json.JSONArray()
                        statusParams.put("status")
                        val status = rpc.callSync("scantxoutset", statusParams, readTimeoutMs = 5_000)
                        if (status != null && status.has("progress")) {
                            val pct = status.getInt("progress")
                            _state.value = _state.value.copy(scanProgress = pct)
                        }
                    }
                } catch (_: InterruptedException) {}
                catch (_: Exception) {}
            }, "scan-progress")
            progressPoller.start()

            val result = rpc.callSync("scantxoutset", params, readTimeoutMs = 300_000)
            progressPoller.interrupt()

            if (result == null || result.has("_rpc_error")) {
                val errMsg = result?.optString("_rpc_error", "null response") ?: "null response"
                Log.e(TAG, "scantxoutset failed: $errMsg")
                return null
            }

            val totalAmount = result.optDouble("total_amount", 0.0)
            val totalSats = (totalAmount * 100_000_000).toLong()
            val unspents = result.optJSONArray("unspents") ?: org.json.JSONArray()

            if (totalSats == 0L || unspents.length() == 0) {
                return null
            }

            var minHeight = Int.MAX_VALUE
            for (i in 0 until unspents.length()) {
                val h = unspents.getJSONObject(i).getInt("height")
                if (h < minHeight) minHeight = h
            }

            val birthday = maxOf(minHeight - 10, 0)
            Log.i(TAG, "scantxoutset: found $totalSats sats in ${unspents.length()} UTXOs. " +
                    "Min height: $minHeight, birthday: $birthday")
            return birthday

        } catch (e: Exception) {
            Log.e(TAG, "scantxoutset failed: ${e.message}", e)
            return null
        }
    }

    private fun bech32ToScriptPubKey(address: String): ByteArray? {
        return try {
            val lower = address.lowercase()
            val hrpEnd = lower.lastIndexOf('1')
            if (hrpEnd < 1) return null
            val data = lower.substring(hrpEnd + 1)
            val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
            val values = data.map { charset.indexOf(it) }.filter { it >= 0 }
            if (values.size < 8) return null
            val payload = values.dropLast(6)
            if (payload.isEmpty()) return null
            val witnessVersion = payload[0]
            val program = convertBits(payload.drop(1), 5, 8, false) ?: return null
            val script = ByteArray(2 + program.size)
            script[0] = if (witnessVersion == 0) 0x00 else (0x50 + witnessVersion).toByte()
            script[1] = program.size.toByte()
            program.forEachIndexed { i, b -> script[2 + i] = b }
            script
        } catch (e: Exception) {
            Log.e(TAG, "bech32 decode failed: ${e.message}")
            null
        }
    }

    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0; var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value; bits += fromBits
            while (bits >= toBits) { bits -= toBits; result.add(((acc shr bits) and maxv).toByte()) }
        }
        if (pad && bits > 0) result.add(((acc shl (toBits - bits)) and maxv).toByte())
        else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) return null
        return result.toByteArray()
    }

    /**
     * Back up channel monitors to the backup directory.
     * This preserves the full serialized monitor data so it can survive
     * a seed restore (which clears the lightning/ directory).
     * Should be called on channel open, channel update, and periodically.
     */
    fun backupChannelMonitors() {
        val n = node ?: return
        try {
            val monitors = n.watchtowerExportMonitors()
            if (monitors.isEmpty()) return

            val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup/monitors")
            if (!backupDir.exists()) backupDir.mkdirs()

            for (monitor in monitors) {
                val file = File(backupDir, "${monitor.channelId}.bin")
                val existingSize = if (file.exists()) file.length() else 0
                // Only write if data changed (updateId increased or file missing)
                if (!file.exists() || file.length() != monitor.monitorBytes.size.toLong()) {
                    file.writeBytes(monitor.monitorBytes)
                    Log.i(TAG, "Backed up monitor ${monitor.channelId.take(12)} " +
                            "(update=${monitor.latestUpdateId}, ${monitor.monitorBytes.size}b)")
                }
            }

            // Also save monitor metadata for restore
            val metaFile = File(context.filesDir, "${STORAGE_DIR}_backup/monitors_meta.json")
            val meta = org.json.JSONArray()
            for (monitor in monitors) {
                val obj = org.json.JSONObject()
                obj.put("channel_id", monitor.channelId)
                obj.put("counterparty", monitor.counterpartyNodeId)
                obj.put("update_id", monitor.latestUpdateId)
                obj.put("size", monitor.monitorBytes.size)
                meta.put(obj)
            }
            metaFile.writeText(meta.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "backupChannelMonitors: ${e.message}")
        }
    }
}
