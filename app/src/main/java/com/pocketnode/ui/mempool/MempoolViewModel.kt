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
import com.pocketnode.mempool.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MempoolViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MempoolViewModel"
    }

    private var mempoolService: MempoolService? = null
    private var serviceBound = false

    // State flows
    private val _mempoolState = MutableStateFlow(MempoolState())
    val mempoolState: StateFlow<MempoolState> = _mempoolState.asStateFlow()

    private val _gbtResult = MutableStateFlow<GbtResult?>(null)
    val gbtResult: StateFlow<GbtResult?> = _gbtResult.asStateFlow()

    private val _feeRateHistogram = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val feeRateHistogram: StateFlow<Map<Int, Int>> = _feeRateHistogram.asStateFlow()

    private val _selectedBlockDetails = MutableStateFlow<BlockDetails?>(null)
    val selectedBlockDetails: StateFlow<BlockDetails?> = _selectedBlockDetails.asStateFlow()
    
    private val _feeEstimates = MutableStateFlow(FeeEstimates())
    val feeEstimates: StateFlow<FeeEstimates> = _feeEstimates.asStateFlow()
    
    private val _rpcStatus = MutableStateFlow(RpcStatus.DISCONNECTED)
    val rpcStatus: StateFlow<RpcStatus> = _rpcStatus.asStateFlow()
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    
    private val _newBlockDetected = MutableStateFlow<String?>(null)
    val newBlockDetected: StateFlow<String?> = _newBlockDetected.asStateFlow()
    
    private val _confirmedTransaction = MutableStateFlow<ConfirmedTransactionEvent?>(null)
    val confirmedTransaction: StateFlow<ConfirmedTransactionEvent?> = _confirmedTransaction.asStateFlow()
    
    private var lastBestBlockHash: String? = null

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "MempoolService connected")
            val binder = service as MempoolService.MempoolBinder
            mempoolService = binder.getService()
            serviceBound = true
            
            // Start collecting service state flows
            viewModelScope.launch {
                mempoolService?.mempoolState?.collect {
                    _mempoolState.value = it
                }
            }
            
            viewModelScope.launch {
                mempoolService?.gbtResult?.collect {
                    _gbtResult.value = it
                }
            }
            
            viewModelScope.launch {
                mempoolService?.feeRateHistogram?.collect {
                    _feeRateHistogram.value = it
                }
            }
            
            viewModelScope.launch {
                mempoolService?.feeEstimates?.collect {
                    _feeEstimates.value = it
                }
            }
            
            viewModelScope.launch {
                mempoolService?.rpcStatus?.collect {
                    _rpcStatus.value = it
                }
            }
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

    fun startMempoolUpdates() {
        mempoolService?.setPollingEnabled(true)
    }

    fun stopMempoolUpdates() {
        mempoolService?.setPollingEnabled(false)
    }

    fun showBlockDetails(blockIndex: Int) {
        val currentGbt = _gbtResult.value
        if (currentGbt != null && blockIndex < currentGbt.blocks.size) {
            val block = currentGbt.blocks[blockIndex]
            val weight = currentGbt.blockWeights.getOrNull(blockIndex) ?: 0
            
            _selectedBlockDetails.value = BlockDetails(
                blockIndex = blockIndex,
                transactionCount = block.size,
                totalWeight = weight,
                transactions = block.toList()
            )
        }
    }

    fun clearBlockDetails() {
        _selectedBlockDetails.value = null
    }
    
    fun refreshMempool() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Trigger immediate update by restarting polling
                mempoolService?.let { service ->
                    // The service will automatically update all flows
                    // Just wait a bit for the update to complete
                    kotlinx.coroutines.delay(1000)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }
    
    fun clearNewBlockDetected() {
        _newBlockDetected.value = null
    }
    
    fun clearConfirmedTransaction() {
        _confirmedTransaction.value = null
    }
}

data class BlockDetails(
    val blockIndex: Int,
    val transactionCount: Int,
    val totalWeight: Int,
    val transactions: List<Int> // Transaction UIDs
)

data class ConfirmedTransactionEvent(
    val txid: String,
    val blockNumber: Int,
    val confirmations: Int
)