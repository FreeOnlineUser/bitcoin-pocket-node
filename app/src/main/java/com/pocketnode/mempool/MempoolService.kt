package com.pocketnode.mempool

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.storage.WatchListManager
import com.pocketnode.notification.TransactionNotificationManager
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MempoolService : Service() {
    companion object {
        private const val TAG = "MempoolService"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val MAX_BLOCK_WEIGHT = 4_000_000
        private const val MAX_BLOCKS = 8
    }

    private val binder = MempoolBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var rpcClient: BitcoinRpcClient? = null
    private var isRpcConnected = false

    private lateinit var watchListManager: WatchListManager
    private lateinit var notificationManager: TransactionNotificationManager
    private var gbtGenerator: GbtGenerator? = null

    private val currentMempool = ConcurrentHashMap<String, MempoolEntry>()
    private val txIdToUid = ConcurrentHashMap<String, Int>()
    private val uidToTxId = ConcurrentHashMap<Int, String>()
    private val uidCounter = AtomicInteger(1)

    private val _mempoolState = MutableStateFlow(MempoolState())
    val mempoolState: StateFlow<MempoolState> = _mempoolState.asStateFlow()

    private val _gbtResult = MutableStateFlow<GbtResult?>(null)
    val gbtResult: StateFlow<GbtResult?> = _gbtResult.asStateFlow()

    private val _feeRateHistogram = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val feeRateHistogram: StateFlow<Map<Int, Int>> = _feeRateHistogram.asStateFlow()

    private val _feeEstimates = MutableStateFlow(FeeEstimates())
    val feeEstimates: StateFlow<FeeEstimates> = _feeEstimates.asStateFlow()

    private val _projectedBlocks = MutableStateFlow<List<ProjectedBlockInfo>>(emptyList())
    val projectedBlocks: StateFlow<List<ProjectedBlockInfo>> = _projectedBlocks.asStateFlow()

    private val _latestBlock = MutableStateFlow<LatestBlockInfo?>(null)
    val latestBlock: StateFlow<LatestBlockInfo?> = _latestBlock.asStateFlow()

    private val _rpcStatus = MutableStateFlow(RpcStatus.DISCONNECTED)
    val rpcStatus: StateFlow<RpcStatus> = _rpcStatus.asStateFlow()

    fun reinitializeRpcClient() {
        isRpcConnected = false
        _rpcStatus.value = RpcStatus.DISCONNECTED
        currentMempool.clear()
        txIdToUid.clear()
        uidToTxId.clear()
        uidCounter.set(1)
        _gbtResult.value = null
        initializeRpcClient()
    }

    inner class MempoolBinder : Binder() {
        fun getService(): MempoolService = this@MempoolService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MempoolService created")
        initializeRpcClient()
        watchListManager = WatchListManager(this)
        notificationManager = TransactionNotificationManager(this)
        gbtGenerator = GbtGenerator.create(MAX_BLOCK_WEIGHT, MAX_BLOCKS)
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        gbtGenerator?.destroy()
        serviceScope.cancel()
    }

    private fun initializeRpcClient() {
        val creds = ConfigGenerator.readCredentials(this)
        if (creds != null) {
            rpcClient = BitcoinRpcClient(creds.first, creds.second)
            Log.d(TAG, "RPC client initialized with stored credentials")
        } else {
            Log.w(TAG, "No RPC credentials available yet")
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateMempoolData()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating mempool data", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun updateMempoolData() {
        val rpc = rpcClient ?: run {
            initializeRpcClient()
            rpcClient ?: return
        }

        try {
            if (!isRpcConnected) {
                val ping = rpc.call("ping")
                if (ping == null || ping.has("_rpc_error")) {
                    _rpcStatus.value = RpcStatus.DISCONNECTED
                    return
                }
                isRpcConnected = true
                _rpcStatus.value = RpcStatus.CONNECTED
            }

            val (newMempoolData, mempoolInfo, feeEst) = coroutineScope {
                val mempoolDeferred = async { getRawMempool(rpc) }
                val infoDeferred = async { getMempoolInfo(rpc) }
                val feeDeferred = async { getFeeEstimates(rpc) }
                Triple(mempoolDeferred.await(), infoDeferred.await(), feeDeferred.await())
            }

            if (newMempoolData == null) return
            _feeEstimates.value = feeEst

            val newTxIds = newMempoolData.keys.toSet()
            val currentTxIds = currentMempool.keys.toSet()
            val addedTxIds = newTxIds - currentTxIds
            val removedTxIds = currentTxIds - newTxIds

            removedTxIds.forEach { txId ->
                currentMempool.remove(txId)
                val uid = txIdToUid.remove(txId)
                uid?.let { uidToTxId.remove(it) }
            }
            addedTxIds.forEach { txId ->
                newMempoolData[txId]?.let { entry ->
                    currentMempool[txId] = entry
                    val uid = uidCounter.getAndIncrement()
                    txIdToUid[txId] = uid
                    uidToTxId[uid] = txId
                }
            }

            _mempoolState.value = MempoolState(
                transactionCount = mempoolInfo?.size ?: currentMempool.size,
                totalVbytes = mempoolInfo?.bytes ?: currentMempool.values.sumOf { it.vsize },
                totalFees = currentMempool.values.sumOf { it.fee }
            )

            updateFeeRateHistogram()

            if (currentMempool.isNotEmpty()) {
                runGbtAlgorithm(addedTxIds, removedTxIds)
            } else {
                _gbtResult.value = null
                _projectedBlocks.value = emptyList()
            }

            fetchLatestBlock(rpc)
            checkWatchedTransactions(rpc)
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateMempoolData", e)
            isRpcConnected = false
            _rpcStatus.value = RpcStatus.ERROR
        }
    }

    private suspend fun getRawMempool(rpc: BitcoinRpcClient): Map<String, MempoolEntry>? {
        return try {
            val params = JSONArray().apply { put(true) } // verbose=true
            val json = rpc.call("getrawmempool", params) ?: return null
            if (json.has("_rpc_error")) return null

            // The result is wrapped; for getrawmempool verbose, bitcoind returns a JSONObject
            // Our RPC client wraps it if it's already a JSONObject, so we use it directly
            val result = mutableMapOf<String, MempoolEntry>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val txid = keys.next()
                if (txid == "value" || txid == "_rpc_error") continue
                try {
                    val entryJson = json.getJSONObject(txid)
                    result[txid] = MempoolEntry.fromJson(entryJson)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse mempool entry for $txid", e)
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get raw mempool", e)
            null
        }
    }

    private suspend fun getMempoolInfo(rpc: BitcoinRpcClient): MempoolInfo? {
        return try {
            val json = rpc.call("getmempoolinfo") ?: return null
            if (json.has("_rpc_error")) return null
            MempoolInfo(
                size = json.optInt("size", 0),
                bytes = json.optInt("bytes", 0),
                usage = json.optLong("usage", 0L),
                maxmempool = json.optLong("maxmempool", 0L),
                mempoolminfee = json.optDouble("mempoolminfee", 0.0),
                minrelaytxfee = json.optDouble("minrelaytxfee", 0.0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mempool info", e)
            null
        }
    }

    private suspend fun getFeeEstimates(rpc: BitcoinRpcClient): FeeEstimates {
        return try {
            val est1 = rpc.call("estimatesmartfee", JSONArray().apply { put(1) })
            val est3 = rpc.call("estimatesmartfee", JSONArray().apply { put(3) })
            val est6 = rpc.call("estimatesmartfee", JSONArray().apply { put(6) })
            FeeEstimates(
                fastestFee = est1?.optDouble("feerate", 0.0)?.let { (it * 100_000_000).toInt() } ?: 0,
                halfHourFee = est3?.optDouble("feerate", 0.0)?.let { (it * 100_000_000).toInt() } ?: 0,
                hourFee = est6?.optDouble("feerate", 0.0)?.let { (it * 100_000_000).toInt() } ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get fee estimates", e)
            FeeEstimates()
        }
    }

    private fun runGbtAlgorithm(addedTxIds: Set<String>, removedTxIds: Set<String>) {
        try {
            val generator = gbtGenerator ?: return
            if (addedTxIds.isEmpty() && removedTxIds.isEmpty()) return

            // Always pack the whole mempool. Packing only what arrived since the last
            // poll left the view showing one small block whenever anything left.
            val allThreadTxs = currentMempool.entries.mapNotNull { (txId, entry) ->
                convertToThreadTransaction(txId, entry)
            }
            val result = generator.make(mempool = allThreadTxs, maxUid = uidCounter.get())
            _gbtResult.value = result
            result?.let { computeProjectedBlockInfo(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error running GBT algorithm", e)
        }
    }

    private fun computeProjectedBlockInfo(gbtResult: GbtResult) {
        try {
            val blockInfos = gbtResult.blocks.mapIndexed { index, block ->
                val feeRates = mutableListOf<Double>()
                val weights = mutableListOf<Int>()
                var totalFees = 0.0
                val totalWeight = gbtResult.blockWeights.getOrNull(index) ?: 0

                for (uid in block) {
                    val txId = uidToTxId[uid] ?: continue
                    val entry = currentMempool[txId] ?: continue
                    val feeRate = entry.effectiveFee / entry.vsize.toDouble() * 100_000_000.0
                    feeRates.add(feeRate)
                    weights.add(entry.weight)
                    totalFees += entry.effectiveFee
                }

                val sortedRates = feeRates.sorted()
                val minFeeRate = sortedRates.firstOrNull() ?: 0.0
                val maxFeeRate = sortedRates.lastOrNull() ?: 0.0
                val medianFeeRate = if (sortedRates.isNotEmpty()) {
                    val mid = sortedRates.size / 2
                    if (sortedRates.size % 2 == 0) (sortedRates[mid - 1] + sortedRates[mid]) / 2.0
                    else sortedRates[mid]
                } else 0.0

                data class BandDef(val range: ClosedFloatingPointRange<Double>, val label: String)
                val bandDefs = listOf(
                    BandDef(0.0..2.0, "magenta"),
                    BandDef(2.0..4.0, "purple"),
                    BandDef(4.0..10.0, "blue"),
                    BandDef(10.0..20.0, "green"),
                    BandDef(20.0..50.0, "yellow"),
                    BandDef(50.0..100.0, "orange"),
                    BandDef(100.0..Double.MAX_VALUE, "red")
                )

                val totalWeightForBands = weights.sum().toFloat().coerceAtLeast(1f)
                val bands = bandDefs.mapNotNull { bandDef ->
                    var bandWeight = 0
                    for (i in feeRates.indices) {
                        if (feeRates[i] >= bandDef.range.start && (feeRates[i] < bandDef.range.endInclusive || bandDef.range.endInclusive == Double.MAX_VALUE)) {
                            bandWeight += weights[i]
                        }
                    }
                    if (bandWeight > 0) FeeRateBand(feeRateRange = bandDef.range, proportion = bandWeight / totalWeightForBands) else null
                }

                ProjectedBlockInfo(
                    index = index, transactionCount = block.size, totalWeight = totalWeight,
                    totalFees = totalFees, minFeeRate = minFeeRate, maxFeeRate = maxFeeRate,
                    medianFeeRate = medianFeeRate, feeRateBands = bands
                )
            }
            _projectedBlocks.value = blockInfos
        } catch (e: Exception) {
            Log.e(TAG, "Error computing projected block info", e)
        }
    }

    private suspend fun fetchLatestBlock(rpc: BitcoinRpcClient) {
        try {
            val hashResult = rpc.call("getbestblockhash") ?: return
            val blockHash = hashResult.optString("value", "") 
            if (blockHash.isEmpty()) return
            if (_latestBlock.value?.hash == blockHash) return

            val blockJson = rpc.call("getblock", JSONArray().apply { put(blockHash); put(1) }) ?: return
            if (blockJson.has("_rpc_error")) return

            val txArray = blockJson.optJSONArray("tx")
            _latestBlock.value = LatestBlockInfo(
                height = blockJson.optInt("height", 0),
                hash = blockHash,
                time = blockJson.optLong("time", 0L),
                txCount = blockJson.optInt("nTx", txArray?.length() ?: 0),
                size = blockJson.optInt("size", 0),
                weight = blockJson.optInt("weight", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching latest block", e)
        }
    }

    private fun convertToThreadTransaction(txId: String, entry: MempoolEntry): ThreadTransaction? {
        val uid = txIdToUid[txId] ?: return null
        val inputUids = entry.depends.mapNotNull { txIdToUid[it] }.toIntArray()
        val effectiveFeePerVsize = entry.effectiveFee / entry.vsize.toDouble()
        val order = (entry.time and 0xFFFFFFFF).toInt()
        return ThreadTransaction(uid = uid, order = order, fee = entry.effectiveFee, weight = entry.weight, sigops = 0, effectiveFeePerVsize = effectiveFeePerVsize, inputs = inputUids)
    }

    private fun updateFeeRateHistogram() {
        val histogram = mutableMapOf<Int, Int>()
        currentMempool.values.forEach { entry ->
            val feeRate = (entry.effectiveFee / entry.vsize * 100_000_000).toInt()
            val bucket = when {
                feeRate <= 2 -> 1; feeRate <= 4 -> 3; feeRate <= 10 -> 5
                feeRate <= 20 -> 10; feeRate <= 50 -> 20; feeRate <= 100 -> 50; else -> 100
            }
            histogram[bucket] = (histogram[bucket] ?: 0) + 1
        }
        _feeRateHistogram.value = histogram
    }

    private suspend fun checkWatchedTransactions(rpc: BitcoinRpcClient) {
        val watchedTxIds = watchListManager.getWatchedTransactionIds()
        if (watchedTxIds.isEmpty()) return
        try {
            val confirmedTxs = mutableListOf<String>()
            watchedTxIds.forEach { txid ->
                if (!currentMempool.containsKey(txid)) {
                    try {
                        val txDetails = rpc.call("getrawtransaction", JSONArray().apply { put(txid); put(true) })
                        val confirmations = txDetails?.optInt("confirmations", 0) ?: 0
                        if (confirmations > 0) {
                            val blockHeight = txDetails?.optInt("blockheight", 0) ?: 0
                            confirmedTxs.add(txid)
                            notificationManager.notifyTransactionConfirmed(txid, blockHeight, confirmations)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to check confirmation for $txid", e)
                    }
                }
            }
            confirmedTxs.forEach { watchListManager.removeTransaction(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking watched transactions", e)
        }
    }

    fun watchTransaction(txId: String) { watchListManager.addTransaction(txId) }
    fun unwatchTransaction(txId: String) { watchListManager.removeTransaction(txId) }
    fun getWatchedTransactions(): List<String> = watchListManager.getWatchedTransactionIds()
    fun isWatched(txId: String): Boolean = watchListManager.isWatched(txId)

    fun setPollingEnabled(enabled: Boolean) {
        if (enabled && pollingJob?.isActive != true) startPolling()
        else if (!enabled && pollingJob?.isActive == true) stopPolling()
    }

    suspend fun searchTransaction(txid: String): TransactionSearchResult {
        return try {
            currentMempool[txid]?.let { entry ->
                val uid = txIdToUid[txid]
                val blockPosition = findTransactionInProjectedBlocks(uid)
                return TransactionSearchResult.InMempool(txid, entry, blockPosition)
            }

            val rpc = rpcClient ?: return TransactionSearchResult.Error("RPC not available")
            val txDetails = rpc.call("getrawtransaction", JSONArray().apply { put(txid); put(true) })
            if (txDetails == null || txDetails.has("_rpc_error")) return TransactionSearchResult.NotFound

            val confirmations = txDetails.optInt("confirmations", 0)
            if (confirmations > 0) {
                TransactionSearchResult.Confirmed(txid, confirmations, txDetails.optString("blockhash", null))
            } else {
                TransactionSearchResult.NotFound
            }
        } catch (e: Exception) {
            TransactionSearchResult.Error("Search failed: ${e.message}")
        }
    }

    private fun findTransactionInProjectedBlocks(uid: Int?): Int? {
        if (uid == null) return null
        val currentGbt = _gbtResult.value ?: return null
        currentGbt.blocks.forEachIndexed { index, block -> if (block.contains(uid)) return index }
        return null
    }
}

data class MempoolState(
    val transactionCount: Int = 0,
    val totalVbytes: Int = 0,
    val totalFees: Double = 0.0,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class MempoolInfo(
    val size: Int, val bytes: Int, val usage: Long,
    val maxmempool: Long, val mempoolminfee: Double, val minrelaytxfee: Double
)

data class FeeEstimates(
    val fastestFee: Int = 0, val halfHourFee: Int = 0, val hourFee: Int = 0
)

enum class RpcStatus { CONNECTED, DISCONNECTED, ERROR }

sealed class TransactionSearchResult {
    data class InMempool(val txid: String, val entry: MempoolEntry, val projectedBlockPosition: Int?) : TransactionSearchResult()
    data class Confirmed(val txid: String, val confirmations: Int, val blockHash: String?) : TransactionSearchResult()
    object NotFound : TransactionSearchResult()
    data class Error(val message: String) : TransactionSearchResult()
}
