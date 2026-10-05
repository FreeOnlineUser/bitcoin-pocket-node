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
        // How far past LDK's position to fetch. LDK connects everything available in
        // one retry, at most every 5 minutes, so this sets the pace: ~4,500 blocks an
        // hour. The blocks wait in our own cache (~600 MB at today's sizes), not in
        // bitcoind's block files: see [cachedBlock].
        private const val FEED_WINDOW = 384L
        // Blocks queued at each peer at once (Core's own limit for block download).
        // A peer serves requests in order, so a deep queue strands the block LDK needs
        // behind everything requested before it. Measured 2026-10-05: with the whole
        // window queued, LDK gained 17 blocks in 13 minutes at full download speed.
        private const val PER_PEER_IN_FLIGHT = 16
        // A queued block not delivered in this long goes to another peer. 16 blocks at a
        // peer is ~25 MB, under a minute even from a slow one.
        private const val STALL_MS = 60_000L
        // How long a peer that stalled gets no new requests. A peer that never delivered
        // anything is left out until the feed goes idle: some peers accept getdata for
        // old blocks and never answer (4 of 9 on 2026-10-06).
        private const val SLOW_PEER_BENCH_MS = 10 * 60_000L
        private const val IDLE_AFTER_MS = 15 * 60_000L
        // ~2 years of blocks, ~85 GB at today's sizes: the most worth fetching to repair.
        private const val MAX_FEED_BLOCKS = 100_000L
        // Size of a persisted BDK local_chain holding only the genesis block (measured
        // on the phone; one more checkpoint makes it larger).
        private const val GENESIS_ONLY_CHAIN_BYTES = 42L
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
        val preserveNames = setOf("keys_seed", "keys_seed.bak", "mnemonic", "channel_manager", "monitors",
            "archived_monitors", "wallet_birthday", "channel_backups.json")
        // Move, don't delete: everything cleared goes to a dated folder beside the
        // store, so a reset is always reversible by hand.
        val moveTo = File(storageDir.parentFile, "lightning_reset_${System.currentTimeMillis()}")
        storageDir.listFiles()?.forEach { file ->
            if (file.name !in preserveNames) {
                moveTo.mkdirs()
                val moved = file.renameTo(File(moveTo, file.name))
                Log.i(TAG, "resetChainState: ${if (moved) "moved ${file.name} to ${moveTo.name}" else "FAILED to move ${file.name}"}")
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

    // ── Wallet health check (read-only) ──────────────────────────────

    /**
     * Read-only look at the LDK store and the seed's on-chain coins, to diagnose a
     * wallet stuck at an old (or genesis) sync position. Logs each stored key's size
     * (no contents), and scans bitcoind's UTXO set with the seed's BIP84 descriptors,
     * which works on a pruned node and doesn't depend on LDK's sync. The result goes
     * to [LightningService.LightningState.walletScan] for the screen, not the log.
     */
    fun walletHealthCheck(
        rpc: BitcoinRpcClient, storageDir: File, rpcUser: String, rpcPassword: String, rpcPort: Int
    ) {
        var localChainBytes = -1L
        try {
            val db = File(storageDir, "ldk_node_data.sqlite")
            if (db.exists()) {
                android.database.sqlite.SQLiteDatabase.openDatabase(db.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
                    d.rawQuery("SELECT primary_namespace, secondary_namespace, key, length(value) FROM ldk_node_data ORDER BY 1, 2, 3", null).use { c ->
                        while (c.moveToNext()) {
                            Log.i(TAG, "Store: ${c.getString(0)}/${c.getString(1)}/${c.getString(2)} ${c.getLong(3)} bytes")
                            if (c.getString(0) == "bdk_wallet" && c.getString(2) == "local_chain") localChainBytes = c.getLong(3)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Store listing failed: ${e.message}")
        }

        // ldk-node applies the birthday checkpoint to a new wallet in memory and saves
        // it with the first connected block. If the app dies before then (it did,
        // 2026-10-05, ten seconds after a rebuild), the store keeps a genesis-only
        // wallet and the next start loads it as-is: the birthday is only read for new
        // wallets. A birthday on file with a genesis-only chain means exactly that, so
        // rebuild again now rather than after LDK walks every header back to genesis.
        val birthday = File(storageDir, "wallet_birthday").takeIf { it.exists() }
            ?.readText()?.trim()?.toLongOrNull()
        if (birthday != null && localChainBytes in 1..GENESIS_ONLY_CHAIN_BYTES &&
            !repairStarted && countStoredMonitors(storageDir) == 0) {
            Log.w(TAG, "Wallet chain is genesis-only but birthday $birthday is on file; rebuilding from it")
            repairStarted = true
            stateFlow.value = stateFlow.value.copy(
                recoveryProblem = "Lightning's wallet lost its birthday checkpoint. Rebuilding it from block ${"%,d".format(birthday)}…"
            )
            rebuildWalletFromBirthday(rpc, storageDir, rpcUser, rpcPassword, rpcPort, knownBirthday = birthday)
            return
        }

        val mnemonicFile = File(storageDir, "mnemonic")
        if (!mnemonicFile.exists()) return
        try {
            stateFlow.value = stateFlow.value.copy(walletScan = "Checking the UTXO set for this wallet's coins…")
            val descs = WalletRecoveryService(context).descriptorsFromMnemonic(mnemonicFile.readText().trim())
            val scanObjects = JSONArray()
            for (d in descs) scanObjects.put(JSONObject().put("desc", d).put("range", 200))
            try { rpc.callSync("scantxoutset", JSONArray().put("abort"), readTimeoutMs = 10_000) } catch (_: Exception) {}
            val result = rpc.callSync("scantxoutset", JSONArray().put("start").put(scanObjects), readTimeoutMs = 600_000)
            if (result == null || result.has("_rpc_error")) {
                stateFlow.value = stateFlow.value.copy(walletScan = "UTXO check failed: ${result?.optString("message") ?: "no response"}")
                return
            }
            val unspents = result.optJSONArray("unspents") ?: JSONArray()
            val sats = Math.round(result.optDouble("total_amount", 0.0) * 100_000_000)
            var minHeight = Int.MAX_VALUE
            for (i in 0 until unspents.length()) minHeight = minOf(minHeight, unspents.getJSONObject(i).optInt("height", Int.MAX_VALUE))
            val summary = if (unspents.length() == 0) "UTXO check: no on-chain coins for this wallet's addresses"
                else "UTXO check: ${unspents.length()} coin(s), ${"%,d".format(sats)} sats, oldest from block ${"%,d".format(minHeight)}"
            stateFlow.value = stateFlow.value.copy(walletScan = summary)
            Log.i(TAG, "Wallet UTXO check done: ${unspents.length()} coin(s), oldest height ${if (unspents.length() > 0) minHeight else "-"}")
        } catch (e: Exception) {
            Log.e(TAG, "Wallet UTXO check failed", e)
            stateFlow.value = stateFlow.value.copy(walletScan = "UTXO check failed: ${e.message}")
        }
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

        // Height -> (peer, when) for blocks we asked for and bitcoind still lists in flight.
        val requested = HashMap<Long, Pair<Int, Long>>()
        // Heights at or past `need` copied into the block cache.
        val have = HashSet<Long>()
        // Peers that stalled, and when they may be asked again.
        val slowUntil = HashMap<Int, Long>()
        // Blocks each peer has delivered this session.
        val delivered = HashMap<Int, Int>()
        val hashes = HashMap<Long, String>()
        clearBlockCache()

        suspend fun hashAt(h: Long): String? = hashes[h]
            ?: rpc.call("getblockhash", JSONArray().put(h))?.optString("value")
                ?.takeIf { it.isNotEmpty() }?.also { hashes[h] = it }

        // Copy a block out of bitcoind while it's still on disk. False if it's gone.
        suspend fun keep(h: Long): Boolean {
            val hash = hashAt(h) ?: return false
            val res = rpc.callLongRunning("getblock", JSONArray().put(hash).put(0), timeoutMs = 60_000)
            val hex = res?.takeIf { !it.has("_rpc_error") }?.optString("value")
            if (hex.isNullOrEmpty()) return false
            File(blockCacheDir, hash).writeBytes(hexToBytes(hex))
            cachedHeights[hash] = h
            have.add(h)
            return true
        }
        var need = -1L          // lowest height LDK is currently missing
        var firstNeed = -1L     // where this catch-up started, for progress
        var lastReportAt = 0L
        var lastSummaryAt = 0L
        var holding = false

        fun clearProgress() {
            stateFlow.value = stateFlow.value.copy(
                recoveryBlocksNeeded = 0, recoveryBlocksDone = 0, recoveryWaitingForWifi = false,
                recoveryProblem = null
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
                    requested.clear()
                    have.clear()
                    clearBlockCache()
                    slowUntil.clear()
                    delivered.clear()
                    if (holding) { pmm.releaseNetworkHold(); holding = false }
                    clearProgress()
                    kotlinx.coroutines.delay(10_000)
                    continue
                }

                val info = rpc.getBlockchainInfo()
                val pruneHeight = info?.optLong("pruneheight", 0) ?: 0
                if (pruneHeight > 0 && pruneHeight - need > MAX_FEED_BLOCKS) {
                    // Something in LDK's store sits implausibly far back. The known cause is an
                    // on-chain wallet created at genesis (ldk-node before d0ed6a3 did that when
                    // it couldn't read the tip on first start). Fetching most of the chain block
                    // by block isn't a repair. With no channel monitors in the store there is no
                    // channel state at risk, so rebuild the wallet from its birthday instead.
                    Log.e(TAG, "Prune feed: LDK needs block $need, ${pruneHeight - need} below the prune height. Not fetching.")
                    val storageDir = File(context.filesDir, STORAGE_DIR)
                    if (!repairStarted && countStoredMonitors(storageDir) == 0) {
                        repairStarted = true
                        stateFlow.value = stateFlow.value.copy(
                            recoveryBlocksNeeded = 0, recoveryBlocksDone = 0, recoveryWaitingForWifi = false,
                            recoveryProblem = "Lightning's wallet was created at the start of the chain. Rebuilding it from its birthday…"
                        )
                        // Its own thread: stopping the node cancels this coroutine.
                        Thread({ rebuildWalletFromBirthday(rpc, storageDir, rpcUser, rpcPassword, rpcPort) }, "wallet-rebuild").start()
                        return@withContext
                    }
                    stateFlow.value = stateFlow.value.copy(
                        recoveryBlocksNeeded = 0, recoveryBlocksDone = 0, recoveryWaitingForWifi = false,
                        recoveryProblem = "Lightning needs blocks from height $need, too far back to fetch. Its chain data needs repair."
                    )
                    kotlinx.coroutines.delay(60_000)
                    continue
                }
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

                // What bitcoind still has in flight, by peer. Anything we asked for that
                // left the list has arrived, or its peer went away; LDK's next report
                // catches the second case.
                val inFlight = HashMap<Int, Int>()
                val inFlightHeights = HashSet<Long>()
                for (p in peers) {
                    inFlight[p.id] = p.inFlight.size
                    inFlightHeights.addAll(p.inFlight)
                }
                val arrived = requested.keys.filter { it !in inFlightHeights }
                for (h in arrived) requested.remove(h)?.let { (p, _) -> delivered[p] = (delivered[p] ?: 0) + 1 }
                // Copy them out now: bitcoind may prune a fetched block within minutes,
                // before LDK's next retry. Gone already means it goes back in the queue.
                for (h in arrived) if (h >= need && !keep(h)) Log.d(TAG, "Prune feed: block $h pruned before it could be kept")

                // Blocks a peer has sat on too long go to another peer. A peer serves in
                // order, so one slow peer strands every block queued at it, and LDK can't
                // pass the first of them. getblockfrompeer drops the old request, so this
                // doesn't double up. A peer that keeps stalling is left out for a while.
                val stalled = requested.filter { (_, v) -> now - v.second > STALL_MS }.keys.sorted()
                for (h in stalled) {
                    val (slowPeer, _) = requested[h] ?: continue
                    slowUntil[slowPeer] = if ((delivered[slowPeer] ?: 0) == 0) Long.MAX_VALUE else now + SLOW_PEER_BENCH_MS
                    val other = peers.filter { it.id != slowPeer && (slowUntil[it.id] ?: 0) < now }
                        .minByOrNull { inFlight[it.id] ?: 0 } ?: break
                    val hash = rpc.call("getblockhash", JSONArray().put(h))?.optString("value")
                    if (hash.isNullOrEmpty()) continue
                    val res = rpc.call("getblockfrompeer", JSONArray().put(hash).put(other.id))
                    if (res != null && !res.has("_rpc_error")) {
                        requested[h] = other.id to now
                        inFlight[other.id] = (inFlight[other.id] ?: 0) + 1
                        inFlight[slowPeer] = ((inFlight[slowPeer] ?: 1) - 1).coerceAtLeast(0)
                    }
                }
                if (stalled.isNotEmpty()) Log.i(TAG, "Prune feed: moved ${stalled.size} stalled block(s) off slow peer(s)")

                // Fill each peer up to PER_PEER_IN_FLIGHT, lowest heights first, so blocks
                // arrive in about the order LDK connects them.
                for (h in need until need + FEED_WINDOW) {
                    if (pruneHeight > 0 && h >= pruneHeight) break  // bitcoind still has these
                    if (h in have || h in requested) continue
                    val peer = peers.filter { (inFlight[it.id] ?: 0) < PER_PEER_IN_FLIGHT && (slowUntil[it.id] ?: 0) < now }
                        .minByOrNull { inFlight[it.id] ?: 0 } ?: break
                    val hash = hashAt(h) ?: continue
                    val res = rpc.call("getblockfrompeer", JSONArray().put(hash).put(peer.id)) ?: break
                    val msg = res.optString("message")
                    when {
                        !res.has("_rpc_error") -> {
                            requested[h] = peer.id to now
                            inFlight[peer.id] = (inFlight[peer.id] ?: 0) + 1
                        }
                        msg.contains("already downloaded", ignoreCase = true) -> keep(h)
                        else -> {
                            // Peer gone or unusable; it drops out of next round's list.
                            Log.d(TAG, "Prune feed: getblockfrompeer $h via peer ${peer.id}: $msg")
                            inFlight[peer.id] = PER_PEER_IN_FLIGHT
                        }
                    }
                }
                requested.keys.removeAll { it < need }
                have.removeAll { it < need }
                hashes.keys.removeAll { it < need }
                // LDK has connected everything below need.
                for ((hash, h) in cachedHeights.entries.toList()) {
                    if (h < need) { File(blockCacheDir, hash).delete(); cachedHeights.remove(hash) }
                }
                if (now - lastSummaryAt > 60_000) {
                    lastSummaryAt = now
                    Log.i(TAG, "Prune feed: need $need, ${have.size} cached, ${requested.size} requested, " +
                        "peers ${peers.joinToString(" ") { "${it.id}:${inFlight[it.id] ?: 0}" + if ((slowUntil[it.id] ?: 0) > now) "(slow)" else "" }}" +
                        (requested[need]?.let { ", need asked of peer ${it.first} ${(now - it.second) / 1000}s ago" } ?: ""))
                }
                kotlinx.coroutines.delay(5_000)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Prune feed failed", e)
        } finally {
            if (holding) pmm.releaseNetworkHold()
            clearProgress()
            clearBlockCache()
        }
    }

    // ── Fed block cache ──
    //
    // A fetched block can't wait in bitcoind for LDK: once the prune target is full,
    // bitcoind deletes block files holding only old blocks as soon as it opens a new
    // file, oldest first, which are the blocks LDK needs next. (Files that also hold a
    // new tip block are kept ~2 days, and during a long catch-up those fill the target.)
    // So the feed copies each block out as it arrives, and RpcRelay answers LDK's
    // getblock from here. LDK checks every block against its header chain itself.

    private val blockCacheDir by lazy { File(context.cacheDir, "prune_feed").apply { mkdirs() } }
    private val cachedHeights = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Raw block LDK asked for, if the prune feed holds it. Called from RpcRelay threads. */
    fun cachedBlock(hash: String): ByteArray? {
        if (!cachedHeights.containsKey(hash)) return null
        return try { File(blockCacheDir, hash).readBytes() } catch (_: Exception) { null }
    }

    private fun clearBlockCache() {
        cachedHeights.clear()
        blockCacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        return out
    }

    @Volatile private var repairStarted = false

    /**
     * Rebuild an LDK store whose wallet is pinned at genesis. Only called when the
     * store holds no channel monitors, so the only thing reset is wallet sync state,
     * which the seed rebuilds. Finds the oldest coin with a UTXO-set scan and uses it
     * as the wallet birthday (or the tip if there are none), resets the store
     * (resetChainState keeps the mnemonic, seed and birthday, and refuses if monitors
     * exist), and restarts LDK. The prune feed then brings the wallet up from there.
     */
    private fun rebuildWalletFromBirthday(
        rpc: BitcoinRpcClient, storageDir: File, rpcUser: String, rpcPassword: String, rpcPort: Int,
        knownBirthday: Long? = null
    ) {
        try {
            val mnemonic = File(storageDir, "mnemonic").takeIf { it.exists() }?.readText()?.trim()
            val tip = rpc.getBlockchainInfoSync()?.optLong("blocks", 0) ?: 0
            var birthday = tip
            if (knownBirthday != null) {
                birthday = knownBirthday
            } else if (mnemonic != null) {
                val scanObjects = JSONArray()
                for (d in WalletRecoveryService(context).descriptorsFromMnemonic(mnemonic)) {
                    scanObjects.put(JSONObject().put("desc", d).put("range", 200))
                }
                try { rpc.callSync("scantxoutset", JSONArray().put("abort"), readTimeoutMs = 10_000) } catch (_: Exception) {}
                val result = rpc.callSync("scantxoutset", JSONArray().put("start").put(scanObjects), readTimeoutMs = 600_000)
                if (result == null || result.has("_rpc_error")) {
                    Log.e(TAG, "Wallet rebuild: UTXO scan failed, not resetting")
                    stateFlow.value = stateFlow.value.copy(recoveryProblem = "Wallet rebuild couldn't scan for coins; nothing was changed.")
                    repairStarted = false
                    return
                }
                val unspents = result.optJSONArray("unspents") ?: JSONArray()
                for (i in 0 until unspents.length()) {
                    birthday = minOf(birthday, unspents.getJSONObject(i).optLong("height", tip))
                }
            }
            if (birthday <= 0) {
                stateFlow.value = stateFlow.value.copy(recoveryProblem = "Wallet rebuild couldn't read the chain tip; nothing was changed.")
                repairStarted = false
                return
            }
            if (knownBirthday == null) {
                if (birthday < tip) birthday = (birthday - 10).coerceAtLeast(1)
                File(storageDir, "wallet_birthday").writeText(birthday.toString())
            }
            Log.i(TAG, "Wallet rebuild: birthday $birthday, resetting store and restarting LDK")

            stopNode?.invoke()
            Thread.sleep(500)
            resetChainState(storageDir)
            if (File(storageDir, "ldk_node_data.sqlite").exists()) {
                // resetChainState refused (it found monitors after all). Leave it alone.
                Log.e(TAG, "Wallet rebuild: store not reset, restarting LDK unchanged")
            }
            stateFlow.value = stateFlow.value.copy(recoveryProblem = null)
            clearStartingFlag?.invoke()
            startNode?.invoke(rpcUser, rpcPassword, rpcPort)
        } catch (e: Exception) {
            Log.e(TAG, "Wallet rebuild failed", e)
            stateFlow.value = stateFlow.value.copy(recoveryProblem = "Wallet rebuild failed: ${e.message}")
            repairStarted = false
        }
    }

    private class FeedPeer(val id: Int, val inFlight: List<Long>)

    /**
     * Peers advertising NODE_NETWORK, i.e. able to serve blocks older than 288, with
     * the heights bitcoind has in flight from each.
     */
    private suspend fun fullHistoryPeers(rpc: BitcoinRpcClient): List<FeedPeer> {
        val res = rpc.call("getpeerinfo") ?: return emptyList()
        val arr = res.optJSONArray("value") ?: return emptyList()
        val peers = ArrayList<FeedPeer>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val services = p.optJSONArray("servicesnames") ?: continue
            if ((0 until services.length()).none { services.optString(it) == "NETWORK" }) continue
            val inflight = p.optJSONArray("inflight")
            val heights = (0 until (inflight?.length() ?: 0)).map { inflight!!.optLong(it) }
            peers.add(FeedPeer(p.optInt("id"), heights))
        }
        return peers
    }
}
