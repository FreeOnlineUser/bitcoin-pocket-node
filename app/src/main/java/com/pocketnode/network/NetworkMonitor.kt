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
 * Monitors network connectivity and tracks data usage per network type.
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(NetworkState.OFFLINE)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("network_data_usage", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Tracking baseline for delta calculation
    private var lastRxBytes: Long = TrafficStats.getTotalRxBytes()
    private var lastTxBytes: Long = TrafficStats.getTotalTxBytes()
    private var lastNetworkState: NetworkState = NetworkState.OFFLINE

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
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

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
            // VPN/Ethernet/other transports — treat as WiFi (metered check below)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkState.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.WIFI
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.WIFI
            else -> NetworkState.OFFLINE
        }
    }

    private fun sampleDataUsage() {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()

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
