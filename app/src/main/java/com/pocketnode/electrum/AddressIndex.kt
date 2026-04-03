package com.pocketnode.electrum

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Tracks addresses/xpubs/descriptors via bitcoind's descriptor wallet.
 *
 * Uses scantxoutset for balance/UTXOs (reads chainstate directly, works on pruned nodes)
 * and descriptor wallet for unconfirmed transactions and history.
 * Discovered transactions are persisted to survive block pruning.
 *
 * Scripthash = SHA256(scriptPubKey) reversed (Electrum convention).
 */
class AddressIndex(private val rpc: BitcoinRpcClient, private val context: Context) {

    companion object {
        private const val TAG = "AddressIndex"
        const val WALLET_NAME = "pocketnode_electrum"
    }

    // Map scripthash -> list of known addresses for that scripthash
    // Populated when descriptors are imported
    private val scripthashToAddress = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val addressToScripthash = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val rawDescriptors = mutableListOf<String>()  // for scantxoutset

    // (scantxoutset cache removed: using pure descriptor wallet RPCs now)

    /**
     * Scan all tracked addresses in one scantxoutset call and cache results.
     * Called after index rebuild and on new blocks.
     */
    // Persistent transaction history: survives block pruning
    private val txHistoryFile = File(context.filesDir, "electrum_tx_history.json")
    // scripthash -> set of "txid:height"
    private val persistedHistory = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    // Cached raw tx hex: txid -> hex (for pruned blocks we can't fetch from bitcoind)
    private val txHexCacheFile = File(context.filesDir, "electrum_tx_hex_cache.json")
    private val txHexCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        loadPersistedHistory()
        loadTxHexCache()
        // Load saved mempool API URL
        val savedUrl = context.getSharedPreferences("electrum_prefs", android.content.Context.MODE_PRIVATE)
            .getString("mempool_api_url", null)
        if (savedUrl != null) HistoryRecovery.apiBase = savedUrl
    }

    private fun loadPersistedHistory() {
        try {
            if (txHistoryFile.exists()) {
                val json = JSONObject(txHistoryFile.readText())
                for (key in json.keys()) {
                    val arr = json.getJSONArray(key)
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    persistedHistory[key] = set
                }
                Log.i(TAG, "Loaded persisted tx history: ${persistedHistory.size} scripthashes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tx history: ${e.message}")
        }
    }

    private fun savePersistedHistory() {
        try {
            val json = JSONObject()
            for ((key, set) in persistedHistory) {
                json.put(key, JSONArray(set.toList()))
            }
            txHistoryFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tx history: ${e.message}")
        }
    }

    private fun loadTxHexCache() {
        try {
            if (txHexCacheFile.exists()) {
                val json = JSONObject(txHexCacheFile.readText())
                for (key in json.keys()) {
                    txHexCache[key] = json.getString(key)
                }
                Log.i(TAG, "Loaded tx hex cache: ${txHexCache.size} transactions")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tx hex cache: ${e.message}")
        }
    }

    private fun saveTxHexCache() {
        try {
            val json = JSONObject()
            for ((k, v) in txHexCache) json.put(k, v)
            txHexCacheFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save tx hex cache: ${e.message}")
        }
    }

    /**
     * Get cached raw hex for a transaction. Returns null if not cached.
     */
    fun getCachedTxHex(txid: String): String? = txHexCache[txid]

    /**
     * Cache raw hex for a transaction.
     */
    fun cacheTxHex(txid: String, hex: String) {
        txHexCache[txid] = hex
        saveTxHexCache()
    }

    private fun persistTx(scripthash: String, txid: String, height: Int) {
        val set = persistedHistory.getOrPut(scripthash) { mutableSetOf() }
        val entry = "$txid:$height"
        if (set.add(entry)) {
            // Also update height if we had it at 0 (unconfirmed -> confirmed)
            val unconfirmed = "$txid:0"
            if (height > 0 && set.contains(unconfirmed)) {
                set.remove(unconfirmed)
            }
            savePersistedHistory()
        }
    }

    /**
     * Look up a transaction's block height from persisted history.
     */
    fun getTxHeight(txid: String): Int {
        for ((_, set) in persistedHistory) {
            for (entry in set) {
                val parts = entry.split(":")
                if (parts.size == 2 && parts[0] == txid) {
                    return parts[1].toIntOrNull() ?: 0
                }
            }
        }
        return 0
    }

    /**
     * Ensure the tracking wallet exists and load it.
     */
    suspend fun ensureWallet(): Boolean {
        // Try to load existing wallet first
        val loadResult = rpc.call("loadwallet", JSONArray().apply { put(WALLET_NAME) })
        if (loadResult != null && loadResult.optBoolean("_rpc_error", false).not()) {
            Log.i(TAG, "Loaded existing wallet: $WALLET_NAME")
            return true
        }

        // Create a new descriptor wallet (watch-only, no private keys)
        val createResult = rpc.call("createwallet", JSONArray().apply {
            put(WALLET_NAME)      // wallet_name
            put(true)             // disable_private_keys
            put(true)             // blank
            put("")               // passphrase (empty)
            put(false)            // avoid_reuse
            put(true)             // descriptors (use descriptor wallet)
            put(true)             // load_on_startup
        })

        return if (createResult != null && !createResult.optBoolean("_rpc_error", false)) {
            Log.i(TAG, "Created descriptor wallet: $WALLET_NAME")
            true
        } else {
            val errMsg = createResult?.optString("message", "unknown") ?: "null response"
            // "already loaded" is fine
            if (errMsg.contains("already loaded") || errMsg.contains("already exists")) {
                Log.d(TAG, "Wallet already exists/loaded")
                true
            } else {
                Log.e(TAG, "Failed to create wallet: $errMsg")
                false
            }
        }
    }

    /**
     * Import descriptors for tracking. Derives addresses and builds scripthash index.
     * @param descriptors List of output descriptors (e.g., "wpkh(xpub.../0/star)")
     * @param xpubs List of bare xpubs (auto-wrapped in wpkh, sh(wpkh), pkh descriptors)
     */
    suspend fun importDescriptors(descriptors: List<String>, xpubs: List<String>) {
        val allDescriptors = mutableListOf<String>()
        allDescriptors.addAll(descriptors)

        // Auto-wrap xpubs in standard descriptor types
        for (xpub in xpubs) {
            val prefix = xpub.take(4)
            when {
                prefix.startsWith("zpub") -> {
                    val converted = convertToXpub(xpub)
                    allDescriptors.add("wpkh($converted/0/*)")
                    allDescriptors.add("wpkh($converted/1/*)")
                }
                prefix.startsWith("ypub") -> {
                    val converted = convertToXpub(xpub)
                    allDescriptors.add("sh(wpkh($converted/0/*))")
                    allDescriptors.add("sh(wpkh($converted/1/*))")
                }
                else -> {
                    allDescriptors.add("wpkh($xpub/0/*)")
                    allDescriptors.add("wpkh($xpub/1/*)")
                }
            }
        }

        if (allDescriptors.isEmpty()) {
            Log.w(TAG, "No descriptors to import")
            return
        }

        // Store raw descriptors for scantxoutset queries
        rawDescriptors.clear()
        rawDescriptors.addAll(allDescriptors)

        // Check which descriptors are already imported
        val existingDescs = mutableSetOf<String>()
        val listResult = walletRpc("listdescriptors", JSONArray())
        if (listResult != null) {
            val descsArray = listResult.optJSONArray("descriptors")
                ?: listResult.optJSONObject("value")?.optJSONArray("descriptors")
            if (descsArray != null) {
                for (i in 0 until descsArray.length()) {
                    val d = descsArray.getJSONObject(i).optString("desc", "")
                    // Strip checksum for comparison
                    existingDescs.add(d.substringBefore('#'))
                }
            }
        }

        // Only import new descriptors (with rescan), skip already-tracked ones
        val importArray = JSONArray()
        for (desc in allDescriptors) {
            val descWithChecksum = if (desc.contains('#')) desc else addChecksum(desc)
            val bare = desc.substringBefore('#')
            if (bare in existingDescs) {
                Log.d(TAG, "Descriptor already imported, skipping: $bare")
                continue
            }
            importArray.put(JSONObject().apply {
                put("desc", descWithChecksum ?: desc)
                put("timestamp", 0)  // rescan available blocks for existing transactions
                put("watchonly", true)
                put("active", true)
                put("range", JSONArray().apply { put(0); put(100) })
                if (desc.contains("/0/*")) put("internal", false)
                if (desc.contains("/1/*")) put("internal", true)
            })
        }

        if (importArray.length() > 0) {
            Log.d(TAG, "importdescriptors payload: ${importArray.toString().take(300)}")
            val result = walletRpc("importdescriptors", JSONArray().apply { put(importArray) })
            Log.d(TAG, "importdescriptors result: ${result?.toString()?.take(300)}")
            if (result != null) {
                Log.i(TAG, "Imported ${importArray.length()} new descriptors (${existingDescs.size} already tracked)")
            } else {
                Log.e(TAG, "Failed to import descriptors")
            }
        } else {
            Log.i(TAG, "All ${allDescriptors.size} descriptors already imported, skipping rescan")
        }

        // Build scripthash index from derived addresses
        rebuildIndex()
    }

    /**
     * Import individual addresses for tracking.
     */
    suspend fun importAddresses(addresses: List<String>) {
        if (addresses.isEmpty()) return

        val importArray = JSONArray()
        for (addr in addresses) {
            importArray.put(JSONObject().apply {
                put("desc", "addr($addr)")
                put("timestamp", 0)
                put("watchonly", true)
            })
        }

        walletRpc("importdescriptors", JSONArray().apply { put(importArray) })
        rebuildIndex()
    }

    /**
     * Rebuild the scripthash -> address mapping by querying the wallet.
     */
    suspend fun rebuildIndex() {
        scripthashToAddress.clear()
        addressToScripthash.clear()

        // Derive addresses from descriptors using deriveaddresses RPC
        // This generates the actual addresses BlueWallet will query
        val listResult = walletRpc("listdescriptors", JSONArray())
        Log.d(TAG, "listdescriptors raw: ${listResult?.toString()?.take(200)}")
        if (listResult != null) {
            val descsArray = listResult.optJSONObject("value")?.optJSONArray("descriptors")
                ?: listResult.optJSONArray("descriptors")
            Log.d(TAG, "descsArray: ${descsArray?.length() ?: "null"} descriptors")
            if (descsArray != null) {
                for (i in 0 until descsArray.length()) {
                    val descObj = descsArray.getJSONObject(i)
                    val desc = descObj.optString("desc", "")
                    if (desc.isEmpty()) continue

                    // Derive first 100 addresses from each descriptor (receive + change)
                    Log.d(TAG, "Deriving addresses for: ${desc.take(40)}...")
                    val deriveResult = rpc.call("deriveaddresses", JSONArray().apply {
                        put(desc)
                        put(JSONArray().apply { put(0); put(99) })  // range [0, 99]
                    })
                    if (deriveResult == null) {
                        Log.w(TAG, "deriveaddresses returned null for ${desc.take(40)}")
                    } else if (deriveResult.has("_rpc_error")) {
                        Log.w(TAG, "deriveaddresses error: ${deriveResult.optString("message")}")
                    } else {
                        val addrs = deriveResult.optJSONArray("value") ?: deriveResult.optJSONArray("result")
                        Log.d(TAG, "deriveaddresses returned ${addrs?.length() ?: 0} addresses")
                        if (addrs != null) {
                            for (j in 0 until addrs.length()) {
                                val addr = addrs.optString(j, "")
                                if (addr.isNotEmpty()) indexAddress(addr)
                            }
                        }
                    }
                }
            }
        }

        // Also index any addresses from listreceivedbyaddress (catch-all)
        val result = walletRpc("listreceivedbyaddress", JSONArray().apply {
            put(0); put(true); put(true)
        })
        if (result != null) {
            val arr = result.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    indexAddress(entry.getString("address"))
                }
            }
        }

        Log.i(TAG, "Index built: ${addressToScripthash.size} addresses, ${scripthashToAddress.size} scripthashes")

        // Check initial recovery status (don't auto-scan, user taps Refresh)
        checkRecoveryStatus()
    }

    /**
     * Called by SubscriptionManager when a new block is detected.
     * Refreshes wallet transactions and persists any new ones.
     */
    suspend fun refreshOnNewBlock() {
        // Skip if no addresses are tracked (avoids pointless wallet RPC calls)
        if (addressToScripthash.isEmpty()) return

        // Query wallet for recent transactions (picks up newly confirmed + new incoming)
        val txResult = walletRpc("listtransactions", JSONArray().apply {
            put("*"); put(50); put(0); put(true)  // last 50 txs, include watchonly
        })
        val txArr = txResult?.optJSONArray("value") ?: return

        var newTxCount = 0
        for (i in 0 until txArr.length()) {
            val tx = txArr.getJSONObject(i)
            val txid = tx.optString("txid", "")
            val addr = tx.optString("address", "")
            val confs = tx.optInt("confirmations", 0)
            val height = if (confs > 0) tx.optInt("blockheight", 0) else 0

            if (txid.isEmpty()) continue
            val category = tx.optString("category", "")

            // For sends to external addresses, find which tracked addresses were spent
            val scripthash = addressToScripthash[addr]
            if (scripthash == null) {
                if (category == "send" && addr.isNotEmpty()) {
                    // Get the full tx to find input addresses
                    try {
                        val txDetail = walletRpc("gettransaction", JSONArray().apply {
                            put(txid); put(true)
                        })
                        val hex = txDetail?.optString("hex", "") ?: txDetail?.optJSONObject("value")?.optString("hex", "") ?: ""
                        if (hex.isEmpty()) { continue }
                        val decoded = rpc.call("decoderawtransaction", JSONArray().apply { put(hex) })
                        val vin = decoded?.optJSONArray("vin")
                        if (vin != null) {
                            for (v in 0 until vin.length()) {
                                val input = vin.getJSONObject(v)
                                val prevTxid = input.optString("txid", "")
                                val prevVout = input.optInt("vout", -1)
                                if (prevTxid.isEmpty()) continue
                                // Find which tracked address owned this input
                                for ((trackedAddr, sh) in addressToScripthash) {
                                    val addrHistory = persistedHistory[sh] ?: continue
                                    if (addrHistory.any { it.startsWith(prevTxid) } || addrHistory.any { it.startsWith("$txid:") }) {
                                        val entry = "$txid:$height"
                                        if (!addrHistory.contains(entry) && !addrHistory.contains("$txid:0")) {
                                            addrHistory.add(entry)
                                            newTxCount++
                                        } else if (addrHistory.contains("$txid:0") && height > 0) {
                                            addrHistory.remove("$txid:0")
                                            addrHistory.add(entry)
                                            newTxCount++
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve spend inputs for $txid: ${e.message}")
                    }
                }
                continue
            }

            val entry = "$txid:$height"
            val existing = persistedHistory[scripthash]
            if (existing != null) {
                // Check if we need to update (new tx or height changed from 0 to confirmed)
                val hasExact = existing.contains(entry)
                val hasUnconfirmed = existing.contains("$txid:0")
                if (!hasExact) {
                    if (hasUnconfirmed && height > 0) {
                        // Tx confirmed, update height
                        existing.remove("$txid:0")
                        existing.add(entry)
                        newTxCount++
                    } else if (!hasUnconfirmed) {
                        existing.add(entry)
                        newTxCount++
                    }
                }
            } else {
                persistedHistory[scripthash] = mutableSetOf(entry)
                newTxCount++
            }

            // Cache tx hex if available
            proactivelyCacheTx(txid)
        }

        if (newTxCount > 0) {
            savePersistedHistory()
            Log.i(TAG, "Block refresh: $newTxCount new/updated transactions persisted")
        }
    }

    /**
     * Find addresses with UTXOs but incomplete history, and recover from mempool.space.
     * Only runs once per address (results are persisted).
     */
    data class RecoveryStatus(
        val totalAddresses: Int = 0,
        val recoveredAddresses: Int = 0,
        val totalTxFound: Int = 0,
        val isRecovering: Boolean = false,
        val isComplete: Boolean = false,
        val scanProgress: Int = 0,       // addresses scanned so far
        val scanTotal: Int = 0,          // total addresses to scan
        val phase: String = ""           // "Scanning mempool.space...", "Caching transactions...", etc.
    )

    private val _recoveryStatus = MutableStateFlow(RecoveryStatus())
    val recoveryStatus: StateFlow<RecoveryStatus> = _recoveryStatus
    @Volatile private var recoveryCancelled = false

    fun cancelRecovery() {
        recoveryCancelled = true
        HistoryRecovery.interruptConnection()
    }

    /**
     * Check if all tracked addresses have been recovered.
     */
    fun checkRecoveryStatus() {
        val prefs = context.getSharedPreferences("electrum_recovery", Context.MODE_PRIVATE)
        val recovered = prefs.getStringSet("recovered", emptySet()) ?: emptySet()
        val total = addressToScripthash.keys.size
        val done = addressToScripthash.keys.count { it in recovered }
        val txCount = persistedHistory.values.sumOf { it.size }
        _recoveryStatus.value = RecoveryStatus(
            totalAddresses = total,
            recoveredAddresses = done,
            totalTxFound = txCount,
            isComplete = total > 0 && done >= total
        )
    }

    /**
     * Recover missing history from mempool.space.
     * Called automatically on startup for new addresses, or manually via UI.
     * @param force If true, re-query all addresses (not just new ones)
     */
    suspend fun recoverMissingHistory(force: Boolean = false) {
        val prefs = context.getSharedPreferences("electrum_recovery", Context.MODE_PRIVATE)
        val recoveredAddresses = if (force) {
            mutableSetOf()  // re-query everything
        } else {
            prefs.getStringSet("recovered", emptySet())?.toMutableSet() ?: mutableSetOf()
        }

        // Scan all tracked addresses sequentially
        val needsRecovery = mutableSetOf<String>()
        for (addr in addressToScripthash.keys) {
            if (addr in recoveredAddresses) continue
            needsRecovery.add(addr)
        }

        if (needsRecovery.isEmpty()) {
            checkRecoveryStatus()
            return
        }

        Log.i(TAG, "Recovering history for ${needsRecovery.size} addresses")
        recoveryCancelled = false
        _recoveryStatus.value = _recoveryStatus.value.copy(
            isRecovering = true,
            totalAddresses = addressToScripthash.keys.size,
            scanProgress = 0,
            scanTotal = needsRecovery.size,
            phase = "Scanning mempool.space (${needsRecovery.size} addresses)..."
        )

        val knownCounts = mutableMapOf<String, Int>()
        for (addr in needsRecovery) {
            val sh = addressToScripthash[addr] ?: continue
            knownCounts[addr] = persistedHistory[sh]?.size ?: 0
        }

        val recovered = HistoryRecovery.recoverHistory(
            needsRecovery, knownCounts,
            onProgress = { scanned, total, addr ->
                val displayAddr = if (addr.contains("\n")) {
                    // Rate limit message already formatted
                    val parts = addr.split("\n", limit = 2)
                    val shortAddr = parts[0].take(8) + "..." + parts[0].takeLast(6)
                    "$shortAddr\n${parts[1]}"
                } else {
                    addr.take(8) + "..." + addr.takeLast(6)
                }
                _recoveryStatus.value = _recoveryStatus.value.copy(
                    scanProgress = scanned,
                    scanTotal = total,
                    phase = "${scanned + 1}/$total $displayAddr"
                )
            },
            isCancelled = { recoveryCancelled }
        )

        var newTxCount = 0
        for ((addr, txs) in recovered) {
            val sh = addressToScripthash[addr] ?: continue
            for ((txid, height) in txs) {
                persistTx(sh, txid, height)
                newTxCount++
            }
            recoveredAddresses.add(addr)
        }

        // Also mark addresses that had no results as recovered (they're empty)
        for (addr in needsRecovery) {
            recoveredAddresses.add(addr)
        }

        prefs.edit().putStringSet("recovered", recoveredAddresses).apply()
        if (newTxCount > 0) {
            Log.i(TAG, "Recovered $newTxCount transactions from mempool.space")
        }

        // Fetch raw hex for all known txids + their vin txids
        if (!recoveryCancelled) {
            _recoveryStatus.value = _recoveryStatus.value.copy(
                phase = "Caching transaction data..."
            )
            fetchAndCacheTxHex()
        }

        _recoveryStatus.value = RecoveryStatus(
            totalAddresses = addressToScripthash.keys.size,
            recoveredAddresses = recoveredAddresses.size,
            totalTxFound = persistedHistory.values.sumOf { it.size },
            isRecovering = false,
            isComplete = true
        )
    }

    /**
     * Fetch raw hex for all known txids and their vin (input) txids.
     * Stores in txHexCache so transactionGet can serve them without network calls.
     */
    private suspend fun fetchAndCacheTxHex() {
        // Collect all txids from persisted history
        val allTxids = mutableSetOf<String>()
        for ((_, set) in persistedHistory) {
            for (entry in set) {
                val txid = entry.split(":").firstOrNull() ?: continue
                allTxids.add(txid)
            }
        }

        if (allTxids.isEmpty()) return

        // Find which txids we don't have cached yet
        val needed = allTxids.filter { !txHexCache.containsKey(it) }.toMutableSet()
        if (needed.isEmpty()) {
            Log.i(TAG, "All ${allTxids.size} tx hex already cached")
            return
        }

        Log.i(TAG, "Fetching hex for ${needed.size} transactions from mempool.space")
        val vinTxids = mutableSetOf<String>()

        for (txid in needed.toList()) {
            try {
                // Fetch full tx JSON to discover vin txids
                val txJson = HistoryRecovery.httpGetPublic("${HistoryRecovery.apiBase}/tx/$txid")
                if (txJson != null) {
                    val tx = JSONObject(txJson)
                    // Extract vin txids
                    val vin = tx.optJSONArray("vin")
                    if (vin != null) {
                        for (i in 0 until vin.length()) {
                            val vinTxid = vin.getJSONObject(i).optString("txid", "")
                            if (vinTxid.isNotEmpty()) vinTxids.add(vinTxid)
                        }
                    }
                }

                // Fetch raw hex
                val hex = HistoryRecovery.httpGetPublic("${HistoryRecovery.apiBase}/tx/$txid/hex")
                if (hex != null && hex.matches(Regex("[0-9a-fA-F]+"))) {
                    txHexCache[txid] = hex
                }
                Thread.sleep(200) // rate limit
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch tx $txid: ${e.message}")
            }
        }

        // Now fetch hex for vin txids we don't have
        val vinNeeded = vinTxids.filter { !txHexCache.containsKey(it) }
        if (vinNeeded.isNotEmpty()) {
            Log.i(TAG, "Fetching hex for ${vinNeeded.size} input transactions")
            for (txid in vinNeeded) {
                try {
                    val hex = HistoryRecovery.httpGetPublic("${HistoryRecovery.apiBase}/tx/$txid/hex")
                    if (hex != null && hex.matches(Regex("[0-9a-fA-F]+"))) {
                        txHexCache[txid] = hex
                    }
                    Thread.sleep(200) // rate limit
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch vin tx $txid: ${e.message}")
                }
            }
        }

        saveTxHexCache()
        Log.i(TAG, "Tx hex cache now has ${txHexCache.size} transactions")
    }

    /**
     * Proactively cache a tx's raw hex + its vin txids' hex from local bitcoind.
     * Called when we first discover a new tx (block is still available, not yet pruned).
     */
    private suspend fun proactivelyCacheTx(txid: String) {
        try {
            // Get the raw hex from bitcoind
            val height = getTxHeight(txid)
            if (height <= 0) return

            val blockHash = rpc.call("getblockhash", JSONArray().apply { put(height) })
                ?.optString("value", "") ?: return
            if (blockHash.isEmpty()) return

            // Get raw hex
            val hexResult = rpc.call("getrawtransaction", JSONArray().apply {
                put(txid); put(0); put(blockHash)
            })
            val hex = hexResult?.optString("value", "") ?: return
            if (hex.isEmpty()) return

            txHexCache[txid] = hex

            // Decode to find vin txids
            val decoded = rpc.call("decoderawtransaction", JSONArray().apply { put(hex) })
            val vin = decoded?.optJSONArray("vin")
            if (vin != null) {
                for (i in 0 until vin.length()) {
                    val vinTxid = vin.getJSONObject(i).optString("txid", "")
                    if (vinTxid.isNotEmpty() && !txHexCache.containsKey(vinTxid)) {
                        // Try to get vin tx from bitcoind (might be in a recent block)
                        val vinHexResult = rpc.call("getrawtransaction", JSONArray().apply {
                            put(vinTxid); put(0)
                        })
                        val vinHex = vinHexResult?.optString("value", "")
                        if (!vinHex.isNullOrEmpty() && !vinHexResult.optBoolean("_rpc_error", false)) {
                            txHexCache[vinTxid] = vinHex
                        }
                        // If vin tx is also pruned, it'll get fetched during next Recover History
                    }
                }
            }

            saveTxHexCache()
            Log.d(TAG, "Proactively cached tx $txid + vin txids")
        } catch (e: Exception) {
            Log.w(TAG, "Proactive cache failed for $txid: ${e.message}")
        }
    }

    /**
     * Add an address to the scripthash index.
     */
    private suspend fun indexAddress(address: String) {
        if (addressToScripthash.containsKey(address)) return

        // Get the scriptPubKey for this address
        val validateResult = rpc.call("validateaddress", JSONArray().apply { put(address) })
        val scriptPubKey = validateResult?.optString("scriptPubKey") ?: return

        val scripthash = computeScripthash(scriptPubKey)
        addressToScripthash[address] = scripthash
        scripthashToAddress.getOrPut(scripthash) { mutableSetOf() }.add(address)
    }

    // -- Query methods (called by ElectrumMethods) --

    /**
     * Get confirmed and unconfirmed balance for a scripthash.
     */
    // -- Query methods: pure descriptor wallet RPCs + persisted history --

    suspend fun getBalance(scripthash: String): Pair<Long, Long> {
        val addresses = scripthashToAddress[scripthash] ?: return Pair(0L, 0L)
        var confirmed = 0L
        var unconfirmed = 0L

        for (addr in addresses) {
            val result = walletRpc("listunspent", JSONArray().apply {
                put(0)     // minconf (include unconfirmed)
                put(9999999) // maxconf
                put(JSONArray().apply { put(addr) })
            })
            if (addr == "bc1q3tndgvlx66w95l3lahugqgfd08zwupdqqvxtds") {
                Log.d(TAG, "getBalance DEBUG addr=$addr result=${result?.toString()?.take(500)}")
            }
            val arr = result?.optJSONArray("value") ?: continue
            if (addr == "bc1q3tndgvlx66w95l3lahugqgfd08zwupdqqvxtds") {
                Log.d(TAG, "getBalance DEBUG arr.length=${arr.length()}")
            }
            for (i in 0 until arr.length()) {
                val utxo = arr.getJSONObject(i)
                val sats = btcToSats(utxo.getDouble("amount"))
                if (utxo.optInt("confirmations", 0) > 0) {
                    confirmed += sats
                } else {
                    unconfirmed += sats
                }
            }
        }

        Log.d(TAG, "getBalance scripthash=${scripthash.take(12)} confirmed=$confirmed unconfirmed=$unconfirmed")
        return Pair(confirmed, unconfirmed)
    }

    suspend fun getHistory(scripthash: String): JSONArray {
        val addresses = scripthashToAddress[scripthash] ?: return JSONArray()
        val seenTxids = mutableMapOf<String, Int>()  // txid -> height
        // Source 1: Persisted history (survives pruning)
        val persisted = persistedHistory[scripthash]
        if (persisted != null) {
            for (entry in persisted) {
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val txid = parts[0]
                    val height = parts[1].toIntOrNull() ?: 0
                    val existing = seenTxids[txid]
                    if (existing == null || (height > 0 && existing == 0)) {
                        seenTxids[txid] = height
                    }
                }
            }
        }

        // Source 2: Descriptor wallet (live data)
        for (addr in addresses) {
            val txResult = walletRpc("listtransactions", JSONArray().apply {
                put("*"); put(100); put(0); put(true)
            })
            val arr = txResult?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val tx = arr.getJSONObject(i)
                val txAddr = tx.optString("address", "")
                if (txAddr != addr) continue

                val txid = tx.getString("txid")
                val confirmations = tx.optInt("confirmations", 0)
                val height = if (confirmations > 0) {
                    val blockHash = tx.optString("blockhash", "")
                    if (blockHash.isNotEmpty()) {
                        val blockInfo = rpc.call("getblockheader", JSONArray().apply { put(blockHash) })
                        blockInfo?.optInt("height", 0) ?: 0
                    } else 0
                } else 0

                val existing = seenTxids[txid]
                if (existing == null || (height > 0 && existing == 0)) {
                    seenTxids[txid] = height
                }
                if (height > 0) {
                    persistTx(scripthash, txid, height)
                    // Proactively cache hex while block is still available
                    if (!txHexCache.containsKey(txid)) {
                        proactivelyCacheTx(txid)
                    }
                }
            }
        }

        val result = JSONArray()
        for ((txid, height) in seenTxids.entries.sortedBy { it.value }) {
            result.put(JSONObject().apply {
                put("tx_hash", txid)
                put("height", height)
            })
        }
        return result
    }

    suspend fun listUnspent(scripthash: String): JSONArray {
        val addresses = scripthashToAddress[scripthash] ?: return JSONArray()
        val result = JSONArray()
        val seen = mutableSetOf<String>()

        for (addr in addresses) {
            val rpcResult = walletRpc("listunspent", JSONArray().apply {
                put(0); put(9999999); put(JSONArray().apply { put(addr) })
            })
            val arr = rpcResult?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val utxo = arr.getJSONObject(i)
                val key = "${utxo.getString("txid")}:${utxo.getInt("vout")}"
                if (key in seen) continue
                seen.add(key)
                val confirmations = utxo.optInt("confirmations", 0)
                result.put(JSONObject().apply {
                    put("tx_hash", utxo.getString("txid"))
                    put("tx_pos", utxo.getInt("vout"))
                    put("height", if (confirmations > 0) {
                        val blockHash = utxo.optString("blockhash", "")
                        if (blockHash.isNotEmpty()) {
                            val blockInfo = rpc.call("getblockheader", JSONArray().apply { put(blockHash) })
                            blockInfo?.optInt("height", 0) ?: 0
                        } else 0
                    } else 0)
                    put("value", btcToSats(utxo.getDouble("amount")))
                })
            }
        }

        return result
    }

    /**
     * Get the status hash for a scripthash (SHA256 of "txid:height:" for each tx).
     * Returns null if no history exists.
     */
    suspend fun getStatusHash(scripthash: String): String? {
        val history = getHistory(scripthash)
        if (history.length() == 0) return null

        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until history.length()) {
            val entry = history.getJSONObject(i)
            val status = "${entry.getString("tx_hash")}:${entry.getInt("height")}:"
            md.update(status.toByteArray())
        }

        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Get all tracked scripthashes and their current status hashes.
     * Used by SubscriptionManager to detect changes.
     */
    fun getAllTrackedScripthashes(): Set<String> {
        // Only return scripthashes with known history (active addresses)
        // to avoid checking all 200 every poll cycle
        return persistedHistory.keys.toSet()
    }

    suspend fun getAllStatusHashes(): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        for (scripthash in scripthashToAddress.keys) {
            result[scripthash] = getStatusHash(scripthash)
        }
        return result
    }

    // -- Helper methods --

    /**
     * Make an RPC call to the tracking wallet specifically.
     */
    private suspend fun walletRpc(method: String, params: Any = JSONArray()): JSONObject? {
        return rpc.callWallet(WALLET_NAME, method, params)
    }

    /**
     * Compute Electrum-style scripthash: SHA256(scriptPubKey bytes), reversed.
     */
    private fun computeScripthash(scriptPubKeyHex: String): String {
        val scriptBytes = scriptPubKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(scriptBytes)
        return hash.reversed().toByteArray().joinToString("") { "%02x".format(it) }
    }

    /**
     * Add checksum to a descriptor using getdescriptorinfo RPC.
     */
    private suspend fun addChecksum(descriptor: String): String? {
        val result = rpc.call("getdescriptorinfo", JSONArray().apply { put(descriptor) })
        if (result == null || result.has("_rpc_error")) {
            Log.w(TAG, "getdescriptorinfo failed for ${descriptor.take(40)}: ${result?.optString("message")}")
            return null
        }
        val desc = result.optString("descriptor", "")
        return if (desc.isNotEmpty()) desc else null
    }

    /**
     * Convert zpub/ypub to xpub by swapping the version bytes.
     * zpub (0x04B24746) and ypub (0x049D7CB2) -> xpub (0x0488B21E)
     * All are Base58Check-encoded 78-byte payloads; only the first 4 bytes differ.
     */
    private fun convertToXpub(key: String): String {
        val decoded = base58CheckDecode(key) ?: return key
        // Replace version bytes with xpub version (0x0488B21E)
        decoded[0] = 0x04.toByte()
        decoded[1] = 0x88.toByte()
        decoded[2] = 0xB2.toByte()
        decoded[3] = 0x1E.toByte()
        return base58CheckEncode(decoded)
    }

    private fun base58CheckDecode(input: String): ByteArray? {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger.ZERO
        for (c in input) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) return null
            num = num.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(idx.toLong()))
        }
        val bytes = num.toByteArray()
        // Count leading 1s (zero bytes)
        val leadingZeros = input.takeWhile { it == '1' }.length
        val stripped = if (bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        val result = ByteArray(leadingZeros) + stripped
        // Verify checksum (last 4 bytes)
        val payload = result.dropLast(4).toByteArray()
        val checksum = result.takeLast(4).toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(
            MessageDigest.getInstance("SHA-256").digest(payload)
        )
        if (!hash.take(4).toByteArray().contentEquals(checksum)) return null
        return payload
    }

    private fun base58CheckEncode(payload: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val checksum = MessageDigest.getInstance("SHA-256").digest(
            MessageDigest.getInstance("SHA-256").digest(payload)
        )
        val data = payload + checksum.take(4).toByteArray()
        var num = java.math.BigInteger(1, data)
        val sb = StringBuilder()
        val base = java.math.BigInteger.valueOf(58)
        while (num > java.math.BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(alphabet[r.toInt()])
            num = q
        }
        // Leading zero bytes
        for (b in data) {
            if (b == 0.toByte()) sb.append('1') else break
        }
        return sb.reverse().toString()
    }

    private fun btcToSats(btc: Double): Long = (btc * 100_000_000L).toLong()
}
