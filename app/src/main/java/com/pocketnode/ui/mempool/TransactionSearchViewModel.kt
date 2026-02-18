package com.pocketnode.ui.mempool

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.mempool.MempoolService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TransactionSearchViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "TransactionSearchViewModel"
    }

    private var mempoolService: MempoolService? = null
    private var serviceBound = false

    // State flows
    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _searchResult = MutableStateFlow<TransactionSearchResult?>(null)
    val searchResult: StateFlow<TransactionSearchResult?> = _searchResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "MempoolService connected")
            val binder = service as MempoolService.MempoolBinder
            mempoolService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.d(TAG, "MempoolService disconnected")
            mempoolService = null
            serviceBound = false
        }
    }

    init {
        bindMempoolService()
    }

    override fun onCleared() {
        super.onCleared()
        unbindMempoolService()
    }

    private fun bindMempoolService() {
        val context = getApplication<Application>()
        val intent = Intent(context, MempoolService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMempoolService() {
        val context = getApplication<Application>()
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    fun updateSearchText(text: String) {
        _searchText.value = text.lowercase().replace(Regex("[^a-f0-9]"), "")
    }

    fun searchTransaction() {
        val txid = _searchText.value
        if (txid.length != 64) {
            _searchResult.value = TransactionSearchResult.Error("Transaction ID must be 64 characters")
            return
        }

        _isLoading.value = true
        _searchResult.value = null

        viewModelScope.launch {
            try {
                // TODO: Implement actual transaction search via RPC
                // For now, simulate search behavior
                
                // Simulate network delay
                kotlinx.coroutines.delay(1000)

                // Mock search result
                val mockResult = searchMockTransaction(txid)
                _searchResult.value = mockResult
                
            } catch (e: Exception) {
                Log.e(TAG, "Error searching transaction", e)
                _searchResult.value = TransactionSearchResult.Error("Search failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun searchMockTransaction(txid: String): TransactionSearchResult {
        // Mock implementation - replace with actual Bitcoin RPC call
        return if (txid.startsWith("a") || txid.startsWith("b")) {
            // Simulate found transaction
            val watchedTxs = mempoolService?.getWatchedTransactions() ?: emptyList()
            TransactionSearchResult.Found(
                TransactionDetails(
                    txid = txid,
                    feeRate = 25.5,
                    vsize = 225,
                    fee = 0.00005737, // 5737 sats
                    timeInMempool = "15m 32s",
                    projectedBlockPosition = 1, // In block #2
                    isWatched = watchedTxs.contains(txid)
                )
            )
        } else {
            // Simulate not found
            TransactionSearchResult.NotFound
        }
    }

    fun watchTransaction() {
        val currentResult = _searchResult.value
        if (currentResult is TransactionSearchResult.Found) {
            val txid = currentResult.transaction.txid
            val isCurrentlyWatched = currentResult.transaction.isWatched
            
            if (isCurrentlyWatched) {
                mempoolService?.unwatchTransaction(txid)
            } else {
                mempoolService?.watchTransaction(txid)
            }
            
            // Update the result to reflect the new watch status
            _searchResult.value = currentResult.copy(
                transaction = currentResult.transaction.copy(
                    isWatched = !isCurrentlyWatched
                )
            )
        }
    }
}

sealed class TransactionSearchResult {
    data class Found(val transaction: TransactionDetails) : TransactionSearchResult()
    object NotFound : TransactionSearchResult()
    data class Error(val message: String) : TransactionSearchResult()
}

data class TransactionDetails(
    val txid: String,
    val feeRate: Double, // sat/vB
    val vsize: Int,
    val fee: Double, // BTC
    val timeInMempool: String,
    val projectedBlockPosition: Int?, // Which projected block (0-based index)
    val isWatched: Boolean = false
)