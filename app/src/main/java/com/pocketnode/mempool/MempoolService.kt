package com.pocketnode.mempool

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.rpc.RpcConfig
import com.pocketnode.rpc.RpcConfigDefaults
import com.pocketnode.rpc.RpcException
import com.pocketnode.storage.WatchListManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service that manages mempool data and GBT projections
 */
class MempoolService : Service() {
    companion object {
        private const val TAG = "MempoolService"
        private const val POLL_INTERVAL_MS = 10_000L // 10 seconds
        private const val MAX_BLOCK_WEIGHT = 4_000_000
        private const val MAX_BLOCKS = 8
    }

    private val binder = MempoolBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    
    // Bitcoin RPC client
    private lateinit var rpcClient: BitcoinRpcClient
    private var isRpcConnected = false
    
    // Watch list manager
    private lateinit var watchListManager: WatchListManager
    
    // GBT generator
    private var gbtGenerator: GbtGenerator? = null
    
    // Current mempool state
    private val currentMempool = ConcurrentHashMap<String, MempoolEntry>()
    private val txIdToUid = ConcurrentHashMap<String, Int>()
    private val uidToTxId = ConcurrentHashMap<Int, String>()
    private val uidCounter = AtomicInteger(1)
    
    // Note: Watched transactions now managed by WatchListManager
    
    // StateFlow for UI updates
    private val _mempoolState = MutableStateFlow(MempoolState())
    val mempoolState: StateFlow<MempoolState> = _mempoolState.asStateFlow()
    
    private val _gbtResult = MutableStateFlow<GbtResult?>(null)
    val gbtResult: StateFlow<GbtResult?> = _gbtResult.asStateFlow()
    
    // Fee rate histogram data
    private val _feeRateHistogram = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val feeRateHistogram: StateFlow<Map<Int, Int>> = _feeRateHistogram.asStateFlow()
    
    // Fee estimates from estimatesmartfee
    private val _feeEstimates = MutableStateFlow(FeeEstimates())
    val feeEstimates: StateFlow<FeeEstimates> = _feeEstimates.asStateFlow()
    
    // RPC connection status
    private val _rpcStatus = MutableStateFlow(RpcStatus.DISCONNECTED)
    val rpcStatus: StateFlow<RpcStatus> = _rpcStatus.asStateFlow()

    inner class MempoolBinder : Binder() {
        fun getService(): MempoolService = this@MempoolService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MempoolService created")
        initializeRpcClient()
        initializeWatchListManager()
        initializeGbtGenerator()
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MempoolService destroyed")
        stopPolling()
        gbtGenerator?.destroy()
        serviceScope.cancel()
    }

    private fun initializeRpcClient() {
        // Initialize defaults if needed
        RpcConfigDefaults.initializeDefaultsIfNeeded(this)
        
        val config = RpcConfig.load(this)
        rpcClient = BitcoinRpcClient(
            rpcHost = config.host,
            rpcPort = config.port,
            rpcUser = config.username,
            rpcPassword = config.password
        )
        Log.d(TAG, "RPC client initialized for ${config.host}:${config.port} (${config.network})")
    }
    
    private fun initializeWatchListManager() {
        watchListManager = WatchListManager(this)
        Log.d(TAG, "Watch list manager initialized")
    }

    private fun initializeGbtGenerator() {
        gbtGenerator = GbtGenerator.create(MAX_BLOCK_WEIGHT, MAX_BLOCKS)
        Log.d(TAG, "GBT generator initialized")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateMempoolData()
                    delay(POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating mempool data", e)
                    delay(POLL_INTERVAL_MS) // Continue polling even on error
                }
            }
        }
        Log.d(TAG, "Started mempool polling")
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "Stopped mempool polling")
    }

    private suspend fun updateMempoolData() {
        try {
            // First check RPC connection
            if (!isRpcConnected) {
                val pingResult = testRpcConnection()
                if (!pingResult) {
                    _rpcStatus.value = RpcStatus.DISCONNECTED
                    return
                }
                isRpcConnected = true
                _rpcStatus.value = RpcStatus.CONNECTED
            }
            
            // Get mempool data and info in parallel
            val mempoolDataDeferred = async { getRawMempool() }
            val mempoolInfoDeferred = async { getMempoolInfo() }
            val feeEstimatesDeferred = async { getFeeEstimates() }
            
            val newMempoolData = mempoolDataDeferred.await()
            val mempoolInfo = mempoolInfoDeferred.await()
            val feeEstimates = feeEstimatesDeferred.await()
            
            if (newMempoolData == null) return
            
            // Update fee estimates
            _feeEstimates.value = feeEstimates
            
            // Find new and removed transactions
            val newTxIds = newMempoolData.keys.toSet()
            val currentTxIds = currentMempool.keys.toSet()
            
            val addedTxIds = newTxIds - currentTxIds
            val removedTxIds = currentTxIds - newTxIds
            
            // Update current mempool
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
            
            // Update mempool state with real data from mempoolinfo
            _mempoolState.value = MempoolState(
                transactionCount = mempoolInfo?.size ?: currentMempool.size,
                totalVbytes = mempoolInfo?.bytes ?: currentMempool.values.sumOf { it.vsize },
                totalFees = currentMempool.values.sumOf { it.fee },
                vbytesPerSecond = 0.0 // TODO: Calculate from historical data
            )
            
            // Update fee rate histogram
            updateFeeRateHistogram()
            
            // Run GBT if we have mempool data
            if (currentMempool.isNotEmpty()) {
                runGbtAlgorithm(addedTxIds, removedTxIds)
            }
            
            // Check watched transactions for confirmations
            checkWatchedTransactions()
            
        } catch (e: RpcException) {
            Log.e(TAG, "RPC error in updateMempoolData", e)
            isRpcConnected = false
            _rpcStatus.value = RpcStatus.ERROR
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateMempoolData", e)
        }
    }

    private suspend fun testRpcConnection(): Boolean {
        return try {
            rpcClient.ping()
        } catch (e: Exception) {
            Log.w(TAG, "RPC connection test failed", e)
            false
        }
    }

    private suspend fun getRawMempool(): Map<String, MempoolEntry>? {
        return try {
            val json = rpcClient.getRawMempool()
            val result = mutableMapOf<String, MempoolEntry>()
            
            json.forEach { (txid, entryJson) ->
                try {
                    val entry = Json.decodeFromJsonElement(MempoolEntry.serializer(), entryJson)
                    result[txid] = entry
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
    
    private suspend fun getMempoolInfo(): MempoolInfo? {
        return try {
            val json = rpcClient.getMempoolInfo()
            MempoolInfo(
                size = json["size"]?.jsonPrimitive?.int ?: 0,
                bytes = json["bytes"]?.jsonPrimitive?.int ?: 0,
                usage = json["usage"]?.jsonPrimitive?.long ?: 0L,
                maxmempool = json["maxmempool"]?.jsonPrimitive?.long ?: 0L,
                mempoolminfee = json["mempoolminfee"]?.jsonPrimitive?.double ?: 0.0,
                minrelaytxfee = json["minrelaytxfee"]?.jsonPrimitive?.double ?: 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mempool info", e)
            null
        }
    }
    
    private suspend fun getFeeEstimates(): FeeEstimates {
        return try {
            val estimates1 = rpcClient.estimateSmartFee(1)
            val estimates3 = rpcClient.estimateSmartFee(3)
            val estimates6 = rpcClient.estimateSmartFee(6)
            
            FeeEstimates(
                fastestFee = estimates1["feerate"]?.jsonPrimitive?.double?.let { (it * 100_000_000).toInt() } ?: 0,
                halfHourFee = estimates3["feerate"]?.jsonPrimitive?.double?.let { (it * 100_000_000).toInt() } ?: 0,
                hourFee = estimates6["feerate"]?.jsonPrimitive?.double?.let { (it * 100_000_000).toInt() } ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get fee estimates", e)
            FeeEstimates()
        }
    }

    private fun runGbtAlgorithm(addedTxIds: Set<String>, removedTxIds: Set<String>) {
        try {
            val generator = gbtGenerator ?: return
            
            if (addedTxIds.isEmpty() && removedTxIds.isEmpty()) {
                // No changes, skip GBT run
                return
            }
            
            val maxUid = uidCounter.get()
            
            if (removedTxIds.isNotEmpty()) {
                // Use update method for incremental changes
                val newThreadTxs = addedTxIds.mapNotNull { txId ->
                    currentMempool[txId]?.let { entry ->
                        convertToThreadTransaction(txId, entry)
                    }
                }
                
                val removedUids = removedTxIds.mapNotNull { txIdToUid[it] }
                
                val result = generator.update(
                    newTxs = newThreadTxs,
                    removeTxs = removedUids,
                    maxUid = maxUid
                )
                
                _gbtResult.value = result
                Log.d(TAG, "GBT update completed: ${result?.blocks?.size} blocks projected")
                
            } else {
                // Full rebuild
                val allThreadTxs = currentMempool.entries.mapNotNull { (txId, entry) ->
                    convertToThreadTransaction(txId, entry)
                }
                
                val result = generator.make(
                    mempool = allThreadTxs,
                    maxUid = maxUid
                )
                
                _gbtResult.value = result
                Log.d(TAG, "GBT full run completed: ${result?.blocks?.size} blocks projected")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error running GBT algorithm", e)
        }
    }

    private fun convertToThreadTransaction(txId: String, entry: MempoolEntry): ThreadTransaction? {
        val uid = txIdToUid[txId] ?: return null
        
        // Convert depends (parent txids) to UIDs
        val inputUids = entry.depends.mapNotNull { parentTxId ->
            txIdToUid[parentTxId]
        }.toIntArray()
        
        // Calculate effective fee per vsize
        val effectiveFeePerVsize = entry.fee / entry.vsize.toDouble()
        
        // Use time as order (later transactions have higher order)
        val order = (entry.time and 0xFFFFFFFF).toInt()
        
        return ThreadTransaction(
            uid = uid,
            order = order,
            fee = entry.fee,
            weight = entry.weight,
            sigops = 0, // TODO: Get actual sigops count from RPC if available
            effectiveFeePerVsize = effectiveFeePerVsize,
            inputs = inputUids
        )
    }

    private fun updateFeeRateHistogram() {
        val histogram = mutableMapOf<Int, Int>()
        
        // Group transactions by fee rate ranges (sat/vB)
        currentMempool.values.forEach { entry ->
            val feeRate = (entry.fee / entry.vsize * 100_000_000).toInt() // Convert to sat/vB
            val bucket = when {
                feeRate <= 2 -> 1
                feeRate <= 4 -> 3
                feeRate <= 10 -> 5
                feeRate <= 20 -> 10
                feeRate <= 50 -> 20
                feeRate <= 100 -> 50
                else -> 100
            }
            histogram[bucket] = (histogram[bucket] ?: 0) + 1
        }
        
        _feeRateHistogram.value = histogram
    }

    private suspend fun checkWatchedTransactions() {
        val watchedTxIds = watchListManager.getWatchedTransactionIds()
        if (watchedTxIds.isEmpty()) return
        
        try {
            // Check which watched transactions are no longer in mempool
            val confirmedTxs = mutableListOf<String>()
            
            watchedTxIds.forEach { txid ->
                if (!currentMempool.containsKey(txid)) {
                    // Transaction is no longer in mempool, might be confirmed
                    try {
                        val txDetails = rpcClient.getRawTransaction(txid, true)
                        if (txDetails.jsonObject["confirmations"]?.jsonPrimitive?.int ?: 0 > 0) {
                            confirmedTxs.add(txid)
                            Log.d(TAG, "Watched transaction $txid has been confirmed")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to check confirmation for watched tx $txid", e)
                    }
                }
            }
            
            // Clean up confirmed transactions from watch list
            confirmedTxs.forEach { txid ->
                watchListManager.removeTransaction(txid)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking watched transactions", e)
        }
    }

    /**
     * Add a transaction to the watch list for confirmation tracking
     */
    fun watchTransaction(txId: String) {
        watchListManager.addTransaction(txId)
        Log.d(TAG, "Now watching transaction: $txId")
    }

    /**
     * Remove a transaction from the watch list
     */
    fun unwatchTransaction(txId: String) {
        watchListManager.removeTransaction(txId)
        Log.d(TAG, "Stopped watching transaction: $txId")
    }

    /**
     * Get list of currently watched transactions
     */
    fun getWatchedTransactions(): List<String> = watchListManager.getWatchedTransactionIds()
    
    /**
     * Check if a transaction is being watched
     */
    fun isWatched(txId: String): Boolean = watchListManager.isWatched(txId)

    /**
     * Enable/disable mempool polling based on UI visibility
     */
    fun setPollingEnabled(enabled: Boolean) {
        if (enabled && pollingJob?.isActive != true) {
            startPolling()
        } else if (!enabled && pollingJob?.isActive == true) {
            stopPolling()
        }
    }

    /**
     * Search for a transaction by ID
     */
    suspend fun searchTransaction(txid: String): TransactionSearchResult {
        return try {
            // First check if it's in current mempool
            currentMempool[txid]?.let { entry ->
                val uid = txIdToUid[txid]
                val blockPosition = findTransactionInProjectedBlocks(uid)
                
                return TransactionSearchResult.InMempool(
                    txid = txid,
                    entry = entry,
                    projectedBlockPosition = blockPosition
                )
            }
            
            // If not in mempool, try to get it via RPC
            val txDetails = rpcClient.getRawTransaction(txid, true).jsonObject
            val confirmations = txDetails["confirmations"]?.jsonPrimitive?.int ?: 0
            
            if (confirmations > 0) {
                TransactionSearchResult.Confirmed(
                    txid = txid,
                    confirmations = confirmations,
                    blockHash = txDetails["blockhash"]?.jsonPrimitive?.content
                )
            } else {
                TransactionSearchResult.NotFound
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error searching transaction $txid", e)
            TransactionSearchResult.Error("Search failed: ${e.message}")
        }
    }
    
    private fun findTransactionInProjectedBlocks(uid: Int?): Int? {
        if (uid == null) return null
        
        val currentGbt = _gbtResult.value ?: return null
        currentGbt.blocks.forEachIndexed { index, block ->
            if (block.contains(uid)) {
                return index
            }
        }
        return null
    }
}

/**
 * Current state of the mempool
 */
data class MempoolState(
    val transactionCount: Int = 0,
    val totalVbytes: Int = 0,
    val totalFees: Double = 0.0,
    val vbytesPerSecond: Double = 0.0, // Inflow rate
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Mempool info from getmempoolinfo RPC
 */
data class MempoolInfo(
    val size: Int,
    val bytes: Int,
    val usage: Long,
    val maxmempool: Long,
    val mempoolminfee: Double,
    val minrelaytxfee: Double
)

/**
 * Fee estimates from estimatesmartfee RPC
 */
data class FeeEstimates(
    val fastestFee: Int = 0, // sat/vB for next block
    val halfHourFee: Int = 0, // sat/vB for ~30 min
    val hourFee: Int = 0 // sat/vB for ~1 hour
)

/**
 * RPC connection status
 */
enum class RpcStatus {
    CONNECTED,
    DISCONNECTED,
    ERROR
}

/**
 * Transaction search result
 */
sealed class TransactionSearchResult {
    data class InMempool(
        val txid: String,
        val entry: MempoolEntry,
        val projectedBlockPosition: Int?
    ) : TransactionSearchResult()
    
    data class Confirmed(
        val txid: String,
        val confirmations: Int,
        val blockHash: String?
    ) : TransactionSearchResult()
    
    object NotFound : TransactionSearchResult()
    
    data class Error(val message: String) : TransactionSearchResult()
}