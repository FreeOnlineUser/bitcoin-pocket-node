package com.pocketnode.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * Manager for persisting transaction watch list using SharedPreferences
 */
class WatchListManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "mempool_watch_list"
        private const val KEY_WATCHED_TRANSACTIONS = "watched_txs"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Add a transaction to the watch list
     */
    fun addTransaction(txid: String) {
        val watchedTxs = getWatchedTransactions().toMutableSet()
        watchedTxs.add(WatchedTransaction(txid, System.currentTimeMillis()))
        saveWatchedTransactions(watchedTxs)
    }

    /**
     * Remove a transaction from the watch list
     */
    fun removeTransaction(txid: String) {
        val watchedTxs = getWatchedTransactions().filterNot { it.txid == txid }.toSet()
        saveWatchedTransactions(watchedTxs)
    }

    /**
     * Check if a transaction is being watched
     */
    fun isWatched(txid: String): Boolean {
        return getWatchedTransactions().any { it.txid == txid }
    }

    /**
     * Get all watched transactions
     */
    fun getWatchedTransactions(): Set<WatchedTransaction> {
        val jsonString = prefs.getString(KEY_WATCHED_TRANSACTIONS, null) ?: return emptySet()
        return try {
            json.decodeFromString<Set<WatchedTransaction>>(jsonString)
        } catch (e: Exception) {
            // If deserialization fails, return empty set and clear corrupted data
            prefs.edit().remove(KEY_WATCHED_TRANSACTIONS).apply()
            emptySet()
        }
    }

    /**
     * Get list of watched transaction IDs
     */
    fun getWatchedTransactionIds(): List<String> {
        return getWatchedTransactions().map { it.txid }
    }

    /**
     * Clear all watched transactions
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun saveWatchedTransactions(watchedTxs: Set<WatchedTransaction>) {
        val jsonString = json.encodeToString(watchedTxs)
        prefs.edit().putString(KEY_WATCHED_TRANSACTIONS, jsonString).apply()
    }
}

@Serializable
data class WatchedTransaction(
    val txid: String,
    val addedTimestamp: Long
)