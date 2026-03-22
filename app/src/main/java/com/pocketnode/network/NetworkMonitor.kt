package com.pocketnode.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NetworkState {
    WIFI, CELLULAR, OFFLINE
}

data class DataUsageEntry(
    val date: String,           // yyyy-MM-dd
    val wifiRx: Long = 0,      // bytes
    val wifiTx: Long = 0,
    val cellularRx: Long = 0,
    val cellularTx: Long = 0
)

/**
 * Usage data exposed as reactive state. UI collects this flow directly.
 */
data class UsageState(
    val today: DataUsageEntry = DataUsageEntry(date = ""),
    val recentDays: List<DataUsageEntry> = emptyList(),
    val monthCellular: Long = 0
)

/**
 * Monitors network connectivity and tracks data usage per network type.
 * Singleton: use NetworkMonitor.getInstance(context) to get the shared instance.
 */
class NetworkMonitor private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context.applicationContext).also { instance = it }
            }
        }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(NetworkState.OFFLINE)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    // Reactive usage state: UI collects this flow, no remember caching needed
    private val _usageState = MutableStateFlow(UsageState())
    val usageState: StateFlow<UsageState> = _usageState.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("network_data_usage", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val uid: Int = android.os.Process.myUid()
    private var lastRxBytes: Long = TrafficStats.getUidRxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
    private var lastTxBytes: Long = TrafficStats.getUidTxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
    private var lastNetworkState: NetworkState = NetworkState.OFFLINE

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    init {
        android.util.Log.i("DataUsage", "NetworkMonitor init: uid=$uid baseline=↓${lastRxBytes}/↑${lastTxBytes} (trafficStats since boot)")
        refreshUsageState()
    }

    fun start() {
        if (started) return
        started = true

        // Determine initial state
        _networkState.value = currentNetworkState()
        lastNetworkState = _networkState.value

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateState()
            }

            override fun onLost(network: Network) {
                updateState()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                updateState()
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)

        // Periodically sample data usage
        scope.launch {
            while (isActive) {
                sampleDataUsage()
                delay(30_000) // every 30s
            }
        }
    }

    fun stop() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
        scope.cancel()
    }

    private fun updateState() {
        val newState = currentNetworkState()
        sampleDataUsage() // capture usage before state change
        _networkState.value = newState
        lastNetworkState = newState
    }

    private fun currentNetworkState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork
            ?: return NetworkState.OFFLINE
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkState.OFFLINE

        // Android's TRANSPORT_VPN masks the underlying transport, so we can't tell if a VPN
        // runs over WiFi or cellular by checking transports alone. Instead, we use the metered
        // capability: carriers mark cellular as metered, so metered VPN = cellular underneath.
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !isMetered -> NetworkState.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && isMetered -> NetworkState.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> if (isMetered) NetworkState.CELLULAR else NetworkState.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.WIFI
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> if (isMetered) NetworkState.CELLULAR else NetworkState.WIFI
            else -> NetworkState.OFFLINE
        }
    }

    private fun sampleDataUsage() {
        val currentRx = TrafficStats.getUidRxBytes(uid)
        val currentTx = TrafficStats.getUidTxBytes(uid)

        if (currentRx == TrafficStats.UNSUPPORTED.toLong()) return

        val deltaRx = (currentRx - lastRxBytes).coerceAtLeast(0)
        val deltaTx = (currentTx - lastTxBytes).coerceAtLeast(0)

        lastRxBytes = currentRx
        lastTxBytes = currentTx

        if (deltaRx == 0L && deltaTx == 0L) return

        val today = todayKey()
        val state = lastNetworkState

        prefs.edit().apply {
            when (state) {
                NetworkState.WIFI -> {
                    putLong("${today}_wifi_rx", prefs.getLong("${today}_wifi_rx", 0) + deltaRx)
                    putLong("${today}_wifi_tx", prefs.getLong("${today}_wifi_tx", 0) + deltaTx)
                }
                NetworkState.CELLULAR -> {
                    putLong("${today}_cell_rx", prefs.getLong("${today}_cell_rx", 0) + deltaRx)
                    putLong("${today}_cell_tx", prefs.getLong("${today}_cell_tx", 0) + deltaTx)
                }
                NetworkState.OFFLINE -> { /* no-op */ }
            }
            apply()
        }

        // Update reactive state
        refreshUsageState()

        // Log every sample with context for debugging data usage
        val todayUsage = getUsageForDate(today)
        val totalToday = todayUsage.wifiRx + todayUsage.wifiTx + todayUsage.cellularRx + todayUsage.cellularTx
        val powerMode = try { com.pocketnode.power.PowerModeManager.modeFlow.value } catch (_: Exception) { "unknown" }
        android.util.Log.d("DataUsage",
            "delta=↓${formatDelta(deltaRx)}/↑${formatDelta(deltaTx)} " +
            "today=${formatDelta(totalToday)} " +
            "net=$state power=$powerMode " +
            "trafficStats=↓${formatDelta(currentRx)}/↑${formatDelta(currentTx)}")
    }

    private fun formatDelta(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1fGB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    /** Refresh the reactive usage state from SharedPreferences */
    private fun refreshUsageState() {
        _usageState.value = UsageState(
            today = getUsageForDate(todayKey()),
            recentDays = getRecentUsage(7),
            monthCellular = getMonthUsage("cell")
        )
    }

    /** Get usage for a specific date (yyyy-MM-dd) */
    fun getUsageForDate(date: String): DataUsageEntry {
        return DataUsageEntry(
            date = date,
            wifiRx = prefs.getLong("${date}_wifi_rx", 0),
            wifiTx = prefs.getLong("${date}_wifi_tx", 0),
            cellularRx = prefs.getLong("${date}_cell_rx", 0),
            cellularTx = prefs.getLong("${date}_cell_tx", 0)
        )
    }

    /** Get usage for the last N days */
    fun getRecentUsage(days: Int = 7): List<DataUsageEntry> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        return (0 until days).map { i ->
            val date = fmt.format(cal.time)
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            getUsageForDate(date)
        }
    }

    /** Get total cellular usage for current month */
    /** Clear all stored data usage history and reset baseline */
    fun clearAllUsage() {
        prefs.edit().clear().apply()
        lastRxBytes = TrafficStats.getUidRxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
        lastTxBytes = TrafficStats.getUidTxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it }
        refreshUsageState()
        android.util.Log.i("NetworkMonitor", "All data usage history cleared, baseline reset")
    }

    fun getMonthCellularUsage(): Long = getMonthUsage("cell")

    /** Get total WiFi usage for current month */
    fun getMonthWifiUsage(): Long = getMonthUsage("wifi")

    private fun getMonthUsage(type: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val monthPrefix = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val cal = java.util.Calendar.getInstance()
        var total = 0L
        val dayOfMonth = cal.get(java.util.Calendar.DAY_OF_MONTH)
        for (i in 0 until dayOfMonth) {
            val date = fmt.format(cal.time)
            if (date.startsWith(monthPrefix)) {
                total += prefs.getLong("${date}_${type}_rx", 0) + prefs.getLong("${date}_${type}_tx", 0)
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return total
    }

    /** Get today's usage summary */
    fun getTodayUsage(): DataUsageEntry = getUsageForDate(todayKey())

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
