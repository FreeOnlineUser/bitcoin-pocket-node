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
                val service = mempoolService
                if (service == null) {
                    _searchResult.value = TransactionSearchResult.Error("Mempool service not available")
                    return@launch
                }

                when (val result = service.searchTransaction(txid)) {
                    is com.pocketnode.mempool.TransactionSearchResult.InMempool -> {
                        val timeInMempool = calculateTimeInMempool(result.entry.time)
                        
                        _searchResult.value = TransactionSearchResult.Found(
                            TransactionDetails(
                                txid = txid,
                                feeRate = result.entry.fee / result.entry.vsize,
                                vsize = result.entry.vsize,
                                fee = result.entry.fee,
                                timeInMempool = timeInMempool,
                                projectedBlockPosition = result.projectedBlockPosition,
                                isWatched = service.isWatched(txid)
                            )
                        )
                    }
                    
                    is com.pocketnode.mempool.TransactionSearchResult.Confirmed -> {
                        _searchResult.value = TransactionSearchResult.Found(
                            TransactionDetails(
                                txid = txid,
                                feeRate = 0.0, // Not available for confirmed tx
                                vsize = 0,     // Not available for confirmed tx
                                fee = 0.0,     // Not available for confirmed tx
                                timeInMempool = "Confirmed (${result.confirmations} confirmations)",
                                projectedBlockPosition = null,
                                isWatched = false
                            )
                        )
                    }
                    
                    is com.pocketnode.mempool.TransactionSearchResult.NotFound -> {
                        _searchResult.value = TransactionSearchResult.NotFound
                    }
                    
                    is com.pocketnode.mempool.TransactionSearchResult.Error -> {
                        _searchResult.value = TransactionSearchResult.Error(result.message)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error searching transaction", e)
                _searchResult.value = TransactionSearchResult.Error("Search failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun calculateTimeInMempool(unixTime: Long): String {
        val currentTime = System.currentTimeMillis() / 1000
        val secondsInMempool = currentTime - unixTime
        
        return when {
            secondsInMempool < 60 -> "${secondsInMempool}s"
            secondsInMempool < 3600 -> {
                val minutes = secondsInMempool / 60
                val seconds = secondsInMempool % 60
                "${minutes}m ${seconds}s"
            }
            secondsInMempool < 86400 -> {
                val hours = secondsInMempool / 3600
                val minutes = (secondsInMempool % 3600) / 60
                "${hours}h ${minutes}m"
            }
            else -> {
                val days = secondsInMempool / 86400
                val hours = (secondsInMempool % 86400) / 3600
                "${days}d ${hours}h"
            }
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