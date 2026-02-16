package com.pocketnode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.pocketnode.MainActivity
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Controls Bitcoin sync based on network state.
 * Pauses sync on cellular (unless user overrides), resumes on WiFi.
 */
class SyncController(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
    private val rpcClient: BitcoinRpcClient
) {
    companion object {
        private const val TAG = "SyncController"
        private const val SYNC_CHANNEL_ID = "sync_status_channel"
        private const val SYNC_NOTIFICATION_ID = 2
        const val PREF_KEY_ALLOW_CELLULAR = "allow_cellular_sync"
        const val PREF_KEY_CELLULAR_BUDGET_MB = "cellular_budget_mb"
        const val PREF_KEY_WIFI_BUDGET_MB = "wifi_budget_mb"
        const val PREF_KEY_CELLULAR_CONFIRMED = "cellular_confirmed_once"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)

    private val _syncPaused = MutableStateFlow(false)
    val syncPaused: StateFlow<Boolean> = _syncPaused.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    var allowCellularSync: Boolean
        get() = prefs.getBoolean(PREF_KEY_ALLOW_CELLULAR, false)
        set(value) {
            prefs.edit().putBoolean(PREF_KEY_ALLOW_CELLULAR, value).apply()
            // Re-evaluate immediately
            scope.launch { evaluateNetworkState(networkMonitor.networkState.value) }
        }

    var cellularBudgetMb: Long
        get() = prefs.getLong(PREF_KEY_CELLULAR_BUDGET_MB, 0) // 0 = no limit
        set(value) = prefs.edit().putLong(PREF_KEY_CELLULAR_BUDGET_MB, value).apply()

    var wifiBudgetMb: Long
        get() = prefs.getLong(PREF_KEY_WIFI_BUDGET_MB, 0) // 0 = no limit
        set(value) = prefs.edit().putLong(PREF_KEY_WIFI_BUDGET_MB, value).apply()

    private var cellularConfirmed: Boolean
        get() = prefs.getBoolean(PREF_KEY_CELLULAR_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(PREF_KEY_CELLULAR_CONFIRMED, value).apply()

    fun start() {
        createNotificationChannel()

        scope.launch {
            networkMonitor.networkState.collect { state ->
                evaluateNetworkState(state)
            }
        }

        // Check budget periodically
        scope.launch {
            while (isActive) {
                delay(60_000)
                checkBudget()
            }
        }
    }

    fun stop() {
        scope.cancel()
        notificationManager.cancel(SYNC_NOTIFICATION_ID)
    }

    /** Allow sync on cellular for this session (one-time confirmation) */
    fun confirmCellularSync() {
        cellularConfirmed = true
        allowCellularSync = true
    }

    private suspend fun evaluateNetworkState(state: NetworkState) {
        when (state) {
            NetworkState.WIFI -> {
                resumeSync()
                notificationManager.cancel(SYNC_NOTIFICATION_ID)
            }
            NetworkState.CELLULAR -> {
                if (allowCellularSync) {
                    resumeSync()
                    notificationManager.cancel(SYNC_NOTIFICATION_ID)
                } else {
                    pauseSync()
                    showPausedNotification()
                }
            }
            NetworkState.OFFLINE -> {
                // bitcoind handles offline gracefully, but mark as paused for UI
                _syncPaused.value = true
                notificationManager.cancel(SYNC_NOTIFICATION_ID)
            }
        }
    }

    private suspend fun pauseSync() {
        try {
            val params = JSONArray().apply { put(false) }
            rpcClient.call("setnetworkactive", params)
            _syncPaused.value = true
            Log.i(TAG, "Sync paused — on mobile data")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause sync", e)
        }
    }

    private suspend fun resumeSync() {
        try {
            val params = JSONArray().apply { put(true) }
            rpcClient.call("setnetworkactive", params)
            _syncPaused.value = false
            Log.i(TAG, "Sync resumed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume sync", e)
        }
    }

    private fun checkBudget() {
        // Check cellular budget
        val cellBudget = cellularBudgetMb
        if (cellBudget > 0) {
            val cellUsedMb = networkMonitor.getMonthCellularUsage() / (1024 * 1024)
            if (cellUsedMb >= cellBudget && allowCellularSync) {
                Log.i(TAG, "Cellular budget exceeded ($cellUsedMb MB / $cellBudget MB), pausing cellular")
                allowCellularSync = false
            }
        }

        // Check WiFi budget
        val wifiBudget = wifiBudgetMb
        if (wifiBudget > 0) {
            val wifiUsedMb = networkMonitor.getMonthWifiUsage() / (1024 * 1024)
            if (wifiUsedMb >= wifiBudget && networkMonitor.networkState.value == NetworkState.WIFI) {
                Log.i(TAG, "WiFi budget exceeded ($wifiUsedMb MB / $wifiBudget MB), pausing")
                scope.launch { pauseSync() }
                showBudgetExceededNotification("WiFi")
            }
        }
    }

    private fun showBudgetExceededNotification(networkType: String) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, SYNC_CHANNEL_ID)
            .setContentTitle("₿ Pocket Node")
            .setContentText("Sync paused — $networkType data budget exceeded")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .build()
        notificationManager.notify(SYNC_NOTIFICATION_ID, notification)
    }

    private fun showPausedNotification() {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(context, SYNC_CHANNEL_ID)
            .setContentTitle("₿ Pocket Node")
            .setContentText("Sync paused — on mobile data")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .build()

        notificationManager.notify(SYNC_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SYNC_CHANNEL_ID,
            "Sync Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about sync pause/resume on network changes"
        }
        notificationManager.createNotificationChannel(channel)
    }
}
