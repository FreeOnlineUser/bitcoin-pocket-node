package com.pocketnode.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pocketnode.mempool.MempoolService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WorkManager worker to update home screen widget periodically
 */
class WidgetUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WidgetUpdateWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting widget update work")
        
        return try {
            val mempoolService = bindToMempoolService()
            if (mempoolService != null) {
                updateWidgetFromService(mempoolService)
                Result.success()
            } else {
                Log.w(TAG, "Could not bind to MempoolService")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            Result.failure()
        }
    }
    
    private suspend fun bindToMempoolService(): MempoolService? = suspendCancellableCoroutine { continuation ->
        var mempoolService: MempoolService? = null
        var serviceBound = false
        
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.d(TAG, "Connected to MempoolService")
                val binder = service as? MempoolService.MempoolBinder
                mempoolService = binder?.getService()
                serviceBound = true
                continuation.resume(mempoolService)
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "Disconnected from MempoolService")
                serviceBound = false
                mempoolService = null
            }
        }
        
        // Set up cancellation
        continuation.invokeOnCancellation {
            if (serviceBound) {
                applicationContext.unbindService(serviceConnection)
            }
        }
        
        // Bind to service
        val intent = Intent(applicationContext, MempoolService::class.java)
        val bindResult = applicationContext.bindService(
            intent, 
            serviceConnection, 
            Context.BIND_AUTO_CREATE
        )
        
        if (!bindResult) {
            Log.w(TAG, "Failed to bind to MempoolService")
            continuation.resume(null)
        }
    }
    
    private fun updateWidgetFromService(service: MempoolService) {
        try {
            // Get current state values
            val mempoolState = service.mempoolState.value
            val feeEstimates = service.feeEstimates.value
            
            val txCount = mempoolState.transactionCount
            val totalVmb = (mempoolState.totalVbytes / 1_000_000.0).toFloat()
            val nextBlockFee = feeEstimates.fastestFee
            
            Log.d(TAG, "Updating widget: $txCount tx, ${totalVmb}vMB, ${nextBlockFee}sat/vB")
            
            // Update widget data
            MempoolWidgetProvider.updateWidgetData(
                context = applicationContext,
                txCount = txCount,
                totalVmb = totalVmb,
                nextBlockFee = nextBlockFee
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget from service", e)
            throw e
        }
    }
}