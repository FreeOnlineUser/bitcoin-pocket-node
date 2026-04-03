package com.pocketnode.electrum

import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.*
import org.json.JSONArray

/**
 * Manages Electrum subscriptions (header and scripthash change notifications).
 *
 * Polls bitcoind periodically for new blocks and transaction changes,
 * then generates notifications for subscribed clients.
 *
 * Reference: bwt-dev/bwt SubscriptionManager (Rust, MIT license)
 */
class SubscriptionManager(
    private val rpc: BitcoinRpcClient,
    private val addressIndex: AddressIndex
) {
    companion object {
        private const val TAG = "ElectrumSubs"
        private const val POLL_INTERVAL_MS = 5000L  // 5 seconds, same as BWT default
    }

    sealed class Notification {
        data class NewBlock(val height: Int, val headerHex: String) : Notification()
        data class ScripthashChanged(val scripthash: String, val statusHash: String?) : Notification()
    }

    private val subscribedScripthashes = mutableSetOf<String>()
    private var lastTipHeight = -1
    private var lastStatusHashes = mutableMapOf<String, String?>()
    private var pollJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun subscribeScripthash(scripthash: String) {
        subscribedScripthashes.add(scripthash)
    }

    /**
     * Start polling for changes. Calls the callback with new notifications.
     */
    fun startPolling(onNotifications: (List<Notification>) -> Unit) {
        pollJob?.cancel()
        pollJob = scope.launch {
            // Initialize with current state
            try {
                val info = rpc.call("getblockchaininfo")
                lastTipHeight = info?.optInt("blocks", 0) ?: 0
                lastStatusHashes = addressIndex.getAllStatusHashes().toMutableMap()
            } catch (e: Exception) {
                Log.w(TAG, "Initial state fetch failed: ${e.message}")
            }

            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val notifications = checkForChanges()
                    if (notifications.isNotEmpty()) {
                        onNotifications(notifications)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "Poll error: ${e.message}")
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        subscribedScripthashes.clear()
        lastStatusHashes.clear()
        lastTipHeight = -1
    }

    /**
     * Check for new blocks and scripthash changes.
     */
    private suspend fun checkForChanges(): List<Notification> {
        val notifications = mutableListOf<Notification>()

        // Check for new block
        val info = rpc.call("getblockchaininfo") ?: return notifications
        val currentHeight = info.optInt("blocks", 0)

        if (currentHeight > lastTipHeight && lastTipHeight >= 0) {
            val bestHash = info.optString("bestblockhash", "")
            if (bestHash.isNotEmpty()) {
                val headerResult = rpc.call("getblockheader", JSONArray().apply {
                    put(bestHash)
                    put(false)  // hex
                })
                val headerHex = headerResult?.optString("value", "") ?: ""
                if (headerHex.isNotEmpty()) {
                    notifications.add(Notification.NewBlock(currentHeight, headerHex))
                    Log.d(TAG, "New block: $currentHeight")
                }
            }
            lastTipHeight = currentHeight
        } else if (lastTipHeight < 0) {
            lastTipHeight = currentHeight
        }

        // Only poll wallet RPCs if addresses are actually tracked
        val allScripthashes = addressIndex.getAllTrackedScripthashes()
        if (allScripthashes.isNotEmpty()) {
            // Refresh wallet transactions (catches unconfirmed txs between blocks)
            try {
                addressIndex.refreshOnNewBlock()
            } catch (e: Exception) {
                Log.d(TAG, "Tx refresh failed: ${e.message}")
            }

            // Check for scripthash changes on ALL tracked addresses
            // Push unsolicited notifications so wallets like BlueWallet
            // (which don't explicitly subscribe) still get notified
            for (scripthash in allScripthashes) {
                val newHash = addressIndex.getStatusHash(scripthash)
                val oldHash = lastStatusHashes[scripthash]
                if (newHash != oldHash) {
                    notifications.add(Notification.ScripthashChanged(scripthash, newHash))
                    lastStatusHashes[scripthash] = newHash
                    Log.i(TAG, "Scripthash changed: ${scripthash.take(16)}... (unsolicited notify)")
                }
            }
        }

        return notifications
    }
}
