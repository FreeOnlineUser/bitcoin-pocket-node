package com.pocketnode.oracle

import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray

/**
 * Live UTXOracle estimate from transactions as they reach our mempool, before
 * they are mined. Same filters and price method as the block windows, over a
 * rolling [WINDOW_MS] of arrivals.
 *
 * Weaker than the block figures: unconfirmed transactions can be replaced or
 * never confirm, and padding the mempool costs only relay fees. Transactions
 * that leave the mempool are kept only if they were mined; replaced or evicted
 * ones are dropped.
 *
 * Needs a mempool, so only works in Max mode (Low/Away run blocks-only).
 */
class MempoolPrice(private val rpc: BitcoinRpcClient) {

    companion object {
        private const val TAG = "MempoolPrice"
        // 60 min: the phone keeps a 50 MB mempool (maxmempool=50), which turns away
        // low-fee transactions when busy, so arrivals run ~1.5 usable outputs a second.
        const val WINDOW_MS = 60 * 60 * 1000L
        private const val RECENT_BLOCKS_KEPT = 6
        // Below this many filtered outputs the histogram is too thin to trust.
        private const val MIN_OUTPUTS = 2_000
    }

    private class Entry(val arrivedAt: Long, val outputs: List<Double>?)

    private val tracked = HashMap<String, Entry>()
    // Seen but outside the window (too old at first load, or aged out while still
    // unconfirmed). Never fetched again, so a lingering tx can't count as new.
    private val expired = HashSet<String>()
    private val recentBlockTxids = ArrayDeque<Set<String>>()
    private var lastHeight = -1
    private var loaded = false

    /** Outputs counted in the last estimate, for display. */
    var sampleOutputs = 0
        private set

    /**
     * Pull new mempool arrivals and return a fresh estimate, or null if there
     * isn't enough data yet. [oracle] supplies the price calculation.
     */
    suspend fun update(oracle: UTXOracle, tipHeight: Int): OracleResult? {
        val now = System.currentTimeMillis()
        trackBlocks(tipHeight)

        if (!loaded) {
            // After a bitcoind restart the mempool reloads from mempool.dat. Wait for
            // that, or every reloaded tx would later look like a fresh arrival.
            val info = rpc.call("getmempoolinfo") ?: return null
            if (!info.optBoolean("loaded", false)) {
                Log.d(TAG, "Mempool still loading")
                return null
            }
            initialLoad(now)
            loaded = true
        } else {
            val mempool = rpc.call("getrawmempool")?.optJSONArray("value") ?: return null
            val current = HashSet<String>(mempool.length())
            for (i in 0 until mempool.length()) current.add(mempool.getString(i))
            // Gone from the mempool: keep if mined, drop if replaced or evicted.
            tracked.keys.retainAll { it in current || recentBlockTxids.any { set -> it in set } }
            expired.retainAll(current)
            for (txid in current) {
                if (txid !in tracked && txid !in expired) fetch(txid, now, current)
            }
        }

        val aged = tracked.filterValues { now - it.arrivedAt > WINDOW_MS }.keys
        expired.addAll(aged)
        tracked.keys.removeAll(aged)
        val outputs = tracked.values.flatMap { it.outputs ?: emptyList() }
        sampleOutputs = outputs.size
        if (outputs.size < MIN_OUTPUTS) {
            Log.d(TAG, "Only ${outputs.size} outputs in window, waiting for more")
            return null
        }
        return oracle.priceFromOutputs(outputs, tipHeight, now / 1000)
    }

    /** First run: take what's already in the mempool, back to the window start. */
    private suspend fun initialLoad(now: Long) {
        val verbose = rpc.callLongRunning("getrawmempool", JSONArray().put(true), timeoutMs = 120_000) ?: return
        val all = verbose.keys().asSequence().toHashSet()
        var fetched = 0
        for (txid in all) {
            val entryTime = verbose.optJSONObject(txid)?.optLong("time", 0L)?.times(1000) ?: continue
            if (now - entryTime > WINDOW_MS) {
                expired.add(txid)
                continue
            }
            fetch(txid, entryTime, all)
            fetched++
        }
        Log.i(TAG, "Loaded $fetched mempool transactions from the last ${WINDOW_MS / 60_000} min")
    }

    private suspend fun fetch(txid: String, arrivedAt: Long, mempool: Set<String>) {
        val tx = rpc.call("getrawtransaction", JSONArray().put(txid).put(true))
        if (tx == null || tx.has("_rpc_error")) {
            tracked[txid] = Entry(arrivedAt, null)
            return
        }
        // "Same window" for an unconfirmed tx: spending another mempool tx or a recent block's.
        val outputs = UTXOracle.filterTxOutputs(tx) { parent ->
            parent in mempool || recentBlockTxids.any { parent in it }
        }
        tracked[txid] = Entry(arrivedAt, outputs)
    }

    /** Remember the txids of the last few blocks, so mined transactions stay counted. */
    private suspend fun trackBlocks(tipHeight: Int) {
        if (tipHeight <= lastHeight) return
        val from = if (lastHeight < 0) tipHeight - RECENT_BLOCKS_KEPT + 1 else lastHeight + 1
        for (h in from..tipHeight) {
            try {
                val hash = rpc.call("getblockhash", JSONArray().put(h))?.optString("value") ?: continue
                val block = rpc.callLongRunning("getblock", JSONArray().put(hash).put(1), timeoutMs = 60_000) ?: continue
                val txs = block.optJSONArray("tx") ?: continue
                recentBlockTxids.addLast((0 until txs.length()).map { txs.getString(it) }.toHashSet())
                while (recentBlockTxids.size > RECENT_BLOCKS_KEPT) recentBlockTxids.removeFirst()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read block $h: ${e.message}")
            }
        }
        lastHeight = tipHeight
    }

    fun reset() {
        tracked.clear()
        expired.clear()
        recentBlockTxids.clear()
        lastHeight = -1
        loaded = false
        sampleOutputs = 0
    }
}
