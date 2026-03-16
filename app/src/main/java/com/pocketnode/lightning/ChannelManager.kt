package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Log
import com.pocketnode.power.PowerModeManager
import org.lightningdevkit.ldknode.*

/**
 * Manages Lightning channel lifecycle: open, close, peer connections,
 * and peer channel limit caching.
 * Extracted from LightningService.
 */
class ChannelManager(private val context: Context) {

    companion object {
        private const val TAG = "ChannelManager"
    }

    /** Node reference, set by LightningService after start */
    @Volatile var node: Node? = null

    /** Callbacks into LightningService */
    var handleEvents: (() -> Unit)? = null
    var updateState: (() -> Unit)? = null
    var savePeerAnchorsCallback: ((String, Boolean) -> Unit)? = null

    // ── Peer Connection ──────────────────────────────────────────────

    fun connectPeer(nodeId: String, address: String): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            Log.i(TAG, "Connecting to peer $nodeId at $address")
            n.connect(nodeId, address, true)
            Log.i(TAG, "Connected to peer. Draining events...")
            for (i in 1..10) {
                Thread.sleep(500)
                handleEvents?.invoke()
            }
            updateState?.invoke()
            Log.i(TAG, "Peer connection complete")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to peer: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Open Channel ─────────────────────────────────────────────────

    fun openChannel(nodeId: String, address: String, amountSats: Long): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))

        val pmm = PowerModeManager.getInstance(context)
        val needsNetworkHold = PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX
        if (needsNetworkHold) {
            val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context)
            if (creds != null) pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
            pmm.holdNetwork()
            Thread.sleep(5_000)
        }

        return try {
            Log.i(TAG, "Connecting to peer $nodeId at $address")
            n.connect(nodeId, address, true)
            val peer = n.listPeers().find { it.nodeId == nodeId }
            val anchors = peer?.supportsAnchors ?: false
            savePeerAnchorsCallback?.invoke(nodeId, anchors)
            Log.i(TAG, "Connected. Peer anchors=$anchors. Opening channel for $amountSats sats")

            val anchorOnly = context.getSharedPreferences("pocketnode_prefs", MODE_PRIVATE)
                .getBoolean("anchor_channels_only", true)
            if (anchorOnly && !anchors) {
                return Result.failure(Exception("Peer doesn't support anchor channels. Disable 'Anchor channels only' in settings to allow legacy channels."))
            }

            val userChannelId = n.openChannel(nodeId, address, amountSats.toULong(), null, null)
            Log.i(TAG, "Channel open initiated: $userChannelId")

            for (i in 1..6) {
                Thread.sleep(500)
                handleEvents?.invoke()
            }

            val channels = n.listChannels()
            val hasNewChannel = channels.isNotEmpty()
            updateState?.invoke()

            val stateSnapshot = LightningService.stateFlow.value
            val reason = stateSnapshot.lastChannelError
            Log.i(TAG, "Post-open: channels=${channels.size} hasNew=$hasNewChannel reason=$reason")

            if (!hasNewChannel) {
                if (reason == null || (!reason.contains("min chan size") && !reason.contains("min="))) {
                    savePeerMinFloor(nodeId, amountSats)
                }
                val msg = if (reason != null) reason else "Peer rejected channel open"
                Result.failure(Exception(msg))
            } else {
                savePeerMinCeiling(nodeId, amountSats)
                Result.success(userChannelId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open channel: ${e.message}", e)
            savePeerMinFloor(nodeId, amountSats)
            Result.failure(e)
        } finally {
            if (needsNetworkHold) {
                Thread.sleep(10_000)
                pmm.releaseNetworkHold()
            }
        }
    }

    // ── Close Channel ────────────────────────────────────────────────

    fun closeChannel(userChannelId: String, counterpartyNodeId: String): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            try {
                val channels = n.listChannels()
                val ch = channels.find { it.userChannelId == userChannelId }
                if (ch != null) {
                    val cfg = ch.config
                    val updated = cfg.copy(forceCloseAvoidanceMaxFeeSatoshis = 1000UL)
                    n.updateChannelConfig(userChannelId, counterpartyNodeId, updated)
                    Log.i(TAG, "Set forceCloseAvoidanceMaxFeeSatoshis=1000 before cooperative close")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not update channel config before close: ${e.message}")
            }
            n.closeChannelWithTargetFeerate(userChannelId, counterpartyNodeId, 253u)
            Log.i(TAG, "Cooperative close initiated with min feerate (253 sat/kw)")
            updateState?.invoke()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close channel", e)
            Result.failure(e)
        }
    }

    fun forceCloseChannel(userChannelId: String, counterpartyNodeId: String, reason: String = "User requested"): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            n.forceCloseChannel(userChannelId, counterpartyNodeId, reason)
            updateState?.invoke()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force-close channel", e)
            Result.failure(e)
        }
    }

    fun listChannels(): List<ChannelDetails> = node?.listChannels() ?: emptyList()

    // ── Peer Channel Limits ──────────────────────────────────────────

    fun savePeerMinChannel(peerId: PublicKey, minSats: Long) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        prefs.edit()
            .putLong(peerId.toString(), minSats)
            .putBoolean("${peerId}_floor", false)
            .apply()
        Log.i(TAG, "Cached peer min channel: ${peerId.toString().take(16)}... = $minSats sats")
    }

    fun getPeerMinChannel(peerId: String): Long {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return prefs.getLong(peerId, -1L)
    }

    fun isPeerMinExact(peerId: String): Boolean {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return !prefs.getBoolean("${peerId}_floor", false)
    }

    fun savePeerMinCeiling(peerId: String, acceptedSats: Long) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        val existing = prefs.getLong(peerId, -1L)
        val isExistingExact = !prefs.getBoolean("${peerId}_floor", false) && !prefs.getBoolean("${peerId}_ceiling", false)
        if (isExistingExact && existing > 0) return
        if (existing < 0 || acceptedSats < existing) {
            prefs.edit()
                .putLong(peerId, acceptedSats)
                .putBoolean("${peerId}_floor", false)
                .putBoolean("${peerId}_ceiling", true)
                .apply()
            Log.i(TAG, "Cached peer min ceiling: ${peerId.take(16)}... <= $acceptedSats sats")
        }
    }

    fun isPeerMinCeiling(peerId: String): Boolean {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return prefs.getBoolean("${peerId}_ceiling", false)
    }

    fun savePeerMinFloor(peerId: String, attemptedSats: Long) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        val existing = prefs.getLong(peerId, -1L)
        val isExistingExact = !prefs.getBoolean("${peerId}_floor", false)
        if (isExistingExact && existing > 0) return
        if (attemptedSats > existing) {
            prefs.edit()
                .putLong(peerId, attemptedSats)
                .putBoolean("${peerId}_floor", true)
                .apply()
            Log.i(TAG, "Cached peer min floor: ${peerId.take(16)}... > $attemptedSats sats")
        }
    }

    // ── Anchor Support ───────────────────────────────────────────────

    fun peerSupportsAnchors(nodeId: String): Boolean? {
        val n = node ?: return null
        val peer = n.listPeers().find { it.nodeId == nodeId }
        return peer?.supportsAnchors
    }

    fun savePeerAnchors(peerId: String, supportsAnchors: Boolean) {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        prefs.edit().putBoolean("${peerId}_anchors", supportsAnchors).apply()
    }

    fun getCachedPeerAnchors(peerId: String): Boolean? {
        val prefs = context.getSharedPreferences("peer_channel_limits", MODE_PRIVATE)
        return if (prefs.contains("${peerId}_anchors")) prefs.getBoolean("${peerId}_anchors", false) else null
    }
}
