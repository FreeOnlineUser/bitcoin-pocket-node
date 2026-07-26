package com.pocketnode.power

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.network.HeadersClient
import com.pocketnode.network.NetworkMonitor
import com.pocketnode.network.NetworkState
import com.pocketnode.service.BatteryMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray

/**
 * Manages node power modes: Max, Low, Away.
 *
 * Max: full peers, full dbcache, continuous sync
 * Low: reduced peers, normal sync, default for daily carry
 * Away: periodic burst sync with long idle periods, minimal resource use
 */
class PowerModeManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PowerModeManager"
        private const val PREFS_NAME = "pocketnode_prefs"
        private const val PREF_KEY_POWER_MODE = "power_mode"

        // Burst sync intervals
        private const val LOW_BURST_INTERVAL_MS = 15 * 60 * 1000L   // 15 min
        private const val AWAY_BURST_INTERVAL_MS = 60 * 60 * 1000L  // 60 min
        private const val BURST_SYNC_TIMEOUT_MS = 10 * 60 * 1000L   // 10 min safety cap per burst
        private const val LDK_GRACE_PERIOD_MS = 15 * 1000L          // 15s extra for LDK after sync

        // Peer counts
        private const val MAX_PEERS = 8
        private const val BURST_PEERS = 3  // Low/Away: fewer peers = less overhead

        // Metered deferral: on cellular, bursts fetch headers only (80 bytes each)
        // and block download waits for WiFi/Max. Past this many blocks behind,
        // sync anyway: the prune window is 2048 blocks and falling out of it
        // means a full re-bootstrap, far worse than the data cost.
        private const val METERED_FORCE_SYNC_BLOCKS = 1440L
        private const val PREF_KEY_PROBE_PEERS = "headers_probe_peers"

        private const val PREF_KEY_AUTO_POWER = "power_mode_auto"
        private const val PREF_KEY_MANUAL_MODE = "power_mode_manual"

        private val _modeFlow = MutableStateFlow(Mode.LOW)
        val modeFlow: StateFlow<Mode> = _modeFlow

        /** True when auto-detection is active (user toggle) */
        private val _autoEnabled = MutableStateFlow(false)
        val autoEnabledFlow: StateFlow<Boolean> = _autoEnabled

        private val _burstStateFlow = MutableStateFlow(BurstState.IDLE)
        val burstStateFlow: StateFlow<BurstState> = _burstStateFlow

        private val _nextBurstFlow = MutableStateFlow(0L)
        /** Epoch millis of next scheduled burst (0 = not scheduled) */
        val nextBurstFlow: StateFlow<Long> = _nextBurstFlow

        /** Callbacks for wallet session tracking (Electrum client connect/disconnect) */
        var onWalletSessionStart: (() -> Unit)? = null
        var onWalletSessionEnd: (() -> Unit)? = null

        /** True while a wallet is connected (persists 10s after disconnect) */
        private val _walletConnectedFlow = MutableStateFlow(false)
        val walletConnectedFlow: StateFlow<Boolean> = _walletConnectedFlow

        /** True while initial block download is in progress — forces Max mode */
        private val _initialSyncHold = MutableStateFlow(false)
        val initialSyncHoldFlow: StateFlow<Boolean> = _initialSyncHold

        fun releaseInitialSyncHold() {
            _initialSyncHold.value = false
        }

        fun resetHold() {
            _initialSyncHold.value = false
        }

        /** Callback to get current LDK block height (set by LightningService) */
        var getLdkHeight: (() -> Long)? = null

        /** Whether a channel open is holding the network active */
        @Volatile var channelHoldingNetwork = false

        private val _channelHoldFlow = MutableStateFlow(false)
        val channelHoldFlow: StateFlow<Boolean> = _channelHoldFlow

        /** Single mutex shared across all PowerModeManager instances */
        val burstMutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var instance: PowerModeManager? = null

        fun getInstance(context: Context): PowerModeManager {
            return instance ?: synchronized(this) {
                instance ?: PowerModeManager(context.applicationContext).also { instance = it }
            }
        }

        /** @deprecated Use getInstance(context) instead */
        @Suppress("unused")
        @JvmStatic
        fun create(context: Context): PowerModeManager = getInstance(context)
    }

    enum class Mode(val label: String, val emoji: String, val notificationLabel: String) {
        MAX("Max", "⚡", "Max Data Mode"),
        LOW("Low", "🔋", "Low Data Mode"),
        AWAY("Away", "🚶", "Away Mode");

        companion object {
            fun fromString(s: String): Mode = try { valueOf(s) } catch (_: Exception) { LOW }
        }
    }

    enum class BurstState {
        IDLE,       // Between bursts (network may be off)
        SYNCING,    // Burst active, syncing blocks
        WAITING     // Waiting for next burst
    }

    private var burstJob: Job? = null
    private var autoDetectJob: Job? = null
    private var rpc: BitcoinRpcClient? = null
    private var activeScope: CoroutineScope? = null
    private var walletHoldingNetwork = false
    // channelHoldingNetwork is on companion — see below
    private var walletIndicatorJob: Job? = null
    private var networkHoldCount = 0
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        reloadFromPrefs()
    }

    /** Re-read mode and auto-detect state from SharedPreferences. Call on service (re)start. */
    fun reloadFromPrefs() {
        _modeFlow.value = Mode.fromString(prefs.getString(PREF_KEY_POWER_MODE, "MAX") ?: "MAX")
        _autoEnabled.value = prefs.getBoolean(PREF_KEY_AUTO_POWER, false)
        Log.i(TAG, "Loaded from prefs: mode=${_modeFlow.value} auto=${_autoEnabled.value}")
    }

    private var initialSyncJob: Job? = null

    /** Set the RPC client once bitcoind is running */
    fun setRpc(client: BitcoinRpcClient) {
        rpc = client
        // Seed the headers probe with the live peers captured last session
        prefs.getString(PREF_KEY_PROBE_PEERS, null)
            ?.split(",")
            ?.let { HeadersClient.rememberLivePeers(it) }
        // If burst sync should be running but wasn't started (rpc was null during applyMode),
        // kick it off now. This ensures burst sync always starts even if setRpc is called
        // after setMode.
        val mode = _modeFlow.value
        if (mode != Mode.MAX && burstJob?.isActive != true && !_initialSyncHold.value) {
            val scope = activeScope ?: return
            // Check if we're in IBD before starting burst cycling.
            // startInitialSyncHold() may not have been called yet at this point.
            scope.launch(Dispatchers.IO) {
                try {
                    val info = client.getBlockchainInfoSync()
                    val ibd = info?.optBoolean("initialblockdownload", false) ?: false
                    if (ibd) {
                        Log.i(TAG, "setRpc: IBD in progress, skipping burst cycle start")
                        return@launch
                    }
                } catch (_: Exception) {}
                Log.i(TAG, "setRpc: starting burst cycle for $mode (was not running)")
                applyMode(mode)
            }
        }
    }

    /**
     * Keep network active during initial block download without changing the displayed mode.
     * Suspends burst cycling until IBD completes, then resumes normal mode behavior.
     */
    fun startInitialSyncHold(scope: CoroutineScope, rpcClient: BitcoinRpcClient) {
        if (_initialSyncHold.value) return
        _initialSyncHold.value = true

        // Pause burst cycling and keep network on for fast sync
        burstJob?.cancel()
        burstJob = null
        Log.i(TAG, "IBD detected: keeping network active (mode stays ${_modeFlow.value})")
        scope.launch(Dispatchers.IO) {
            setNetworkActive(rpcClient, true)
        }

        initialSyncJob?.cancel()
        initialSyncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000)
                try {
                    val info = rpcClient.call("getblockchaininfo") ?: continue
                    val ibd = info.optBoolean("initialblockdownload", true)
                    if (!ibd) {
                        _initialSyncHold.value = false
                        initialSyncJob = null
                        Log.i(TAG, "IBD complete, resuming ${_modeFlow.value} mode behavior")
                        // Re-apply current mode to restart burst cycling if needed
                        applyMode(_modeFlow.value)
                        return@launch
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** End the initial sync hold and resume normal mode behavior. */
    fun endInitialSyncHold(scope: CoroutineScope) {
        initialSyncJob?.cancel()
        initialSyncJob = null
        _initialSyncHold.value = false
        Log.i(TAG, "IBD hold ended, resuming ${_modeFlow.value} mode behavior")
        scope.launch(Dispatchers.IO) {
            applyMode(_modeFlow.value)
        }
    }

    /** Switch power mode. Applies immediately. */
    fun setMode(mode: Mode, scope: CoroutineScope, isAuto: Boolean = false) {
        val previous = _modeFlow.value
        _modeFlow.value = mode
        prefs.edit().putString(PREF_KEY_POWER_MODE, mode.name).apply()
        // Save as last manual choice when user picks it directly
        if (!isAuto) {
            prefs.edit().putString(PREF_KEY_MANUAL_MODE, mode.name).apply()
        }
        Log.i(TAG, "Power mode: $previous -> $mode${if (isAuto) " (auto)" else ""}")

        // Store scope for burst cycling
        activeScope = scope

        // Cancel existing burst cycle
        burstJob?.cancel()
        burstJob = null
        // Only clear holds on manual mode changes, not auto
        if (!isAuto) {
            walletHoldingNetwork = false
            channelHoldingNetwork = false
        }
        _burstStateFlow.value = BurstState.IDLE
        _nextBurstFlow.value = 0L

        // Restart bitcoind when crossing Max <-> Low/Away boundary
        // (blocksonly + maxconnections change requires process restart)
        val wasBurst = previous != Mode.MAX
        val isBurst = mode != Mode.MAX
        if (wasBurst != isBurst) {
            scope.launch(Dispatchers.IO) {
                com.pocketnode.service.BitcoindService.restartForBurstMode?.invoke(isBurst)
                applyMode(mode)
            }
        } else {
            scope.launch(Dispatchers.IO) {
                applyMode(mode)
            }
        }
    }

    /** Enable or disable auto power mode detection */
    fun setAutoEnabled(enabled: Boolean, networkStateFlow: StateFlow<NetworkState>,
                       batteryStateFlow: StateFlow<BatteryMonitor.BatteryState>,
                       scope: CoroutineScope) {
        _autoEnabled.value = enabled
        prefs.edit().putBoolean(PREF_KEY_AUTO_POWER, enabled).apply()
        Log.i(TAG, "Auto power mode: $enabled")

        autoDetectJob?.cancel()
        if (enabled) {
            startAutoDetection(networkStateFlow, batteryStateFlow, scope)
        } else {
            // Revert to last manually-set mode
            val lastManual = Mode.fromString(prefs.getString(PREF_KEY_MANUAL_MODE, "LOW") ?: "LOW")
            Log.i(TAG, "Auto disabled, reverting to manual: $lastManual")
            setMode(lastManual, scope)
        }
    }

    /**
     * Monitors network and battery state, automatically switching power mode:
     * - WiFi + Charging -> Max
     * - WiFi + Battery -> Low
     * - Cellular + Charging -> Low
     * - Cellular + Battery -> Away
     * - Battery below 20% -> Away
     */
    private fun startAutoDetection(networkStateFlow: StateFlow<NetworkState>,
                                   batteryStateFlow: StateFlow<BatteryMonitor.BatteryState>,
                                   scope: CoroutineScope) {
        autoDetectJob = scope.launch {
            var firstEmission = true
            combine(networkStateFlow, batteryStateFlow) { network, battery ->
                suggestMode(network, battery)
            }.collect { suggested ->
                if (firstEmission) {
                    // Don't override saved mode at boot — only react to changes
                    firstEmission = false
                    return@collect
                }
                val current = _modeFlow.value
                if (suggested != current) {
                    // Don't override if something is holding the network active
                    if ((walletHoldingNetwork || channelHoldingNetwork) && suggested != Mode.MAX) {
                        Log.i(TAG, "Auto-detect suggests $suggested but network held, staying $current")
                        return@collect
                    }
                    Log.i(TAG, "Auto-switching: $current -> $suggested")
                    setMode(suggested, scope, isAuto = true)
                }
            }
        }
    }

    private fun suggestMode(network: NetworkState, battery: BatteryMonitor.BatteryState): Mode {
        // Low battery always goes to Away
        if (battery.level < 20 && !battery.isCharging) return Mode.AWAY

        return when (network) {
            NetworkState.WIFI -> if (battery.isCharging) Mode.MAX else Mode.LOW
            NetworkState.CELLULAR -> if (battery.isCharging) Mode.LOW else Mode.AWAY
            NetworkState.OFFLINE -> Mode.AWAY
        }
    }

    /** Start auto-detection if enabled (call after setRpc with available flows) */
    fun startAutoIfEnabled(networkStateFlow: StateFlow<NetworkState>,
                           batteryStateFlow: StateFlow<BatteryMonitor.BatteryState>,
                           scope: CoroutineScope) {
        activeScope = scope
        // Wire up wallet session callbacks
        onWalletSessionStart = { onWalletSessionStarted() }
        onWalletSessionEnd = { onWalletSessionEnded() }
        if (_autoEnabled.value) {
            startAutoDetection(networkStateFlow, batteryStateFlow, scope)
        }
    }

    /** Apply mode settings to bitcoind via RPC */
    private suspend fun applyMode(mode: Mode) {
        val client = rpc ?: return

        when (mode) {
            Mode.MAX -> {
                // Full power: network on, no burst cycling
                setNetworkActive(client, true)
                _burstStateFlow.value = BurstState.IDLE
                _nextBurstFlow.value = 0L
            }
            Mode.LOW -> {
                // Ensure network is off + peers disconnected before starting burst cycle
                // (handles transition from IBD or mode change where old burst was cancelled)
                setNetworkActive(client, false)
                startBurstCycle(LOW_BURST_INTERVAL_MS)
            }
            Mode.AWAY -> {
                setNetworkActive(client, false)
                startBurstCycle(AWAY_BURST_INTERVAL_MS)
            }
        }
    }

    // burstMutex is on companion — see below

    // getLdkHeight is on the companion so it survives PowerModeManager re-creation

    /** Start the burst sync cycle for Low/Away mode */
    private fun startBurstCycle(intervalMs: Long) {
        burstJob?.cancel()
        val scope = activeScope ?: return
        burstJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                doBurst()
                if (!isActive) break

                val nextBurst = System.currentTimeMillis() + intervalMs
                _nextBurstFlow.value = nextBurst
                _burstStateFlow.value = BurstState.WAITING
                Log.i(TAG, "Next burst in ${intervalMs / 60000}min at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(nextBurst))}")

                delay(intervalMs)
            }
        }
    }

    /**
     * Execute one sync burst. Purpose: learn if new blocks exist, download them if so.
     *
     * 1. Enable network
     * 2. Wait for at least one peer connection
     * 3. Wait for headers to advance (learning if new blocks were mined)
     * 4. If new blocks: download them, wait for LDK to catch up
     * 5. Network off
     *
     * Fast when no new blocks (peer connects, headers match blocks, done).
     * Only holds network open as long as needed.
     */
    private suspend fun doBurst() {
        if (!burstMutex.tryLock()) {
            Log.d(TAG, "Burst already running, skipping")
            return
        }
        val client = rpc
        if (client == null) {
            burstMutex.unlock()
            return
        }
        _burstStateFlow.value = BurstState.SYNCING

        // Metered gate: on cellular, learn the tip from an 80-byte-per-header
        // P2P probe instead of enabling bitcoind's network. Block download
        // waits for WiFi/Max unless we risk falling out of the prune window.
        val onMetered = try {
            NetworkMonitor.getInstance(context).networkState.value == NetworkState.CELLULAR
        } catch (_: Exception) { false }
        if (onMetered) {
            try {
                val probe = HeadersClient.probe(client)
                val info = client.getBlockchainInfo()?.takeIf { !it.optBoolean("_rpc_error", false) }
                val blocksNow = info?.optLong("blocks", 0L) ?: 0L
                val headersNow = info?.optLong("headers", 0L) ?: 0L
                val behind = headersNow - blocksNow
                if (probe.success && behind <= METERED_FORCE_SYNC_BLOCKS) {
                    Log.i(TAG, "Burst: metered, headers-only — +${probe.headersFetched} " +
                        "header(s) from ${probe.peer}, $behind block(s) deferred until WiFi/Max")
                    _burstStateFlow.value = BurstState.IDLE
                    burstMutex.unlock()
                    return
                }
                if (!probe.success) {
                    Log.w(TAG, "Burst: headers probe failed (${probe.error}) — full sync fallback")
                } else {
                    Log.w(TAG, "Burst: $behind blocks behind on metered (cap $METERED_FORCE_SYNC_BLOCKS) " +
                        "— syncing blocks now to stay inside the prune window")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _burstStateFlow.value = BurstState.IDLE
                burstMutex.unlock()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Burst: headers probe error (${e.message}) — full sync fallback")
            }
        }

        // Snapshot current state before enabling network
        val preBlocks = try {
            client.getBlockchainInfo()?.optLong("blocks", 0L) ?: 0L
        } catch (_: Exception) { 0L }

        // Snapshot network totals before burst for cost measurement
        val netBefore = try { client.call("getnettotals") } catch (_: Exception) { null }
        val sentBefore = netBefore?.optLong("totalbytessent", 0) ?: 0
        val recvBefore = netBefore?.optLong("totalbytesrecv", 0) ?: 0

        Log.i(TAG, "Burst: starting at block $preBlocks")

        try {
            // 1. Enable network
            setNetworkActive(client, true)

            // 2. Wait for at least one peer (up to 30s — cold start needs longer)
            var hasPeers = false
            val peerStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - peerStart < 30_000) {
                try {
                    val netInfo = client.call("getnetworkinfo")
                    val connections = netInfo?.optInt("connections", 0) ?: 0
                    if (connections > 0) {
                        hasPeers = true
                        break
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                delay(1_000)
            }

            if (!hasPeers) {
                Log.w(TAG, "Burst: no peers connected in 30s, aborting")
                if (_modeFlow.value != Mode.MAX) setNetworkActive(client, false)
                _burstStateFlow.value = BurstState.IDLE
                burstMutex.unlock()
                return
            }

            // 3. Wait for headers to advance (proves peers told us about new blocks)
            //    If headers don't move within 10s of having peers, nothing's new.
            var headers = preBlocks
            val headerWaitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - headerWaitStart < 10_000) {
                val cur = client.getBlockchainInfo()
                headers = cur?.optLong("headers", 0L) ?: 0L
                if (headers > preBlocks) break  // Peers sent new headers
                delay(1_000)
            }

            // 4. Sync blocks if headers advanced
            val blocks = try {
                client.getBlockchainInfo()?.optLong("blocks", 0L) ?: 0L
            } catch (_: Exception) { 0L }
            var hadNewBlocks = false

            if (headers > blocks) {
                // New blocks to download. Wait until fully synced.
                hadNewBlocks = true
                Log.i(TAG, "Burst: downloading blocks $blocks -> $headers")
                val syncStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - syncStart < BURST_SYNC_TIMEOUT_MS) {
                    val cur = client.getBlockchainInfo()
                    val curBlocks = cur?.optLong("blocks", 0L) ?: 0L
                    val curHeaders = cur?.optLong("headers", 0L) ?: 0L
                    if (curBlocks >= curHeaders && curHeaders > 0) {
                        Log.i(TAG, "Burst: bitcoind synced to $curBlocks")
                        break
                    }
                    delay(2_000)
                }
            } else if (blocks > preBlocks) {
                hadNewBlocks = true
                Log.i(TAG, "Burst: blocks already advanced $preBlocks -> $blocks")
            } else {
                Log.i(TAG, "Burst: no new blocks (at $blocks)")
            }

            // 5. If LDK is running, wait for it to catch up
            val finalBlocks = try {
                client.getBlockchainInfo()?.optLong("blocks", 0L) ?: 0L
            } catch (_: Exception) { 0L }

            if (finalBlocks > preBlocks && getLdkHeight != null) {
                val ldkWaitStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - ldkWaitStart < 30_000) {
                    val ldkNow = getLdkHeight?.invoke() ?: 0L
                    if (ldkNow >= finalBlocks) {
                        Log.i(TAG, "Burst: LDK synced to $ldkNow")
                        break
                    }
                    delay(2_000)
                }
            }

            // 6. Grace period: if new blocks arrived, keep network up a bit longer
            //    so LDK can process gossip, pending HTLCs, scoring updates
            if (hadNewBlocks && getLdkHeight != null) {
                Log.i(TAG, "Burst: grace period (${LDK_GRACE_PERIOD_MS / 1000}s) for LDK post-sync")
                delay(LDK_GRACE_PERIOD_MS)
            }

            // Log burst cost (useful for diagnosing which phase uses data)
            try {
                val netAfter = client.call("getnettotals")
                val sentKB = ((netAfter?.optLong("totalbytessent", 0) ?: 0) - sentBefore) / 1024
                val recvKB = ((netAfter?.optLong("totalbytesrecv", 0) ?: 0) - recvBefore) / 1024
                Log.i(TAG, "Burst cost: sent=${sentKB}KB recv=${recvKB}KB newBlocks=$hadNewBlocks")
            } catch (_: Exception) {}

            Log.i(TAG, "Burst: complete (was $preBlocks, now $finalBlocks)")

            // Remember the peers we were actually connected to: they are the
            // only verified-live candidates the next headers probe can get
            // (addrman gossip is mostly dead addresses).
            try {
                val peers = client.call("getpeerinfo")?.optJSONArray("value")
                if (peers != null && peers.length() > 0) {
                    val addrs = mutableListOf<String>()
                    for (i in 0 until peers.length()) {
                        val addr = peers.getJSONObject(i).optString("addr")
                        if (addr.isNotEmpty()) addrs.add(addr)
                    }
                    if (addrs.isNotEmpty()) {
                        HeadersClient.rememberLivePeers(addrs)
                        prefs.edit().putString(PREF_KEY_PROBE_PEERS, addrs.joinToString(",")).apply()
                        Log.d(TAG, "Burst: remembered ${addrs.size} live peer(s) for headers probes")
                    }
                }
            } catch (_: Exception) {}

            // 7. Network off
            if (_modeFlow.value != Mode.MAX) {
                setNetworkActive(client, false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "Burst cancelled (mode change)")
            burstMutex.unlock()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Burst error: ${e.message}")
            try { setNetworkActive(client, false) } catch (_: Exception) {}
        }

        _burstStateFlow.value = BurstState.IDLE
        burstMutex.unlock()
    }

    /** Trigger an immediate burst sync (e.g. when user opens the app or wallet connects) */
    fun triggerBurst(scope: CoroutineScope) {
        val mode = _modeFlow.value
        if (mode == Mode.MAX) return
        if (_burstStateFlow.value == BurstState.SYNCING) return

        val intervalMs = if (mode == Mode.LOW) LOW_BURST_INTERVAL_MS else AWAY_BURST_INTERVAL_MS

        burstJob?.cancel()
        burstJob = null

        scope.launch(Dispatchers.IO) {
            doBurst()
            // Resume normal cycle after triggered burst
            startBurstCycle(intervalMs)
        }
    }

    /**
     * Called when an external wallet (BlueWallet etc.) connects to the Electrum server.
     * Holds the network active so the wallet has peers for fresh data and tx broadcast.
     * Burst cycling is paused until the last wallet disconnects.
     */
    private fun onWalletSessionStarted() {
        val mode = _modeFlow.value
        if (mode == Mode.MAX) return  // Already fully connected
        if (walletHoldingNetwork) return  // Already holding

        walletHoldingNetwork = true
        walletIndicatorJob?.cancel()
        _walletConnectedFlow.value = true
        Log.i(TAG, "Wallet connected — holding network active")

        // Cancel burst cycling, just keep network on
        burstJob?.cancel()
        burstJob = null
        _burstStateFlow.value = BurstState.IDLE
        _nextBurstFlow.value = 0L

        val scope = activeScope ?: return
        scope.launch(Dispatchers.IO) {
            setNetworkActive(rpc ?: return@launch, true)
        }
    }

    /**
     * Called when the last wallet disconnects from the Electrum server.
     * Resumes normal burst cycling for the current power mode.
     */
    private fun onWalletSessionEnded() {
        if (!walletHoldingNetwork) return
        walletHoldingNetwork = false

        val mode = _modeFlow.value
        if (mode == Mode.MAX) return  // Max stays connected anyway

        Log.i(TAG, "All wallets disconnected — resuming burst cycle")

        // Keep indicator visible for 10s after disconnect
        val scope = activeScope ?: return
        walletIndicatorJob?.cancel()
        walletIndicatorJob = scope.launch {
            delay(10_000)
            _walletConnectedFlow.value = false
        }

        scope.launch(Dispatchers.IO) {
            applyMode(mode)
        }
    }

    /**
     * Hold network active temporarily (e.g. for channel opens).
     * Pauses burst cycling. Call releaseNetworkHold() when done.
     */
    /**
     * Hold network active. Reference-counted: multiple callers can hold,
     * network stays active until ALL release.
     */
    fun holdNetwork() {
        val mode = _modeFlow.value
        if (mode == Mode.MAX) return  // Already connected

        synchronized(this) {
            networkHoldCount++
            Log.i(TAG, "Network hold acquired (count=$networkHoldCount)")
            if (networkHoldCount == 1) {
                // First hold — activate network
                channelHoldingNetwork = true
                _channelHoldFlow.value = true

                burstJob?.cancel()
                burstJob = null
                _burstStateFlow.value = BurstState.IDLE
                _nextBurstFlow.value = 0L

                val scope = activeScope ?: return
                scope.launch(Dispatchers.IO) {
                    setNetworkActive(rpc ?: return@launch, true)
                }
            }
        }
    }

    /** Release one network hold. Network released only when all holds are released. */
    fun releaseNetworkHold() {
        synchronized(this) {
            if (networkHoldCount <= 0) return
            networkHoldCount--
            Log.i(TAG, "Network hold released (count=$networkHoldCount)")
            if (networkHoldCount > 0) return  // Other holders still active

            channelHoldingNetwork = false
            _channelHoldFlow.value = false
        }

        val mode = _modeFlow.value
        if (mode == Mode.MAX) return

        Log.i(TAG, "All network holds released — resuming burst cycle")

        val scope = activeScope ?: return
        scope.launch(Dispatchers.IO) {
            applyMode(mode)
        }
    }

    /** Current network hold count (for testing) */
    fun getNetworkHoldCount(): Int = synchronized(this) { networkHoldCount }

    /** Clean up when service stops */
    fun stop() {
        burstJob?.cancel()
        burstJob = null
        autoDetectJob?.cancel()
        autoDetectJob = null
        _burstStateFlow.value = BurstState.IDLE
        _nextBurstFlow.value = 0L
    }

    private suspend fun setNetworkActive(client: BitcoinRpcClient, active: Boolean) {
        // Never disable network during initial block download
        if (!active && _initialSyncHold.value) {
            Log.i(TAG, "setNetworkActive(false) blocked: IBD in progress")
            return
        }
        try {
            val params = JSONArray().apply { put(active) }
            client.call("setnetworkactive", params)
            Log.d(TAG, "setnetworkactive($active)")
            // Disconnect all peers when going offline to stop traffic immediately
            if (!active) {
                disconnectAllPeers(client)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setnetworkactive failed: ${e.message}")
        }
    }

    /** Disconnect all connected peers to stop traffic immediately */
    private suspend fun disconnectAllPeers(client: BitcoinRpcClient) {
        try {
            val peers = client.call("getpeerinfo") ?: return
            // call() wraps array results as {"value": [...]}
            val peerArray = peers.optJSONArray("value") ?: return
            var disconnected = 0
            for (i in 0 until peerArray.length()) {
                val peer = peerArray.getJSONObject(i)
                val addr = peer.optString("addr", "")
                if (addr.isNotEmpty()) {
                    try {
                        val params = JSONArray().apply { put(addr) }
                        client.call("disconnectnode", params)
                        disconnected++
                    } catch (_: Exception) {}
                }
            }
            if (disconnected > 0) {
                Log.i(TAG, "Disconnected $disconnected peers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "disconnectAllPeers failed: ${e.message}")
        }
    }
}
