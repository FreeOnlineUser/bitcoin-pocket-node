package com.pocketnode.electrum

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Recovers transaction history for addresses where local data is incomplete.
 * Queries mempool.space API to find historical transactions that our pruned
 * node can't see, then persists them so they're never lost.
 *
 * TODO: Route through embedded Tor for better privacy. Currently uses clearnet
 * HTTPS. The addresses are already public on the blockchain, but linking them
 * to an IP is a privacy leak.
 */
object HistoryRecovery {

    private const val TAG = "HistoryRecovery"
    private const val DEFAULT_API_BASE = "https://mempool.space/api"
    var apiBase: String = DEFAULT_API_BASE

    /**
     * Recover missing transaction history for a set of addresses.
     * Returns a map of address -> list of (txid, height) pairs discovered.
     *
     * Only queries addresses that have UTXOs (from scantxoutset) but
     * fewer history entries than expected.
     */
    // Active connection that can be interrupted by cancel
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun interruptConnection() {
        try { activeConnection?.disconnect() } catch (_: Exception) {}
    }

    suspend fun recoverHistory(
        addresses: Set<String>,
        knownTxCount: Map<String, Int>,  // address -> known tx count
        onProgress: ((scanned: Int, total: Int, addr: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Map<String, List<Pair<String, Int>>> = withContext(Dispatchers.IO) {
        val recovered = mutableMapOf<String, List<Pair<String, Int>>>()
        val total = addresses.size
        var scanned = 0

        var backoffMs = 0L
        var consecutive429s = 0
        var consecutiveEmpty = 0
        val addrList = addresses.toList()
        var index = 0

        while (index < addrList.size) {
            if (isCancelled?.invoke() == true) {
                Log.i(TAG, "Recovery cancelled at $scanned/$total")
                break
            }

            val addr = addrList[index]
            onProgress?.invoke(scanned, total, addr)

            if (backoffMs > 200) {
                onProgress?.invoke(scanned, total, "$addr\n⏳ Rate limited, retrying in ${backoffMs/1000}s...")
                val chunks = (backoffMs / 200).toInt()
                for (i in 0 until chunks) {
                    if (isCancelled?.invoke() == true) break
                    Thread.sleep(200)
                }
                if (isCancelled?.invoke() == true) break
            } else if (backoffMs > 0) {
                Thread.sleep(backoffMs)
            }

            var success = false
            var txs: List<Pair<String, Int>>? = null
            try {
                txs = fetchAddressTransactions(addr)
                if (txs == null) {
                    consecutive429s++
                    backoffMs = minOf(2000L * (1L shl minOf(consecutive429s - 1, 4)), 30000L)
                    Log.w(TAG, "Rate limited on $addr, backoff ${backoffMs}ms")
                } else {
                    consecutive429s = 0
                    backoffMs = 200
                    success = true
                    if (txs.isNotEmpty()) {
                        val known = knownTxCount[addr] ?: 0
                        if (txs.size > known) {
                            Log.i(TAG, "Recovered ${txs.size - known} missing txs for $addr")
                        }
                        recovered[addr] = txs
                    }
                }
            } catch (e: Exception) {
                if (isCancelled?.invoke() == true) break
                consecutive429s++
                backoffMs = minOf(2000L * (1L shl minOf(consecutive429s - 1, 4)), 30000L)
            }

            if (success) {
                index++
                scanned++
                consecutiveEmpty++
                if (txs != null && txs.isNotEmpty()) consecutiveEmpty = 0
                // Gap limit: stop if 20 consecutive addresses have no history
                if (consecutiveEmpty >= 20) {
                    Log.i(TAG, "Gap limit reached at $scanned/$total, stopping scan")
                    break
                }
            }
            // On failure, retry same address after backoff
        }

        recovered
    }

    /**
     * Fetch all transactions for an address from mempool.space.
     * Paginates automatically (API returns 25 per page).
     * Returns list of (txid, block_height) pairs. Height=0 means unconfirmed.
     */
    private fun fetchAddressTransactions(address: String): List<Pair<String, Int>>? {
        val results = mutableListOf<Pair<String, Int>>()
        var lastTxid: String? = null
        var firstPage = true

        while (true) {
            val url = if (lastTxid == null) {
                "$apiBase/address/$address/txs"
            } else {
                "$apiBase/address/$address/txs/chain/$lastTxid"
            }

            val json = httpGet(url)
            if (json == null) {
                // First page failed = rate limited, return null
                if (firstPage) return null
                break
            }
            firstPage = false
            val arr = JSONArray(json)
            if (arr.length() == 0) break

            for (i in 0 until arr.length()) {
                val tx = arr.getJSONObject(i)
                val txid = tx.getString("txid")
                val status = tx.optJSONObject("status")
                val height = if (status?.optBoolean("confirmed", false) == true) {
                    status.optInt("block_height", 0)
                } else 0
                results.add(Pair(txid, height))
            }

            // API returns 25 per page; if less, we're done
            if (arr.length() < 25) break
            lastTxid = arr.getJSONObject(arr.length() - 1).getString("txid")

            Log.i(TAG, "Paginating $address: ${results.size} txs so far")
        }

        return results
    }

    /**
     * Public HTTP GET for use by AddressIndex tx hex caching.
     */
    fun httpGetPublic(url: String): String? = httpGet(url)

    private fun httpGet(url: String): String? {
        val conn = com.pocketnode.tor.TorAwareHttp.openConnection(url)
        activeConnection = conn
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "PocketNode/1.0")
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else if (conn.responseCode == 429) {
                null
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            activeConnection = null
            conn.disconnect()
        }
    }
}
