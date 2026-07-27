package com.pocketnode.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wallet payments detected by SPV in deferred blocks, ahead of bitcoind's
 * full validation. App-level UX state only: bitcoind and the wallet stack
 * pick the payments up normally when blocks arrive on WiFi.
 */
object SpvTracker {
    private const val TAG = "SpvTracker"
    private const val PREFS = "spv_tracker"
    private const val KEY_PAYMENTS = "payments"
    private const val KEY_LAST_SCANNED = "last_scanned_height"

    data class SpvPayment(
        val txid: String,
        val vout: Int,
        val valueSats: Long,
        val address: String,
        val height: Long,
        val blockHash: String,
        val blockTime: Long
    )

    private val _paymentsFlow = MutableStateFlow<List<SpvPayment>>(emptyList())
    val paymentsFlow: StateFlow<List<SpvPayment>> = _paymentsFlow

    /** Wallet scripts worth watching: the current deposit address plus
     *  recently used ones (repeat payments to old addresses happen). */
    fun watchedScripts(context: Context): List<Pair<ByteArray, String>> {
        val prefs = context.getSharedPreferences("deposit_address", Context.MODE_PRIVATE)
        val addresses = mutableSetOf<String>()
        prefs.getString("current_address", null)?.let { addresses.add(it) }
        prefs.getStringSet("used_addresses", emptySet())?.let { addresses.addAll(it) }
        return addresses.mapNotNull { addr ->
            Bech32.addressToScriptPubKey(addr)?.let { it to addr }
        }
    }

    fun lastScannedHeight(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SCANNED, 0)

    fun setLastScannedHeight(context: Context, height: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SCANNED, height).apply()
    }

    /** Record newly found payments; returns how many were not already known. */
    fun recordPayments(context: Context, payments: List<SpvPayment>): Int {
        val existing = load(context).toMutableList()
        val known = existing.map { "${it.txid}:${it.vout}" }.toHashSet()
        var added = 0
        for (p in payments) {
            if (known.add("${p.txid}:${p.vout}")) {
                existing.add(p)
                added++
                Log.i(TAG, "SPV payment: ${p.valueSats} sats to ${p.address} " +
                    "in block ${p.height} (${p.txid.take(16)}…)")
            }
        }
        if (added > 0) persist(context, existing)
        _paymentsFlow.value = existing
        return added
    }

    /** Load persisted payments into the flow (call once at service start). */
    fun initialize(context: Context) {
        _paymentsFlow.value = load(context)
    }

    /** Payments still ahead of bitcoind's validated tip. */
    fun pendingCount(validatedHeight: Long): Int =
        _paymentsFlow.value.count { it.height > validatedHeight }

    private fun load(context: Context): List<SpvPayment> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAYMENTS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SpvPayment(
                    txid = o.getString("txid"),
                    vout = o.getInt("vout"),
                    valueSats = o.getLong("value"),
                    address = o.getString("address"),
                    height = o.getLong("height"),
                    blockHash = o.getString("blockHash"),
                    blockTime = o.getLong("blockTime")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            emptyList()
        }
    }

    private fun persist(context: Context, payments: List<SpvPayment>) {
        // Keep the persisted list bounded; old entries are long since
        // superseded by real wallet state once blocks were validated.
        val recent = payments.takeLast(50)
        val arr = JSONArray()
        for (p in recent) {
            arr.put(JSONObject().apply {
                put("txid", p.txid)
                put("vout", p.vout)
                put("value", p.valueSats)
                put("address", p.address)
                put("height", p.height)
                put("blockHash", p.blockHash)
                put("blockTime", p.blockTime)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PAYMENTS, arr.toString()).apply()
    }
}
