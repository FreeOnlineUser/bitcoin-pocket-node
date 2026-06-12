package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages seed backup/restore, wallet recovery scanning, and channel monitor backup.
 * Extracted from LightningService to isolate recovery complexity.
 */
class RecoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "RecoveryManager"
        private const val STORAGE_DIR = "lightning"
    }

    /** State flow reference for scan progress updates, set by LightningService */
    lateinit var stateFlow: MutableStateFlow<LightningService.LightningState>

    /** Callbacks into LightningService for restart after recovery */
    var stopNode: (() -> Unit)? = null
    var startNode: ((String, String, Int) -> Unit)? = null
    var clearStartingFlag: (() -> Unit)? = null

    // ── Seed Words ───────────────────────────────────────────────────

    fun getSeedWords(): List<String>? {
        val mnemonicFile = File(context.filesDir, "$STORAGE_DIR/mnemonic")
        if (mnemonicFile.exists()) {
            val words = mnemonicFile.readText().trim()
            Log.d(TAG, "getSeedWords: from mnemonic file (${words.split(" ").size} words)")
            return words.split(" ")
        }
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

    // ── Seed Restore ─────────────────────────────────────────────────

    fun restoreFromMnemonic(words: List<String>, nodeRunning: Boolean, stopFn: () -> Unit, clearStartFn: () -> Unit) {
        val mnemonicStr = words.joinToString(" ")

        val pendingFile = File(context.filesDir, "pending_mnemonic_restore")
        pendingFile.writeText(mnemonicStr)
        Log.i(TAG, "Pending mnemonic restore written (${words.size} words). Will apply on next start.")

        if (nodeRunning) {
            try { stopFn() } catch (_: Exception) {}
            clearStartFn()
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
    fun applyPendingSeedRestore(onchainWallet: OnchainWallet) {
        val pendingMnemonic = File(context.filesDir, "pending_mnemonic_restore")
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
            File(storageDir, "mnemonic").writeText(mnemonicStr)
            val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup")
            if (!backupDir.exists()) backupDir.mkdirs()
            File(backupDir, "mnemonic").writeText(mnemonicStr)
            Log.i(TAG, "BIP39 mnemonic restore applied.")
        } else if (legacySeed64 != null) {
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
        context.getSharedPreferences("deposit_address", MODE_PRIVATE)
            .edit().clear().apply()
        onchainWallet.clearDepositAddress()
        context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
            .edit().putBoolean("pending_recovery_scan", true).apply()
        Log.i(TAG, "Seed restore applied. Fresh wallet state ready.")
    }

    // ── Recovery Scanning ────────────────────────────────────────────

    fun backgroundRecoveryScanWithDescriptors(
        rpc: BitcoinRpcClient,
        storageDir: File,
        rpcUser: String,
        rpcPassword: String,
        rpcPort: Int,
        descriptors: List<String>
    ) {
        try {
            stateFlow.value = stateFlow.value.copy(scanningForFunds = true)

            val scanObjects = JSONArray()
            for (desc in descriptors) {
                val obj = JSONObject()
                obj.put("desc", desc)
                obj.put("range", 20)
                scanObjects.put(obj)
            }

            val birthdayHeight = tryScanTxOutSet(rpc, scanObjects)

            if (birthdayHeight == null) {
                Log.i(TAG, "Descriptor recovery scan: no funds found.")
                stateFlow.value = stateFlow.value.copy(scanningForFunds = false)
                File(storageDir, "restored_wallet").delete()
                return
            }

            File(storageDir, "wallet_birthday").writeText(birthdayHeight.toString())
            File(storageDir, "restored_wallet").delete()
            Log.i(TAG, "Descriptor recovery scan: saved birthday $birthdayHeight. Restarting LDK...")

            stopNode?.invoke()
            Thread.sleep(500)
            resetChainState(storageDir)
            clearStartingFlag?.invoke()
            startNode?.invoke(rpcUser, rpcPassword, rpcPort)

        } catch (e: Exception) {
            Log.e(TAG, "Descriptor recovery scan failed: ${e.message}", e)
            stateFlow.value = stateFlow.value.copy(scanningForFunds = false)
        }
    }

    fun backgroundRecoveryScan(
        rpc: BitcoinRpcClient,
        storageDir: File,
        rpcUser: String,
        rpcPassword: String,
        rpcPort: Int,
        addresses: List<String>
    ) {
        try {
            stateFlow.value = stateFlow.value.copy(scanningForFunds = true)

            if (addresses.isEmpty()) {
                Log.w(TAG, "Background recovery scan: no addresses provided")
                return
            }

            val scanObjects = JSONArray()
            for (addr in addresses) {
                val obj = JSONObject()
                obj.put("desc", "addr($addr)")
                scanObjects.put(obj)
            }

            val birthdayHeight = tryScanTxOutSet(rpc, scanObjects)

            if (birthdayHeight == null) {
                Log.i(TAG, "Background recovery scan: no funds found in ${addresses.size} addresses.")
                stateFlow.value = stateFlow.value.copy(scanningForFunds = false)
                File(storageDir, "restored_wallet").delete()
                return
            }

            File(storageDir, "wallet_birthday").writeText(birthdayHeight.toString())
            File(storageDir, "restored_wallet").delete()
            Log.i(TAG, "Background recovery scan: saved birthday $birthdayHeight. Restarting LDK...")

            stopNode?.invoke()
            Thread.sleep(500)
            resetChainState(storageDir)
            clearStartingFlag?.invoke()
            startNode?.invoke(rpcUser, rpcPassword, rpcPort)

        } catch (e: Exception) {
            Log.e(TAG, "Background recovery scan failed: ${e.message}", e)
            stateFlow.value = stateFlow.value.copy(scanningForFunds = false)
        }
    }

    fun tryScanTxOutSet(rpc: BitcoinRpcClient, scanObjects: JSONArray): Int? {
        try {
            Log.i(TAG, "Recovery scan: falling back to scantxoutset (UTXO set scan)...")

            try {
                val abortParams = JSONArray()
                abortParams.put("abort")
                rpc.callSync("scantxoutset", abortParams, readTimeoutMs = 10_000)
            } catch (_: Exception) {}

            val params = JSONArray()
            params.put("start")
            params.put(scanObjects)

            val progressPoller = Thread({
                try {
                    while (!Thread.interrupted()) {
                        Thread.sleep(2_000)
                        val statusParams = JSONArray()
                        statusParams.put("status")
                        val status = rpc.callSync("scantxoutset", statusParams, readTimeoutMs = 5_000)
                        if (status != null && status.has("progress")) {
                            val pct = status.getInt("progress")
                            stateFlow.value = stateFlow.value.copy(scanProgress = pct)
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
            val unspents = result.optJSONArray("unspents") ?: JSONArray()

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

    // ── Chain State Reset ────────────────────────────────────────────

    fun resetChainState(storageDir: File) {
        // Fund-safety guard: with the SQLite store, channel_manager and all
        // monitors live inside ldk_node_data.sqlite. If any monitors exist,
        // deleting the store destroys channel state (HARD RULE: never
        // auto-delete wallet/channel state). A stale sync is recoverable;
        // lost monitors are not.
        val monitorCount = countStoredMonitors(storageDir)
        if (monitorCount != 0) {
            Log.w(TAG, "resetChainState: REFUSING to reset. " +
                    if (monitorCount > 0) "$monitorCount channel monitor(s) in store."
                    else "Monitor check failed; assuming monitors present.")
            return
        }
        val preserveNames = setOf("keys_seed", "keys_seed.bak", "mnemonic", "channel_manager", "monitors", "wallet_birthday", "channel_backups.json")
        storageDir.listFiles()?.forEach { file ->
            if (file.name !in preserveNames) {
                val deleted = file.deleteRecursively()
                Log.d(TAG, "resetChainState: ${if (deleted) "deleted" else "FAILED to delete"} ${file.name}")
            } else {
                Log.d(TAG, "resetChainState: preserved ${file.name}")
            }
        }
    }

    /**
     * Counts channel monitors inside the LDK SQLite store.
     * Returns 0 only when the store is confirmed monitor-free (or absent);
     * returns -1 when the check fails, so callers fail safe.
     */
    private fun countStoredMonitors(storageDir: File): Int {
        val sqliteFile = File(storageDir, "ldk_node_data.sqlite")
        if (!sqliteFile.exists()) return 0
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                sqliteFile.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(
                    "SELECT COUNT(*) FROM ldk_node_data WHERE primary_namespace='monitors'",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else -1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "countStoredMonitors failed: ${e.message}")
            -1
        }
    }

    // ── Channel Monitor Backup ───────────────────────────────────────

    fun backupChannelMonitors(node: org.lightningdevkit.ldknode.Node) {
        try {
            val monitors = node.watchtowerExportMonitors()
            if (monitors.isEmpty()) return

            val backupDir = File(context.filesDir, "${STORAGE_DIR}_backup/monitors")
            if (!backupDir.exists()) backupDir.mkdirs()

            for (monitor in monitors) {
                val file = File(backupDir, "${monitor.channelId}.bin")
                if (!file.exists() || file.length() != monitor.monitorBytes.size.toLong()) {
                    file.writeBytes(monitor.monitorBytes)
                    Log.i(TAG, "Backed up monitor ${monitor.channelId.take(12)} " +
                            "(update=${monitor.latestUpdateId}, ${monitor.monitorBytes.size}b)")
                }
            }

            val metaFile = File(context.filesDir, "${STORAGE_DIR}_backup/monitors_meta.json")
            val meta = JSONArray()
            for (monitor in monitors) {
                val obj = JSONObject()
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

    // ── Seed Backup Restore (fallback for corrupted wallet) ──────────

    fun tryRestoreSeedBackup(): Boolean {
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

    // ── Bech32 Helpers ───────────────────────────────────────────────

    fun bech32ToScriptPubKey(address: String): ByteArray? {
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

    // ── Prune Recovery ───────────────────────────────────────────────

    /**
     * Recover from pruned blocks by triggering bitcoind to re-download them.
     * Waits for WiFi if on cellular, shows progress, then restarts LDK.
     */
    suspend fun recoverPrunedBlocks(
        rpcUser: String, rpcPassword: String, rpcPort: Int,
        isCancelled: () -> Boolean
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)

        val chainInfo = rpc.getBlockchainInfo() ?: run {
            Log.e(TAG, "Prune recovery: can't reach bitcoind")
            stateFlow.value = stateFlow.value.copy(
                status = LightningService.LightningState.Status.ERROR,
                error = "Cannot reach bitcoind for block recovery"
            )
            return@withContext
        }

        val pruneHeight = chainInfo.optLong("pruneheight", 0)
        val currentHeight = chainInfo.optLong("blocks", 0)
        if (pruneHeight <= 0 || currentHeight <= 0) {
            Log.e(TAG, "Prune recovery: invalid chain info (prune=$pruneHeight, height=$currentHeight)")
            stateFlow.value = stateFlow.value.copy(
                status = LightningService.LightningState.Status.ERROR,
                error = "Could not determine pruned block range"
            )
            return@withContext
        }

        val blocksNeeded = (currentHeight - pruneHeight).toInt().coerceAtLeast(1)
        Log.i(TAG, "Prune recovery: need to re-download ~$blocksNeeded blocks (prune height: $pruneHeight, tip: $currentHeight)")

        val networkMonitor = com.pocketnode.network.NetworkMonitor.getInstance(context)
        if (networkMonitor.networkState.value != com.pocketnode.network.NetworkState.WIFI) {
            Log.i(TAG, "Prune recovery: waiting for WiFi...")
            stateFlow.value = stateFlow.value.copy(
                status = LightningService.LightningState.Status.RECOVERING,
                recoveryBlocksNeeded = blocksNeeded,
                recoveryBlocksDone = 0,
                recoveryWaitingForWifi = true,
                error = null
            )
            while (networkMonitor.networkState.value != com.pocketnode.network.NetworkState.WIFI) {
                kotlinx.coroutines.delay(5000)
                if (isCancelled()) {
                    Log.i(TAG, "Prune recovery: cancelled while waiting for WiFi")
                    return@withContext
                }
            }
            Log.i(TAG, "Prune recovery: WiFi connected, starting recovery")
        }

        stateFlow.value = stateFlow.value.copy(
            status = LightningService.LightningState.Status.RECOVERING,
            recoveryBlocksNeeded = blocksNeeded,
            recoveryBlocksDone = 0,
            recoveryWaitingForWifi = false,
            error = null
        )

        try {
            val hashResult = rpc.call("getblockhash", JSONArray().apply { put(pruneHeight) })
            val pruneHash = hashResult?.optString("value") ?: run {
                Log.e(TAG, "Prune recovery: can't get block hash at height $pruneHeight")
                stateFlow.value = stateFlow.value.copy(
                    status = LightningService.LightningState.Status.ERROR,
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
                kotlinx.coroutines.delay(2000)
                if (isCancelled()) {
                    Log.i(TAG, "Prune recovery: cancelled during re-download")
                    return@withContext
                }
                val info = rpc.getBlockchainInfo() ?: continue
                val height = info.optLong("blocks", 0)

                if (height >= currentHeight) {
                    val done = (height - pruneHeight).toInt().coerceAtLeast(0)
                    stateFlow.value = stateFlow.value.copy(recoveryBlocksDone = done.coerceAtMost(blocksNeeded))
                    Log.i(TAG, "Prune recovery: complete! Chain at $height")
                    break
                }

                val done = (height - pruneHeight).toInt().coerceAtLeast(0)
                stateFlow.value = stateFlow.value.copy(recoveryBlocksDone = done.coerceAtMost(blocksNeeded))

                if (height == lastHeight) {
                    stallCount++
                    if (stallCount > 30) {
                        Log.w(TAG, "Prune recovery: stalled at $height for 60s")
                        stateFlow.value = stateFlow.value.copy(
                            status = LightningService.LightningState.Status.ERROR,
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
            stateFlow.value = stateFlow.value.copy(
                status = LightningService.LightningState.Status.STARTING,
                recoveryBlocksNeeded = 0,
                recoveryBlocksDone = 0,
                error = null
            )
            kotlinx.coroutines.delay(100)
            startNode?.invoke(rpcUser, rpcPassword, rpcPort)

        } catch (e: Exception) {
            Log.e(TAG, "Prune recovery failed", e)
            stateFlow.value = stateFlow.value.copy(
                status = LightningService.LightningState.Status.ERROR,
                error = "Block recovery failed: ${e.message}"
            )
        }
    }
}
