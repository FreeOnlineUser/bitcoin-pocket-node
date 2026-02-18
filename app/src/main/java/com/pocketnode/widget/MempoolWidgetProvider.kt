package com.pocketnode.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import androidx.work.*
import com.pocketnode.MainActivity
import com.pocketnode.R
import java.util.concurrent.TimeUnit

/**
 * Home screen widget showing current mempool stats
 */
class MempoolWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "MempoolWidgetProvider"
        private const val WIDGET_UPDATE_WORKER = "widget_update_worker"
        
        // Widget data keys
        private const val PREF_TX_COUNT = "tx_count"
        private const val PREF_TOTAL_VMB = "total_vmb"
        private const val PREF_NEXT_BLOCK_FEE = "next_block_fee"
        private const val PREF_FEE_LEVEL = "fee_level"
        private const val PREF_LAST_UPDATE = "last_update"
        
        // Fee level thresholds (sat/vB)
        private const val FEE_LOW_THRESHOLD = 20
        private const val FEE_MODERATE_THRESHOLD = 50
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widgets")
        
        // Schedule periodic updates
        schedulePeriodicUpdates(context)
        
        // Update all widgets immediately
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "First widget added - scheduling updates")
        schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Last widget removed - canceling updates")
        cancelPeriodicUpdates(context)
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_mempool)
        
        // Load cached data from SharedPreferences
        val prefs = context.getSharedPreferences("mempool_widget", Context.MODE_PRIVATE)
        val txCount = prefs.getInt(PREF_TX_COUNT, 0)
        val totalVmb = prefs.getFloat(PREF_TOTAL_VMB, 0f)
        val nextBlockFee = prefs.getInt(PREF_NEXT_BLOCK_FEE, 0)
        val feeLevel = prefs.getString(PREF_FEE_LEVEL, "unknown") ?: "unknown"
        val lastUpdate = prefs.getLong(PREF_LAST_UPDATE, 0)
        
        // Update widget text
        views.setTextViewText(R.id.widget_tx_count, formatTxCount(txCount))
        views.setTextViewText(R.id.widget_vmb, formatVmb(totalVmb))
        views.setTextViewText(R.id.widget_fee_rate, formatFeeRate(nextBlockFee))
        views.setTextViewText(R.id.widget_last_update, formatLastUpdate(lastUpdate))
        
        // Set fee level indicator color
        val indicatorColor = when (feeLevel) {
            "low" -> Color.parseColor("#00FF00")  // Green
            "moderate" -> Color.parseColor("#FFFF00")  // Yellow
            "high" -> Color.parseColor("#FF0000")  // Red
            else -> Color.parseColor("#6A6A6A")  // Gray
        }
        views.setInt(R.id.widget_fee_indicator, "setBackgroundColor", indicatorColor)
        
        // Set up click intent to open the app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        
        // Update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
        Log.d(TAG, "Updated widget $appWidgetId: $txCount tx, ${totalVmb}vMB, ${nextBlockFee}sat/vB")
    }
    
    private fun schedulePeriodicUpdates(context: Context) {
        val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(5, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WIDGET_UPDATE_WORKER,
            ExistingPeriodicWorkPolicy.REPLACE,
            updateRequest
        )
    }
    
    private fun cancelPeriodicUpdates(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_UPDATE_WORKER)
    }
    
    private fun formatTxCount(count: Int): String {
        return when {
            count == 0 -> "No data"
            count >= 10_000 -> "${count / 1000}k tx"
            count >= 1_000 -> String.format("%.1fk tx", count / 1000.0)
            else -> "$count tx"
        }
    }
    
    private fun formatVmb(vmb: Float): String {
        return when {
            vmb == 0f -> "--"
            vmb >= 100 -> "${vmb.toInt()} vMB"
            vmb >= 10 -> String.format("%.1f vMB", vmb)
            else -> String.format("%.2f vMB", vmb)
        }
    }
    
    private fun formatFeeRate(satPerVb: Int): String {
        return if (satPerVb > 0) "$satPerVb sat/vB" else "-- sat/vB"
    }
    
    private fun formatLastUpdate(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        
        val now = System.currentTimeMillis()
        val diff = (now - timestamp) / 1000 // seconds
        
        return when {
            diff < 60 -> "Now"
            diff < 3600 -> "${diff / 60}m ago"
            diff < 86400 -> "${diff / 3600}h ago"
            else -> "${diff / 86400}d ago"
        }
    }
    
    companion object {
        /**
         * Update widget data from MempoolService
         */
        fun updateWidgetData(
            context: Context,
            txCount: Int,
            totalVmb: Float,
            nextBlockFee: Int
        ) {
            val feeLevel = when {
                nextBlockFee <= FEE_LOW_THRESHOLD -> "low"
                nextBlockFee <= FEE_MODERATE_THRESHOLD -> "moderate"
                else -> "high"
            }
            
            // Save to SharedPreferences
            val prefs = context.getSharedPreferences("mempool_widget", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(PREF_TX_COUNT, txCount)
                .putFloat(PREF_TOTAL_VMB, totalVmb)
                .putInt(PREF_NEXT_BLOCK_FEE, nextBlockFee)
                .putString(PREF_FEE_LEVEL, feeLevel)
                .putLong(PREF_LAST_UPDATE, System.currentTimeMillis())
                .apply()
            
            // Trigger widget updates
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, MempoolWidgetProvider::class.java)
            )
            
            widgetIds.forEach { widgetId ->
                val provider = MempoolWidgetProvider()
                provider.updateAppWidget(context, appWidgetManager, widgetId)
            }
        }
    }
}