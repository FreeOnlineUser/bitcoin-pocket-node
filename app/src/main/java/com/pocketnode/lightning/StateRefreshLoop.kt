package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.*
import java.io.File

/**
 * Periodic state refresh, orphan detection, wallet sync, and backup orchestration.
 * Extracted from LightningService to isolate the main polling loop complexity.
 */
class StateRefreshLoop(
    private val context: Context,
    private val storageDir: File
) {
    companion object {
        private const val TAG = "StateRefreshLoop"
        private const val REFRESH_INTERVAL_MS = 10_000L
        private const val PERIODIC_TASK_INTERVAL_MS = 300_000L  // 5 minutes
    }

    // Callbacks into LightningService
    var getNode: (() -> org.lightningdevkit.ldknode.Node?)? = null
    var getRpcClient: (() -> BitcoinRpcClient?)? = null
    var updateState: (() -> Unit)? = null
    var updateBitcoindHeight: ((Long) -> Unit)? = null
    var getState: (() -> LightningService.LightningState)? = null
    var stopAndRestart: ((String, String) -> Unit)? = null
    var drainWatchtowerBlobs: (() -> Unit)? = null
    var backupMonitors: (() -> Unit)? = null

    // Flags (shared with LightningService)
    @Volatile var hasAttemptedRebroadcastRestart = false
    @Volatile var hasCalledBroadcastThisSession = false

    private var job: Job? = null

    /**
     * Start the periodic refresh loop on an IO dispatcher.
     */
    fun start(scope: CoroutineScope): Job {
        val j = scope.launch {
            var lastPeriodicTask = 0L
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                try {
                    refreshBitcoindHeight()
                    updateState?.invoke()
                    handleOrphanFunds(lastPeriodicTask)?.let { lastPeriodicTask = it }
                    lastPeriodicTask = runPeriodicTasks(lastPeriodicTask)
                } catch (_: Exception) {}
            }
        }
        job = j
        return j
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Update cached bitcoind block height.
     */
    private fun refreshBitcoindHeight() {
        try {
            val height = getRpcClient?.invoke()
                ?.callSync("getblockcount", org.json.JSONArray())
                ?.optLong("value", 0) ?: 0
            updateBitcoindHeight?.invoke(height)
        } catch (_: Exception) {}
    }

    /**
     * Detect orphan lightning balance (channels=0 but lightning>0) and handle it.
     * Returns updated lastPeriodicTask timestamp if wallet sync was triggered.
     */
    private fun handleOrphanFunds(lastPeriodicTask: Long): Long? {
        val st = getState?.invoke() ?: return null
        val now = System.currentTimeMillis()
        val hasOrphanFunds = st.channelCount == 0 && st.lightningBalanceSats > 0

        if ((hasOrphanFunds || st.pendingCloseSats > 0) && now - lastPeriodicTask > PERIODIC_TASK_INTERVAL_MS) {
            if (hasOrphanFunds && st.pendingCloseDetails.isEmpty()
                && !hasAttemptedRebroadcastRestart && !hasCalledBroadcastThisSession) {
                // Commitment tx likely never broadcast — request restart
                Log.w(TAG, "Orphan lightning balance detected with no pending sweeps. Requesting LDK restart for rebroadcast (one-time).")
                hasAttemptedRebroadcastRestart = true
                val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
                if (creds != null) {
                    stopAndRestart?.invoke(creds.first, creds.second)
                }
                return null // Signal caller to exit loop (restart creates new loop)
            } else {
                // Pending sweeps exist: broadcast stuck commitment txs + sync wallets
                try {
                    getNode?.invoke()?.broadcastHolderCommitmentTxns()
                    getNode?.invoke()?.syncWallets()
                    Log.d(TAG, "syncWallets+broadcast: triggered for pending close funds")
                } catch (e: Exception) {
                    Log.d(TAG, "syncWallets+broadcast: ${e.message}")
                }
            }
            return now
        }
        return null
    }

    /**
     * Run periodic tasks: state backup, monitor backup, watchtower drain.
     * Returns updated timestamp.
     */
    private fun runPeriodicTasks(lastRun: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastRun < PERIODIC_TASK_INTERVAL_MS) return lastRun

        // Rolling state backup (critical SQLite rows)
        try {
            val backed = StateBackupManager(context, storageDir).backup()
            if (backed) Log.i(TAG, "Periodic state backup complete")
        } catch (e: Exception) {
            Log.e(TAG, "State backup failed: ${e.message}")
        }

        // Legacy monitor backup + watchtower
        val st = getState?.invoke()
        if (st != null && st.channelCount > 0) {
            backupMonitors?.invoke()
            drainWatchtowerBlobs?.invoke()
        }

        return now
    }

    /**
     * Sync watchdog: if LDK is behind bitcoind after a delay, chain state
     * may be corrupted. Only resets if LDK height < bitcoind height.
     */
    fun startSyncWatchdog(
        startHeight: Long,
        rpcUser: String, rpcPassword: String, rpcPort: Int,
        isScanningForFunds: () -> Boolean,
        onReset: () -> Unit
    ) {
        Thread({
            Thread.sleep(120_000) // Wait 2 minutes
            try {
                val node = getNode?.invoke() ?: return@Thread
                val ldkHeight = node.status().currentBestBlock.height.toLong()
                val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)
                val chainInfo = rpc.getBlockchainInfoSync()
                val bitcoindHeight = chainInfo?.optLong("blocks", 0) ?: 0
                if (ldkHeight < bitcoindHeight && ldkHeight <= startHeight && !isScanningForFunds()) {
                    Log.e(TAG, "Sync watchdog: LDK stuck at $ldkHeight, bitcoind at $bitcoindHeight. Resetting chain state.")
                    onReset()
                } else {
                    Log.i(TAG, "Sync watchdog: LDK at $ldkHeight, bitcoind at $bitcoindHeight. Sync healthy.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync watchdog check failed: ${e.message}")
            }
        }, "ldk-sync-watchdog").start()
    }
}
