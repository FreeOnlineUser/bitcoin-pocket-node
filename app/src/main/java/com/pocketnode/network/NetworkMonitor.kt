package com.pocketnode.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
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
        private const val KEY_RESET_AT = "usage_reset_at"
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
    private val statsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    // Apps can't query VPN traffic from NetworkStatsManager, so while a VPN is the active
    // network we charge bitcoind's own P2P byte counters (getnettotals) instead. That is
    // nearly all of our traffic and, unlike TrafficStats, excludes local RPC.
    private var lastP2pRecv = -1L
    private var lastP2pSent = -1L
    // Finished days don't change; cache them so the 30s refresh only queries today.
    private val pastDays = java.util.concurrent.ConcurrentHashMap<String, DataUsageEntry>()
    private var lastNetworkState: NetworkState = NetworkState.OFFLINE

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    init {
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

    /**
     * Bytes this app sent and received on one network type between [startMs] and [endMs],
     * from Android's per-interface accounting. Loopback never appears here, which matters:
     * bitcoind runs under our uid, so TrafficStats counts every local RPC call twice.
     * Apps may always query their own uid; returns null if the type can't be queried.
     */
    private fun uidBytes(networkType: Int, startMs: Long, endMs: Long): Pair<Long, Long>? {
        return try {
            val stats = statsManager.querySummary(networkType, null, startMs, endMs) ?: return null
            try {
                var rx = 0L
                var tx = 0L
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    if (bucket.uid == uid) {
                        rx += bucket.rxBytes
                        tx += bucket.txBytes
                    }
                }
                rx to tx
            } finally {
                stats.close()
            }
        } catch (e: Exception) {
            android.util.Log.w("DataUsage", "netstats query failed for type $networkType: ${e.message}")
            null
        }
    }

    private fun sampleDataUsage() {
        val today = todayKey()
        val state = lastNetworkState
        val onVpn = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        p2pTotals()?.let { (recv, sent) ->
            val restarted = recv < lastP2pRecv || sent < lastP2pSent  // bitcoind restarted
            if (onVpn && lastP2pRecv >= 0 && !restarted && state != NetworkState.OFFLINE) {
                val key = if (state == NetworkState.CELLULAR) "vpncell" else "vpnwifi"
                prefs.edit()
                    .putLong("${today}_${key}_rx", prefs.getLong("${today}_${key}_rx", 0) + (recv - lastP2pRecv))
                    .putLong("${today}_${key}_tx", prefs.getLong("${today}_${key}_tx", 0) + (sent - lastP2pSent))
                    .apply()
            }
            lastP2pRecv = recv
            lastP2pSent = sent
        }

        refreshUsageState()

        val todayUsage = _usageState.value.today
        val totalToday = todayUsage.wifiRx + todayUsage.wifiTx + todayUsage.cellularRx + todayUsage.cellularTx
        val powerMode = try { com.pocketnode.power.PowerModeManager.modeFlow.value } catch (_: Exception) { "unknown" }
        android.util.Log.d("DataUsage",
            "today=${formatDelta(totalToday)} " +
            "wifi=↓${formatDelta(todayUsage.wifiRx)}/↑${formatDelta(todayUsage.wifiTx)} " +
            "cell=↓${formatDelta(todayUsage.cellularRx)}/↑${formatDelta(todayUsage.cellularTx)} " +
            "net=$state${if (onVpn) "+vpn" else ""} power=$powerMode")
    }

    /** bitcoind's cumulative P2P bytes (received, sent) since it started, or null. */
    private fun p2pTotals(): Pair<Long, Long>? {
        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context) ?: return null
        val res = com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second)
            .callSync("getnettotals", connectTimeoutMs = 2_000, readTimeoutMs = 5_000) ?: return null
        if (res.has("_rpc_error")) return null
        return res.optLong("totalbytesrecv", -1).takeIf { it >= 0 }?.let { it to res.optLong("totalbytessent", 0) }
    }

    /** Local-midnight bounds of a yyyy-MM-dd day. */
    private fun dayRange(date: String): Pair<Long, Long> {
        val start = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!.time
        return start to start + 24 * 60 * 60 * 1000L
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
        if (date != todayKey()) {
            return pastDays.getOrPut(date) { queryUsageForDate(date) }
        }
        return queryUsageForDate(date)
    }

    private fun queryUsageForDate(date: String): DataUsageEntry {
        val (dayStart, end) = dayRange(date)
        // Android's history can't be erased, so "clear" is a point we count from.
        val start = maxOf(dayStart, prefs.getLong(KEY_RESET_AT, 0))
        if (start >= end) return DataUsageEntry(date = date)
        val wifi = uidBytes(ConnectivityManager.TYPE_WIFI, start, end) ?: (0L to 0L)
        val eth = uidBytes(ConnectivityManager.TYPE_ETHERNET, start, end) ?: (0L to 0L)
        val mobile = uidBytes(ConnectivityManager.TYPE_MOBILE, start, end) ?: (0L to 0L)
        return DataUsageEntry(
            date = date,
            wifiRx = wifi.first + eth.first + prefs.getLong("${date}_vpnwifi_rx", 0),
            wifiTx = wifi.second + eth.second + prefs.getLong("${date}_vpnwifi_tx", 0),
            cellularRx = mobile.first + prefs.getLong("${date}_vpncell_rx", 0),
            cellularTx = mobile.second + prefs.getLong("${date}_vpncell_tx", 0)
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
        prefs.edit().clear().putLong(KEY_RESET_AT, System.currentTimeMillis()).apply()
        pastDays.clear()
        refreshUsageState()
        android.util.Log.i("NetworkMonitor", "All data usage history cleared, baseline reset")
    }

    fun getMonthCellularUsage(): Long = getMonthUsage("cell")

    /** Get total WiFi usage for current month */
    fun getMonthWifiUsage(): Long = getMonthUsage("wifi")

    private fun getMonthUsage(type: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val dayOfMonth = cal.get(java.util.Calendar.DAY_OF_MONTH)
        var total = 0L
        for (i in 0 until dayOfMonth) {
            val u = getUsageForDate(fmt.format(cal.time))
            total += if (type == "cell") u.cellularRx + u.cellularTx else u.wifiRx + u.wifiTx
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return total
    }

    /** Get today's usage summary */
    fun getTodayUsage(): DataUsageEntry = getUsageForDate(todayKey())

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
