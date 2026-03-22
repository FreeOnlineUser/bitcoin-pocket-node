package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Probes .onion Lightning nodes to discover minimum channel sizes.
 * Connects to each node, attempts a channel open, records the rejection,
 * disconnects, and moves to the next. Rate-limited to avoid being flagged.
 *
 * Results are saved to SharedPreferences (peer_channel_limits) and shared
 * via phone-to-phone peer limit sharing.
 */
class ChannelProbe(private val context: Context) {

    companion object {
        private const val TAG = "ChannelProbe"
        private const val PROBE_AMOUNT_SATS = 100_000L
        private const val DELAY_BETWEEN_PROBES_MS = 45_000L // 45 seconds between attempts
    }

    data class ProbeState(
        val running: Boolean = false,
        val totalNodes: Int = 0,
        val probed: Int = 0,
        val accepted: Int = 0,
        val rejected: Int = 0,
        val unreachable: Int = 0,
        val currentNode: String = "",
        val results: List<ProbeResult> = emptyList()
    )

    data class ProbeResult(
        val nodeId: String,
        val alias: String,
        val outcome: Outcome,
        val minSats: Long? = null,
        val message: String = ""
    )

    enum class Outcome { ACCEPTED, REJECTED_MIN_SIZE, REJECTED_OTHER, UNREACHABLE, FORCE_CLOSED }

    private val _state = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = _state.asStateFlow()

    private var probeJob: Job? = null

    /**
     * Start probing. Fetches top .onion nodes from mempool.space and
     * attempts channel opens to discover minimums.
     */
    fun start(channelManager: ChannelManager, nodeDirectory: NodeDirectory) {
        if (probeJob?.isActive == true) return

        probeJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                _state.value = ProbeState(running = true)
                Log.i(TAG, "Starting channel probe scan")

                // Fetch top nodes by connectivity (most channels = most likely to be online)
                val topNodes = nodeDirectory.getTopNodes(100) + nodeDirectory.getTopByCapacity(100)
                val onionNodes = topNodes
                    .filter { it.hasOnion }
                    .distinctBy { it.publicKey }
                    .filter { !hasExistingMinimum(it.publicKey) }

                Log.i(TAG, "Found ${onionNodes.size} .onion nodes to probe (${topNodes.size} total fetched)")
                _state.value = _state.value.copy(totalNodes = onionNodes.size)

                val results = mutableListOf<ProbeResult>()

                for ((index, node) in onionNodes.withIndex()) {
                    if (!isActive) break

                    val address = node.address ?: continue
                    _state.value = _state.value.copy(
                        currentNode = node.alias.ifEmpty { node.publicKey.take(16) },
                        probed = index
                    )

                    Log.i(TAG, "Probing ${index + 1}/${onionNodes.size}: ${node.alias} (${node.publicKey.take(16)})")

                    val result = probeNode(channelManager, node, address)
                    results.add(result)

                    _state.value = _state.value.copy(
                        probed = index + 1,
                        accepted = results.count { it.outcome == Outcome.ACCEPTED },
                        rejected = results.count { it.outcome == Outcome.REJECTED_MIN_SIZE || it.outcome == Outcome.REJECTED_OTHER },
                        unreachable = results.count { it.outcome == Outcome.UNREACHABLE },
                        results = results.toList()
                    )

                    // Rate limit
                    if (isActive && index < onionNodes.size - 1) {
                        delay(DELAY_BETWEEN_PROBES_MS)
                    }
                }

                Log.i(TAG, "Probe complete: ${results.size} nodes probed, " +
                    "${results.count { it.outcome == Outcome.ACCEPTED }} accepted, " +
                    "${results.count { it.outcome == Outcome.REJECTED_MIN_SIZE }} rejected with min size")

                _state.value = _state.value.copy(
                    running = false,
                    currentNode = "",
                    results = results
                )

            } catch (e: Exception) {
                Log.e(TAG, "Probe scan failed: ${e.message}", e)
                _state.value = _state.value.copy(running = false, currentNode = "Error: ${e.message}")
            }
        }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        _state.value = _state.value.copy(running = false, currentNode = "Stopped")
        Log.i(TAG, "Probe scan stopped by user")
    }

    private fun probeNode(
        channelManager: ChannelManager,
        node: NodeDirectory.LightningNode,
        address: String
    ): ProbeResult {
        // Step 1: Try to connect
        val connectResult = channelManager.connectPeer(node.publicKey, address)
        if (connectResult.isFailure) {
            Log.d(TAG, "  Unreachable: ${connectResult.exceptionOrNull()?.message}")
            return ProbeResult(node.publicKey, node.alias, Outcome.UNREACHABLE,
                message = connectResult.exceptionOrNull()?.message ?: "Connection failed")
        }

        // Step 2: Attempt channel open (will be rejected or accepted)
        val openResult = channelManager.openChannel(node.publicKey, address, PROBE_AMOUNT_SATS)

        // Step 3: Check what happened
        val lastError = LightningService.stateFlow.value.lastChannelError ?: ""
        val handler = ChannelEventHandler(context)
        val closeResult = handler.processCloseReason(lastError)

        // Step 4: Try to disconnect cleanly
        try {
            channelManager.node?.disconnect(node.publicKey)
        } catch (_: Exception) {}

        return when {
            openResult.isSuccess && lastError.isEmpty() -> {
                // Channel was accepted! Close it immediately (we don't actually want it)
                Log.i(TAG, "  ACCEPTED by ${node.alias}")
                try {
                    val channels = channelManager.node?.listChannels() ?: emptyList()
                    val newChannel = channels.find {
                        it.counterpartyNodeId == node.publicKey
                    }
                    if (newChannel != null) {
                        channelManager.node?.closeChannel(newChannel.userChannelId, node.publicKey)
                        Log.i(TAG, "  Closed probe channel")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "  Failed to close probe channel: ${e.message}")
                }
                ProbeResult(node.publicKey, node.alias, Outcome.ACCEPTED, message = "Accepts ${PROBE_AMOUNT_SATS / 1000}k sats")
            }
            closeResult.minChannelSats != null -> {
                Log.i(TAG, "  REJECTED: min ${closeResult.minChannelSats} sats")
                ProbeResult(node.publicKey, node.alias, Outcome.REJECTED_MIN_SIZE,
                    minSats = closeResult.minChannelSats, message = closeResult.displayReason)
            }
            closeResult.isRejection -> {
                Log.i(TAG, "  REJECTED: ${closeResult.displayReason}")
                ProbeResult(node.publicKey, node.alias, Outcome.REJECTED_OTHER,
                    message = closeResult.displayReason)
            }
            openResult.isFailure -> {
                val msg = openResult.exceptionOrNull()?.message ?: "Unknown"
                Log.i(TAG, "  FAILED: $msg")
                ProbeResult(node.publicKey, node.alias, Outcome.REJECTED_OTHER, message = msg)
            }
            else -> {
                ProbeResult(node.publicKey, node.alias, Outcome.REJECTED_OTHER, message = lastError)
            }
        }
    }

    /**
     * Check if we already know the minimum for this node.
     */
    private fun hasExistingMinimum(nodeId: String): Boolean {
        val prefs = context.getSharedPreferences("peer_channel_limits", Context.MODE_PRIVATE)
        return prefs.contains("min_${nodeId}") || prefs.contains("floor_${nodeId}")
    }
}
