package com.pocketnode.lightning

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightning node directory using mempool.space API.
 * Browse and search for Lightning peers to open channels with.
 * No API key required.
 *
 * NOTE: This is the only centralized/third-party dependency in the app.
 * All other features (validation, wallet, payments, price discovery) are
 * fully sovereign. This is used only for peer discovery convenience --
 * users can also enter node pubkeys manually without this API.
 */
object NodeDirectory {

    private const val TAG = "NodeDirectory"
    private const val BASE_URL = "https://mempool.space/api/v1/lightning"

    data class LightningNode(
        val publicKey: String,
        val alias: String,
        val channels: Int,
        val capacity: Long, // sats
        val sockets: String, // comma-separated ip:port addresses
        val country: String,
        val feeRate: Long = -1, // ppm (parts per million), -1 = unknown
        val minChannelSize: Long = -1, // sats, smallest open channel as proxy for minimum
        val supportsAnchors: Boolean? = null // null=unknown, from mempool.space features bit 23
    ) {
        /** First clearnet address, or first .onion, or empty */
        val address: String get() {
            val addrs = sockets.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // Prefer clearnet
            return addrs.firstOrNull { !it.contains(".onion") }
                ?: addrs.firstOrNull()
                ?: ""
        }

        /** Capacity formatted as BTC */
        val capacityBtc: String get() = "%.4f BTC".format(capacity / 100_000_000.0)
    }

    /** Get top nodes by connectivity (most channels) */
    fun getTopNodes(limit: Int = 20): List<LightningNode> {
        return try {
            val json = fetch("$BASE_URL/nodes/rankings/connectivity")
            val arr = JSONArray(json)
            val nodes = mutableListOf<LightningNode>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val obj = arr.getJSONObject(i)
                nodes.add(parseRankingNode(obj))
            }
            nodes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top nodes", e)
            emptyList()
        }
    }

    /** Get top nodes by capacity (largest) */
    fun getTopByCapacity(limit: Int = 20): List<LightningNode> {
        return try {
            val json = fetch("$BASE_URL/nodes/rankings/liquidity")
            val arr = JSONArray(json)
            val nodes = mutableListOf<LightningNode>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val obj = arr.getJSONObject(i)
                nodes.add(parseRankingNode(obj))
            }
            nodes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top by capacity", e)
            emptyList()
        }
    }

    /** Search for a node by alias or public key */
    fun search(query: String): List<LightningNode> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = fetch("$BASE_URL/search?searchText=$encoded")
            val result = JSONObject(json)
            val nodesArr = result.optJSONArray("nodes") ?: return emptyList()
            val nodes = mutableListOf<LightningNode>()
            for (i in 0 until nodesArr.length()) {
                val obj = nodesArr.getJSONObject(i)
                // Search results don't have sockets, need to fetch details
                val pk = obj.getString("public_key")
                val alias = obj.optString("alias", "")
                val channels = obj.optInt("channels", 0)
                val capacity = obj.optLong("capacity", 0)
                nodes.add(LightningNode(pk, alias, channels, capacity, "", ""))
            }
            nodes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search nodes", e)
            emptyList()
        }
    }

    /** Get top nodes sorted by lowest fee rate (samples first channel per node) */
    fun getTopByLowestFee(limit: Int = 20): List<LightningNode> {
        return try {
            // Start with well-connected nodes, then fetch their fees
            val topNodes = getTopNodes(50) // over-fetch to filter
            val nodesWithFees = topNodes.mapNotNull { node ->
                try {
                    val stats = getNodeChannelStats(node.publicKey)
                    if (stats.medianFeeRate >= 0) node.copy(
                        feeRate = stats.medianFeeRate,
                        minChannelSize = stats.minChannelSize
                    ) else null
                } catch (_: Exception) { null }
            }
            nodesWithFees.sortedBy { it.feeRate }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch lowest fee nodes", e)
            emptyList()
        }
    }

    data class ChannelStats(
        val medianFeeRate: Long = -1, // ppm
        val minChannelSize: Long = -1  // sats, smallest open channel
    )

    /** Get fee rate and min channel size by sampling a node's channels */
    fun getNodeChannelStats(publicKey: String): ChannelStats {
        return try {
            // Fetch more channels and sort by capacity ascending to find smallest
            val json = fetch("$BASE_URL/channels?public_key=$publicKey&status=open&index=0&length=20")
            val arr = JSONArray(json)
            if (arr.length() == 0) return ChannelStats()
            val fees = mutableListOf<Long>()
            val sizes = mutableListOf<Long>()
            for (i in 0 until arr.length()) {
                val ch = arr.getJSONObject(i)
                val feeRate = ch.optLong("fee_rate", -1)
                val capacity = ch.optLong("capacity", -1)
                if (feeRate >= 0) fees.add(feeRate)
                if (capacity > 0) sizes.add(capacity)
            }
            ChannelStats(
                medianFeeRate = if (fees.isEmpty()) -1 else fees.sorted()[fees.size / 2],
                minChannelSize = if (sizes.isEmpty()) -1 else sizes.min()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch channel stats for $publicKey", e)
            ChannelStats()
        }
    }

    /** Get median fee rate for a node (convenience wrapper) */
    fun getNodeFeeRate(publicKey: String): Long {
        return getNodeChannelStats(publicKey).medianFeeRate
    }

    /** Get full node details including socket addresses and anchor support */
    fun getNodeDetails(publicKey: String): LightningNode? {
        return try {
            val json = fetch("$BASE_URL/nodes/$publicKey")
            val obj = JSONObject(json)
            val pk = obj.getString("public_key")
            val stats = getNodeChannelStats(pk)
            // Check features for anchors-zero-fee-htlc-tx (bit 23)
            val anchors = parseAnchorSupport(obj.optJSONArray("features"))
            LightningNode(
                publicKey = pk,
                alias = obj.optString("alias", ""),
                channels = obj.optInt("active_channel_count", 0),
                capacity = obj.optLong("capacity", 0),
                sockets = obj.optString("sockets", ""),
                country = obj.optJSONObject("country")?.optString("en", "") ?: "",
                feeRate = stats.medianFeeRate,
                minChannelSize = stats.minChannelSize,
                supportsAnchors = anchors
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch node details", e)
            null
        }
    }

    /** Check if features array contains anchors-zero-fee-htlc-tx (bit 22 or 23) */
    private fun parseAnchorSupport(features: JSONArray?): Boolean? {
        if (features == null) return null
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val bit = f.optInt("bit", -1)
            // Bit 22 (required) or 23 (optional) = anchors-zero-fee-htlc-tx
            if (bit == 22 || bit == 23) return true
        }
        return false
    }

    private fun parseRankingNode(obj: JSONObject): LightningNode {
        return LightningNode(
            publicKey = obj.optString("publicKey", ""),
            alias = obj.optString("alias", ""),
            channels = obj.optInt("channels", 0),
            capacity = obj.optLong("capacity", 0),
            sockets = "", // Rankings don't include sockets
            country = obj.optJSONObject("country")?.optString("en", "") ?: ""
        )
    }

    private fun fetch(urlStr: String): String {
        val conn = com.pocketnode.tor.TorAwareHttp.openConnection(urlStr)
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "BitcoinPocketNode/0.7")
        try {
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
