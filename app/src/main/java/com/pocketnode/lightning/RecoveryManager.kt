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
        // LDK retries a failed sync at most every 5 minutes and connects everything
        // available in one pass, so the window sets the pace: 288 blocks (~500 MB)
        // per retry, about 3,500 blocks an hour.
        private const val FEED_WINDOW = 288L
        private const val REREQUEST_MS = 120_000L
        private const val IDLE_AFTER_MS = 15 * 60_000L
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

    /** Blocks LDK asked for that bitcoind had pruned, reported by [RpcRelay]. */
    private val prunedRequests = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun reportPrunedBlock(hash: String) {
        prunedRequests.add(hash)
    }

    /**
     * Self-heal LDK's chain sync after bitcoind has pruned blocks it still needs.
     *
     * LDK syncs its listeners (channel manager, wallet, sweeper, monitors) from
     * the oldest one's position. If that is below the prune height, LDK's
     * getblock fails and its sync retries every few minutes forever. [RpcRelay]
     * reports each block that failed; this loop fetches it and the [FEED_WINDOW]
     * blocks after it from peers that keep full history (getblockfrompeer), so
     * LDK's next retry can connect them. It follows LDK forward as new failures
     * are reported, and idles when there are none. Nothing is invalidated, so
     * the inherited-invalid healer in BitcoindService has nothing to fight.
     *
     * Fetched blocks go into new block files, which bitcoind prunes only after
     * the older main-chain files, so the window usually survives until LDK reads
     * it. If not, LDK reports the block again and it is fetched again.
     */
    suspend fun feedPrunedBlocks(
        rpcUser: String, rpcPassword: String, rpcPort: Int,
        isRunning: () -> Boolean
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val rpc = BitcoinRpcClient(rpcUser, rpcPassword, port = rpcPort)
        val networkMonitor = com.pocketnode.network.NetworkMonitor.getInstance(context)
        val pmm = com.pocketnode.power.PowerModeManager.getInstance(context)

        val requestedAt = HashMap<Long, Long>()
        var need = -1L          // lowest height LDK is currently missing
        var firstNeed = -1L     // where this catch-up started, for progress
        var lastReportAt = 0L
        var holding = false

        fun clearProgress() {
            stateFlow.value = stateFlow.value.copy(
                recoveryBlocksNeeded = 0, recoveryBlocksDone = 0, recoveryWaitingForWifi = false
            )
        }

        try {
            while (isRunning()) {
                val now = System.currentTimeMillis()

                // Fold in new reports. Each LDK retry fails on the first missing block
                // of its batch, so the lowest recent report is where LDK stands.
                val fresh = generateSequence { prunedRequests.poll() }.toList()
                if (fresh.isNotEmpty()) {
                    val heights = fresh.distinct().mapNotNull { hash ->
                        rpc.call("getblockheader", JSONArray().put(hash))?.optLong("height", -1)?.takeIf { it > 0 }
                    }
                    if (heights.isNotEmpty()) {
                        need = heights.min()
                        if (firstNeed < 0 || need < firstNeed) firstNeed = need
                        lastReportAt = now
                        Log.i(TAG, "Prune feed: LDK needs block $need")
                    }
                }

                // No report for a while: LDK is either synced or hasn't retried yet.
                // Its retry backoff tops out at 5 minutes, so 15 minutes of silence means done.
                if (need < 0 || now - lastReportAt > IDLE_AFTER_MS) {
                    if (need >= 0) Log.i(TAG, "Prune feed: no missing blocks reported for ${IDLE_AFTER_MS / 60_000} min, idle")
                    need = -1
                    firstNeed = -1
                    requestedAt.clear()
                    if (holding) { pmm.releaseNetworkHold(); holding = false }
                    clearProgress()
                    kotlinx.coroutines.delay(10_000)
                    continue
                }

                val info = rpc.getBlockchainInfo()
                val pruneHeight = info?.optLong("pruneheight", 0) ?: 0
                val total = (pruneHeight - firstNeed).coerceAtLeast(1)
                val done = (need - firstNeed).coerceIn(0, total)

                if (networkMonitor.networkState.value != com.pocketnode.network.NetworkState.WIFI) {
                    if (holding) { pmm.releaseNetworkHold(); holding = false }
                    stateFlow.value = stateFlow.value.copy(
                        recoveryBlocksNeeded = total.toInt(), recoveryBlocksDone = done.toInt(), recoveryWaitingForWifi = true
                    )
                    kotlinx.coroutines.delay(15_000)
                    continue
                }
                if (!holding) {
                    pmm.setRpc(rpc)
                    pmm.holdNetwork()
                    holding = true
                }
                stateFlow.value = stateFlow.value.copy(
                    recoveryBlocksNeeded = total.toInt(), recoveryBlocksDone = done.toInt(), recoveryWaitingForWifi = false
                )

                val peers = fullHistoryPeers(rpc)
                if (peers.isEmpty()) {
                    Log.d(TAG, "Prune feed: no full-history peers yet")
                    kotlinx.coroutines.delay(15_000)
                    continue
                }

                var peerIdx = 0
                for (h in need until need + FEED_WINDOW) {
                    if (pruneHeight > 0 && h >= pruneHeight) break  // bitcoind still has these
                    val sent = requestedAt[h]
                    if (sent != null && now - sent < REREQUEST_MS) continue
                    val hash = rpc.call("getblockhash", JSONArray().put(h))?.optString("value")
                    if (hash.isNullOrEmpty()) continue
                    for (attempt in 0 until minOf(3, peers.size)) {
                        val peer = peers[peerIdx++ % peers.size]
                        val res = rpc.call("getblockfrompeer", JSONArray().put(hash).put(peer)) ?: break
                        val msg = res.optString("message")
                        if (!res.has("_rpc_error") || msg.contains("already downloaded", ignoreCase = true)) {
                            requestedAt[h] = now
                            break
                        }
                        Log.d(TAG, "Prune feed: getblockfrompeer $h via peer $peer: $msg")
                    }
                }
                requestedAt.keys.removeAll { it < need }
                kotlinx.coroutines.delay(10_000)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Prune feed failed", e)
        } finally {
            if (holding) pmm.releaseNetworkHold()
            clearProgress()
        }
    }

    /** Peers advertising NODE_NETWORK, i.e. able to serve blocks older than 288. */
    private suspend fun fullHistoryPeers(rpc: BitcoinRpcClient): List<Int> {
        val res = rpc.call("getpeerinfo") ?: return emptyList()
        val arr = res.optJSONArray("value") ?: return emptyList()
        val ids = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val services = p.optJSONArray("servicesnames") ?: continue
            if ((0 until services.length()).any { services.optString(it) == "NETWORK" }) {
                ids.add(p.optInt("id"))
            }
        }
        return ids
    }
}
