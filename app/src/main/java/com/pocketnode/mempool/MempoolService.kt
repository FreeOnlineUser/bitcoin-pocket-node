package com.pocketnode.mempool

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    
    // Bitcoin RPC client - assume it exists in the app
    // private lateinit var rpcClient: BitcoinRpcClient
    
    // GBT generator
    private var gbtGenerator: GbtGenerator? = null
    
    // Current mempool state
    private val currentMempool = ConcurrentHashMap<String, MempoolEntry>()
    private val txIdToUid = ConcurrentHashMap<String, Int>()
    private val uidToTxId = ConcurrentHashMap<Int, String>()
    private val uidCounter = AtomicInteger(1)
    
    // Watched transactions for confirmation tracking
    private val watchedTransactions = ConcurrentHashMap<String, Long>() // txid -> time added
    
    // StateFlow for UI updates
    private val _mempoolState = MutableStateFlow(MempoolState())
    val mempoolState: StateFlow<MempoolState> = _mempoolState.asStateFlow()
    
    private val _gbtResult = MutableStateFlow<GbtResult?>(null)
    val gbtResult: StateFlow<GbtResult?> = _gbtResult.asStateFlow()
    
    // Fee rate histogram data
    private val _feeRateHistogram = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val feeRateHistogram: StateFlow<Map<Int, Int>> = _feeRateHistogram.asStateFlow()

    inner class MempoolBinder : Binder() {
        fun getService(): MempoolService = this@MempoolService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MempoolService created")
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
        // TODO: Replace with actual RPC client calls
        // For now, we'll simulate the RPC calls
        
        try {
            // Simulate getrawmempool true call
            val newMempoolData = getRawMempool() ?: return
            
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
            
            // Update mempool state
            _mempoolState.value = MempoolState(
                transactionCount = currentMempool.size,
                totalVbytes = currentMempool.values.sumOf { it.vsize },
                totalFees = currentMempool.values.sumOf { it.fee }
            )
            
            // Update fee rate histogram
            updateFeeRateHistogram()
            
            // Run GBT if we have mempool data
            if (currentMempool.isNotEmpty()) {
                runGbtAlgorithm(addedTxIds, removedTxIds)
            }
            
            // Check watched transactions for confirmations
            checkWatchedTransactions()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateMempoolData", e)
        }
    }

    private suspend fun getRawMempool(): Map<String, MempoolEntry>? {
        // TODO: Implement actual Bitcoin RPC call to getrawmempool true
        // For now return empty map to avoid crashes during development
        return emptyMap()
        
        /*
        return try {
            val response = rpcClient.call("getrawmempool", listOf(true))
            val json = Json.parseToJsonElement(response).jsonObject
            
            json.mapValues { (_, value) ->
                Json.decodeFromJsonElement(MempoolEntry.serializer(), value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get raw mempool", e)
            null
        }
        */
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
        // TODO: Check if watched transactions have been confirmed
        // This would involve checking if they're still in the mempool
        // and potentially querying recent blocks
    }

    /**
     * Add a transaction to the watch list for confirmation tracking
     */
    fun watchTransaction(txId: String) {
        watchedTransactions[txId] = System.currentTimeMillis()
        Log.d(TAG, "Now watching transaction: $txId")
    }

    /**
     * Remove a transaction from the watch list
     */
    fun unwatchTransaction(txId: String) {
        watchedTransactions.remove(txId)
        Log.d(TAG, "Stopped watching transaction: $txId")
    }

    /**
     * Get list of currently watched transactions
     */
    fun getWatchedTransactions(): List<String> = watchedTransactions.keys.toList()

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