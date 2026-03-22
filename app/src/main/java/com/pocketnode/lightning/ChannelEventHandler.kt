package com.pocketnode.lightning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Handles channel lifecycle events: rejection detection, close reason parsing,
 * peer minimum extraction, and persistence. Extracted from LightningService.
 */
class ChannelEventHandler(private val context: Context) {
    companion object {
        private const val TAG = "ChannelEventHandler"
        private const val PREFS_NAME = "channel_closes"
    }

    private val closePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Result of processing a ChannelClosed event.
     */
    data class CloseResult(
        val displayReason: String,
        val isRejection: Boolean,
        val minChannelSats: Long? = null  // parsed peer minimum, if found
    )

    /**
     * Process a channel close reason string into a display-friendly result.
     * Detects pre-funding rejections vs real channel closes.
     */
    fun processCloseReason(reason: String): CloseResult {
        val isRejection = isRejection(reason)
        val displayReason = if (isRejection) {
            val peerMsg = extractPeerMessage(reason)
            "Channel rejected by peer: $peerMsg"
        } else reason

        val minSats = parseMinChannelSats(reason)

        return CloseResult(
            displayReason = displayReason,
            isRejection = isRejection,
            minChannelSats = minSats
        )
    }

    /**
     * Persist channel close/rejection info for history tracking.
     */
    fun saveCloseInfo(channelId: String, result: CloseResult, counterpartyNodeId: String?) {
        try {
            closePrefs.edit()
                .putString("close_${channelId}_reason", result.displayReason)
                .putString("close_${channelId}_type", if (result.isRejection) "rejection" else "close")
                .putLong("close_${channelId}_time", System.currentTimeMillis())
                .putString("close_${channelId}_peer", counterpartyNodeId ?: "")
                .apply()
            Log.i(TAG, "Saved close info for channel $channelId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save close info: ${e.message}")
        }
    }

    /**
     * Get close history for a channel.
     */
    fun getCloseInfo(channelId: String): Map<String, Any?> {
        return mapOf(
            "reason" to closePrefs.getString("close_${channelId}_reason", null),
            "type" to closePrefs.getString("close_${channelId}_type", null),
            "time" to closePrefs.getLong("close_${channelId}_time", 0),
            "peer" to closePrefs.getString("close_${channelId}_peer", null)
        )
    }

    // --- Pure functions (tested independently) ---

    fun isRejection(reason: String): Boolean {
        return reason.contains("CounterpartyForceClosed") || reason.contains("min chan size")
    }

    fun extractPeerMessage(reason: String): String {
        return Regex("""peerMsg=(.+?)\)""").find(reason)?.groupValues?.get(1) ?: reason
    }

    fun parseMinChannelSats(reason: String): Long? {
        val minBtc = Regex("""min chan size of (\d+\.?\d*) BTC""").find(reason)?.groupValues?.get(1)
        if (minBtc != null) {
            return (minBtc.toDouble() * 100_000_000).toLong()
        }

        val minSatDirect = Regex("""min=(\d+)\s*sat""").find(reason)?.groupValues?.get(1)
        if (minSatDirect != null) {
            return minSatDirect.toLong()
        }

        return null
    }
}
