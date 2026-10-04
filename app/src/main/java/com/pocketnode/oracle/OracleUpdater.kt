package com.pocketnode.oracle

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the UTXOracle price current while the node runs, whether or not the
 * dashboard is on screen. BitcoindService starts it; OracleCard only reads [state].
 *
 * Two numbers come out of the same cached block outputs:
 *  - [OracleState.result]: the standard 144-block (~24h) window.
 *  - [OracleState.recent]: the same algorithm over the newest [RECENT_BLOCKS] blocks.
 *    Follows moves sooner, with more noise.
 */
object OracleUpdater {

    private const val TAG = "OracleUpdater"
    private const val POLL_MS = 30_000L
    // Measured 2026-10-04 against Kraken hourly VWAP over a day of mainnet blocks:
    // 6-block windows tracked within 0.04% (worst 0.14%), while the 144-block
    // average trailed the hour-by-hour price by up to 0.5% on a 0.8% up-day.
    const val RECENT_BLOCKS = 6

    data class OracleState(
        val result: OracleResult? = null,
        val recent: OracleResult? = null,
        val updatedAt: Long = 0,
        val running: Boolean = false,
        val progress: String = "",
        val error: String? = null
    )

    private val _state = MutableStateFlow(OracleState())
    val state: StateFlow<OracleState> = _state.asStateFlow()

    private val lock = Mutex()
    private var job: Job? = null
    private var oracle: UTXOracle? = null
    private lateinit var appContext: Context

    fun start(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        if (_state.value.result == null) _state.value = _state.value.copy(result = loadResult(), updatedAt = loadUpdatedAt())
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    tick()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Oracle update failed", e)
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Full rescan of the newest 144 blocks, on request from the card. */
    fun refresh(scope: CoroutineScope) {
        if (!::appContext.isInitialized) return
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val o = newOracle() ?: return@withLock
                run(o) { o.getPriceRecentBlocks() }
            }
        }
    }

    private suspend fun tick() = lock.withLock {
        val rpc = rpc() ?: return@withLock
        val info = rpc.getBlockchainInfo() ?: return@withLock
        if (info.has("_rpc_error")) return@withLock
        val blocks = info.optLong("blocks", 0).toInt()
        val headers = info.optLong("headers", 0).toInt()
        val synced = !info.optBoolean("initialblockdownload", true) && blocks >= headers - 1
        if (!synced || blocks <= 0) return@withLock

        val o = oracle ?: newOracle()?.also { fresh ->
            val cached = loadBlocks()
            if (cached.isNotEmpty()) {
                fresh.setCachedBlocks(cached)
                Log.i(TAG, "Restored ${cached.size} cached blocks")
            }
            oracle = fresh
        } ?: return@withLock

        if (o.cachedBlocks.isEmpty()) {
            run(o) { o.getPriceRecentBlocks() }
            return@withLock
        }
        if (blocks <= o.cachedBlocks.last().height) {
            // Nothing new; make sure the recent estimate exists after a restore.
            if (_state.value.recent == null) publishRecent(o)
            return@withLock
        }
        try {
            run(o) { o.incrementalUpdate(blocks) }
        } catch (e: Exception) {
            // The cached window fell below the prune height: rebuild from recent blocks.
            if (e.message?.contains("getblock", ignoreCase = true) == true) {
                Log.w(TAG, "Incremental update hit a pruned block, rebuilding window")
                run(o) { o.getPriceRecentBlocks() }
            } else throw e
        }
    }

    private suspend fun run(o: UTXOracle, compute: suspend () -> OracleResult?) {
        _state.value = _state.value.copy(running = true, error = null)
        val progressJob = CoroutineScope(Dispatchers.Default).launch {
            o.progress.collect { _state.value = _state.value.copy(progress = it) }
        }
        try {
            val r = compute()
            if (r != null) {
                val now = System.currentTimeMillis()
                saveResult(r, now)
                saveBlocks(o.cachedBlocks)
                _state.value = _state.value.copy(result = r, updatedAt = now)
                Log.i(TAG, "Price $${r.price} through block ${r.blockRange.last}")
                publishRecent(o)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = if (e.message?.contains("getblock", ignoreCase = true) == true)
                    "Waiting for more blocks. Price data needs recent block history."
                else e.message ?: "Unknown error"
            )
            throw e
        } finally {
            progressJob.cancel()
            _state.value = _state.value.copy(running = false, progress = "")
        }
    }

    private fun publishRecent(o: UTXOracle) {
        val recent = o.priceFromCache(RECENT_BLOCKS)
        _state.value = _state.value.copy(recent = recent)
        recent?.let { Log.i(TAG, "Recent ${RECENT_BLOCKS}-block estimate $${it.price}") }
    }

    private fun rpc(): BitcoinRpcClient? =
        ConfigGenerator.readCredentials(appContext)?.let { BitcoinRpcClient(it.first, it.second) }

    private fun newOracle(): UTXOracle? = rpc()?.let { UTXOracle(it) }

    // ── Persistence (same files OracleCard used, so existing caches carry over) ──

    private val prefs by lazy { appContext.getSharedPreferences("oracle_cache", Context.MODE_PRIVATE) }
    private val blockFile by lazy { File(appContext.filesDir, "oracle_blocks.json") }

    private fun saveResult(r: OracleResult, now: Long) {
        prefs.edit()
            .putInt("price", r.price)
            .putString("date", r.date)
            .putInt("blockStart", r.blockRange.first)
            .putInt("blockEnd", r.blockRange.last)
            .putInt("outputCount", r.outputCount)
            .putFloat("deviation", r.deviation.toFloat())
            .putLong("cachedAt", now)
            .remove("blockData")
            .apply()
    }

    private fun loadResult(): OracleResult? {
        val price = prefs.getInt("price", -1)
        if (price < 0) return null
        return OracleResult(
            price = price,
            date = prefs.getString("date", "") ?: "",
            blockRange = prefs.getInt("blockStart", 0)..prefs.getInt("blockEnd", 0),
            outputCount = prefs.getInt("outputCount", 0),
            deviation = prefs.getFloat("deviation", 0f).toDouble()
        )
    }

    private fun loadUpdatedAt(): Long = prefs.getLong("cachedAt", 0)

    private suspend fun saveBlocks(blocks: List<BlockOutputs>) = withContext(Dispatchers.IO) {
        try {
            val json = org.json.JSONArray()
            for (b in blocks) {
                val outs = org.json.JSONArray()
                for (o in b.outputs) outs.put(o)
                json.put(org.json.JSONObject().put("h", b.height).put("hash", b.hash).put("t", b.time).put("o", outs))
            }
            blockFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save block data", e)
        }
    }

    private suspend fun loadBlocks(): List<BlockOutputs> = withContext(Dispatchers.IO) {
        try {
            if (!blockFile.exists()) return@withContext emptyList()
            val json = org.json.JSONArray(blockFile.readText())
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                val outs = obj.getJSONArray("o")
                BlockOutputs(
                    height = obj.getInt("h"),
                    hash = obj.getString("hash"),
                    time = obj.getLong("t"),
                    txids = emptySet(),
                    outputs = (0 until outs.length()).map { outs.getDouble(it) }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load block data", e)
            emptyList()
        }
    }
}
