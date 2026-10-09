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
 *  - [OracleState.recent]: the same algorithm over [RECENT_BLOCKS] blocks. In the public
 *    edition the window ends 6 confirmations deep ([Edition.RECENT_SKIP]), as the
 *    UTXOracle licence counts newer blocks as live data.
 *    Follows moves sooner, with more noise.
 */
object OracleUpdater {

    private const val TAG = "OracleUpdater"
    private const val POLL_MS = 30_000L
    // Measured 2026-10-04 against Kraken hourly VWAP over a day of mainnet blocks:
    // 6-block windows tracked within 0.04% (worst 0.14%), while the 144-block
    // average trailed the hour-by-hour price by up to 0.5% on a 0.8% up-day.
    const val RECENT_BLOCKS = 6
    // A live figure more than this far from the last-hour mined price is a
    // mismatch, not a market move: 15% in ten minutes would be extraordinary.
    private const val MAX_DEVIATION = 0.15
    // Mined 6-block figures are checked looser, against the day's price and against
    // their neighbours: a wrong peak is double or half, a real day can move 15%.
    private const val MINED_MAX_DEVIATION = 0.3

    data class OracleState(
        val result: OracleResult? = null,
        val recent: OracleResult? = null,
        /** Mempool estimate (Max mode only), and why it's missing when it is. */
        val live: OracleResult? = null,
        val liveNote: String? = null,
        val liveOutputs: Int = 0,
        val liveMinutes: Int = 0,
        val updatedAt: Long = 0,
        val running: Boolean = false,
        val progress: String = "",
        val error: String? = null
    )

    /** Which figure the dashboard headline and the converter use. */
    enum class PriceWindow(val label: String) { DAY("Block window"), HOUR(Edition.RECENT_LABEL), LIVE("Live") }

    /** The windows this edition offers: Live only in the internal one. */
    val availableWindows = PriceWindow.entries.filter { it != PriceWindow.LIVE || Edition.INTERNAL }

    private const val KEY_WINDOW = "price_window"
    private val _window = MutableStateFlow(PriceWindow.DAY)
    val window: StateFlow<PriceWindow> = _window.asStateFlow()
    private var windowLoaded = false

    fun loadWindow(context: Context) {
        if (windowLoaded) return
        windowLoaded = true
        val prefs = context.getSharedPreferences("oracle_cache", Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_WINDOW, null)
        _window.value = availableWindows.firstOrNull { it.name == saved } ?: PriceWindow.DAY
        _chartShown.value = prefs.getBoolean(KEY_CHART, false)
    }

    // ── Week of prices ──

    private const val KEY_CHART = "price_chart"
    private val _chartShown = MutableStateFlow(false)
    /** Whether the dashboard shows the 7-day chart (off unless the user turns it on). */
    val chartShown: StateFlow<Boolean> = _chartShown.asStateFlow()

    fun setChartShown(context: Context, shown: Boolean) {
        context.getSharedPreferences("oracle_cache", Context.MODE_PRIVATE).edit().putBoolean(KEY_CHART, shown).apply()
        _chartShown.value = shown
    }

    private val _history = MutableStateFlow<List<PricePoint>>(emptyList())
    /** One last-hour price per block, for the past week. */
    val history: StateFlow<List<PricePoint>> = _history.asStateFlow()
    private val _historyStatus = MutableStateFlow<String?>(null)
    /** Progress while missing days are rebuilt from blocks, null otherwise. */
    val historyStatus: StateFlow<String?> = _historyStatus.asStateFlow()

    private val priceHistory by lazy { PriceHistory(File(appContext.filesDir, "oracle_history.json")).apply { load() } }
    private var backfillJob: Job? = null
    private const val BACKFILL_RECHECK_MS = 30 * 60_000L

    fun setWindow(context: Context, w: PriceWindow) {
        context.getSharedPreferences("oracle_cache", Context.MODE_PRIVATE).edit().putString(KEY_WINDOW, w.name).apply()
        _window.value = w
    }

    private val _state = MutableStateFlow(OracleState())
    val state: StateFlow<OracleState> = _state.asStateFlow()

    private val lock = Mutex()
    private var job: Job? = null
    private var liveJob: Job? = null
    private var mempool: LivePriceSource? = null
    private var liveOracle: UTXOracle? = null
    private var oracle: UTXOracle? = null
    private lateinit var appContext: Context

    fun start(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        if (_state.value.result == null) _state.value = _state.value.copy(result = loadResult(), updatedAt = loadUpdatedAt())
        if (job?.isActive == true) return
        backfillJob = scope.launch(Dispatchers.IO) {
            publishHistory()
            delay(60_000)  // let the block figures go first
            while (isActive) {
                try {
                    backfill()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Price history backfill failed", e)
                } finally {
                    _historyStatus.value = null
                }
                delay(BACKFILL_RECHECK_MS)
            }
        }
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
        // Separate loop: the first mempool load can take minutes and must not
        // hold up the block-based figures. Internal edition only.
        if (Edition.INTERNAL) liveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    liveTick()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Live price update failed", e)
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        liveJob?.cancel()
        liveJob = null
        backfillJob?.cancel()
        backfillJob = null
    }

    private suspend fun liveTick() {
        val maxMode = com.pocketnode.power.PowerModeManager.modeFlow.value == com.pocketnode.power.PowerModeManager.Mode.MAX
        if (!maxMode) {
            // Low/Away run blocks-only: no mempool, so no live figure. Start clean next time.
            mempool?.reset()
            _state.value = _state.value.copy(live = null, liveOutputs = 0, liveNote = "Live needs Max mode (Low and Away keep no mempool)")
            return
        }
        val rpc = rpc() ?: return
        val info = rpc.getBlockchainInfo() ?: return
        if (info.has("_rpc_error") || info.optBoolean("initialblockdownload", true)) return
        // Own oracle instance: keeps its progress messages off the block figures' card text.
        val o = liveOracle ?: UTXOracle(rpc).also { liveOracle = it }
        val mp = mempool ?: Edition.liveSource(rpc)?.also { mempool = it } ?: return
        if (_state.value.live == null) _state.value = _state.value.copy(liveNote = "Collecting mempool data…")
        var r = mp.update(o, info.optLong("blocks", 0).toInt())
        // With ~700 outputs the histogram match sometimes locks onto double or half
        // the price (seen 2026-10-07: $168k and $42k between $84k readings). Mined
        // prices are the reference: a live figure far off them is dropped, keeping
        // the last good one.
        val ref = _state.value.recent ?: _state.value.result
        if (r != null && ref != null && kotlin.math.abs(r.price.toDouble() / ref.price - 1) > MAX_DEVIATION) {
            Log.w(TAG, "Live estimate $${r.price} rejected, last hour is $${ref.price}")
            r = null
        }
        _state.value = _state.value.copy(
            live = r ?: _state.value.live,
            liveOutputs = mp.sampleOutputs,
            liveMinutes = mp.windowMinutes,
            liveNote = if (r == null && _state.value.live == null) "Collecting mempool data (${mp.sampleOutputs} outputs)…" else null
        )
        r?.let { Log.i(TAG, "Live estimate $${it.price} from ${mp.sampleOutputs} outputs (t=${System.currentTimeMillis() / 1000})") }
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
            recordHistory(o)
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
                recordHistory(o)
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
        val recent = o.priceFromCache(RECENT_BLOCKS, o.cachedBlocks.size - Edition.RECENT_SKIP)
        // Six blocks can lock onto double or half the price the same way live does
        // (seen 2026-10-09: $164k between $82k readings). Keep the last good figure.
        val ref = _state.value.result
        if (recent != null && ref != null && kotlin.math.abs(recent.price.toDouble() / ref.price - 1) > MINED_MAX_DEVIATION) {
            Log.w(TAG, "Recent estimate $${recent.price} rejected, block window is $${ref.price}")
            return
        }
        _state.value = _state.value.copy(recent = recent)
        recent?.let { Log.i(TAG, "Recent ${RECENT_BLOCKS}-block estimate $${it.price}") }
    }

    /** Adds a history point for every cached block that has a full last-hour window. */
    private fun recordHistory(o: UTXOracle) {
        val blocks = o.cachedBlocks
        var added = 0
        for (end in RECENT_BLOCKS..blocks.size - Edition.RECENT_SKIP) {
            val b = blocks[end - 1]
            if (priceHistory.has(b.height)) continue
            o.priceFromCache(RECENT_BLOCKS, end)?.let {
                priceHistory.add(PricePoint(b.height, b.time, it.price))
                added++
            }
        }
        if (added > 0) publishHistory()
    }

    private fun publishHistory() {
        priceHistory.trim()
        priceHistory.dropOutliers(MINED_MAX_DEVIATION)
        priceHistory.save()
        _history.value = priceHistory.all()
    }

    /**
     * Prices the week's blocks that fall before the cached 144, or that are missing
     * after a gap, from blocks still on disk. Runs at start and every 30 minutes; does
     * nothing once the week is complete.
     */
    private suspend fun backfill() {
        val rpc = rpc() ?: return
        val info = rpc.getBlockchainInfo() ?: return
        if (info.has("_rpc_error") || info.optBoolean("initialblockdownload", true)) return
        val tip = info.optLong("blocks", 0).toInt()
        val pruneHeight = info.optLong("pruneheight", 0).toInt()
        // Blocks from the cached window get their points in recordHistory.
        val cacheFirst = lock.withLock { oracle?.cachedBlocks?.firstOrNull()?.height } ?: return
        val coveredFrom = cacheFirst + RECENT_BLOCKS - 1

        val tipTime = blockTime(rpc, tip) ?: return
        val weekStart = firstBlockAfter(rpc, tipTime - PriceHistory.KEEP_SECONDS, tip - 1300, tip) ?: return
        // A point needs RECENT_BLOCKS blocks ending at it, all still on disk.
        val from = maxOf(weekStart, pruneHeight + RECENT_BLOCKS - 1)
        val missing = (from until coveredFrom).filter { !priceHistory.has(it) }
        if (missing.isEmpty()) return
        Log.i(TAG, "Price history: pricing ${missing.size} blocks from $from")

        val o = UTXOracle(rpc)
        val window = ArrayDeque<BlockOutputs>()
        suspend fun take(h: Int) {
            // Same-window filter against the blocks just before it.
            val recent = window.flatMapTo(HashSet()) { it.txids }
            window.addLast(o.blockOutputsAt(h, recent))
            while (window.size > RECENT_BLOCKS) window.removeFirst()
        }
        var done = 0
        for (h in missing) {
            _historyStatus.value = "Filling in the week: ${"%,d".format(done)} of ${"%,d".format(missing.size)} blocks"
            try {
                if (window.lastOrNull()?.height != h - 1) {
                    window.clear()
                    for (k in h - RECENT_BLOCKS + 1 until h) take(k)
                }
                take(h)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pruned since we looked, or a busy node: skip it, the next run retries.
                window.clear()
                continue
            }
            if (window.size == RECENT_BLOCKS) {
                o.priceFromBlocks(window.toList())?.let {
                    priceHistory.add(PricePoint(h, window.last().time, it.price))
                }
            }
            if (++done % 24 == 0) publishHistory()
        }
        publishHistory()
        Log.i(TAG, "Price history: done, ${priceHistory.all().size} points")
    }

    private suspend fun blockTime(rpc: BitcoinRpcClient, height: Int): Long? {
        val hash = rpc.call("getblockhash", org.json.JSONArray().put(height))?.optString("value") ?: return null
        return rpc.call("getblockheader", org.json.JSONArray().put(hash))?.optLong("time")
    }

    /** Lowest height in [lo, hi] whose block time is at least [time] (block times are close to ordered). */
    private suspend fun firstBlockAfter(rpc: BitcoinRpcClient, time: Long, lo: Int, hi: Int): Int? {
        var a = lo.coerceAtLeast(0)
        var b = hi
        while (a < b) {
            val mid = (a + b) / 2
            val t = blockTime(rpc, mid) ?: return null
            if (t >= time) b = mid else a = mid + 1
        }
        return a
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
