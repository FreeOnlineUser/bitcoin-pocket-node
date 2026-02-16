package com.pocketnode.util

import android.content.Context
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.snapshot.NodeSetupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detects which setup steps are already completed.
 * Used by the Config mode checklist to auto-tick finished steps.
 */
object SetupChecker {

    data class SetupState(
        val binaryInstalled: Boolean = false,
        val configGenerated: Boolean = false,
        val remoteNodeConfigured: Boolean = false,
        val snapshotLoaded: Boolean = false,
        val nodeSynced: Boolean = false,
        val nodeRunning: Boolean = false,
        val blockHeight: Long = -1,
        val headerHeight: Long = -1,
        val syncProgress: Double = 0.0
    )

    /**
     * Check all setup steps. Quick checks are synchronous;
     * RPC checks require the node to be running.
     */
    suspend fun check(context: Context): SetupState = withContext(Dispatchers.IO) {
        val binaryInstalled = checkBinary(context)
        val configGenerated = checkConfig(context)
        val remoteNodeConfigured = checkRemoteNode(context)

        // RPC checks (only if config exists — node might be running)
        var snapshotLoaded = false
        var nodeSynced = false
        var nodeRunning = false
        var blockHeight = -1L
        var headerHeight = -1L
        var syncProgress = 0.0

        if (configGenerated) {
            val creds = ConfigGenerator.readCredentials(context)
            if (creds != null) {
                try {
                    val rpc = BitcoinRpcClient(creds.first, creds.second)
                    val info = rpc.getBlockchainInfo()
                    if (info != null) {
                        nodeRunning = true
                        blockHeight = info.optLong("blocks", -1)
                        headerHeight = info.optLong("headers", -1)
                        syncProgress = info.optDouble("verificationprogress", 0.0)
                        val ibd = info.optBoolean("initialblockdownload", true)

                        // Snapshot is loaded if we have blocks beyond genesis
                        snapshotLoaded = blockHeight > 1000

                        // Synced if verification progress ~100% and not in IBD
                        nodeSynced = syncProgress >= 0.9999 && !ibd
                    }
                } catch (_: Exception) {
                    // Node not running or RPC not ready — that's fine
                }
            }
        }

        // Also check if chainstate exists on disk (snapshot loaded even if node stopped)
        if (!snapshotLoaded) {
            val dataDir = File(context.filesDir, "bitcoin")
            val chainstate = File(dataDir, "chainstate")
            snapshotLoaded = chainstate.exists() && chainstate.isDirectory &&
                    (chainstate.listFiles()?.isNotEmpty() == true)
        }

        SetupState(
            binaryInstalled = binaryInstalled,
            configGenerated = configGenerated,
            remoteNodeConfigured = remoteNodeConfigured,
            snapshotLoaded = snapshotLoaded,
            nodeSynced = nodeSynced,
            nodeRunning = nodeRunning,
            blockHeight = blockHeight,
            headerHeight = headerHeight,
            syncProgress = syncProgress
        )
    }

    private fun checkBinary(context: Context): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeLibDir, "libbitcoind.so")
        if (binary.exists() && binary.canExecute()) return true
        // Fallback: legacy location
        val legacy = File(context.filesDir, "bin/bitcoind")
        return legacy.exists() && legacy.canExecute()
    }

    private fun checkConfig(context: Context): Boolean {
        val conf = File(context.filesDir, "bitcoin/bitcoin.conf")
        return conf.exists()
    }

    private fun checkRemoteNode(context: Context): Boolean {
        val setupManager = NodeSetupManager(context)
        return setupManager.isSetupDone()
    }
}
