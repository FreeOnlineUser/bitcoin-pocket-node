package com.pocketnode.lightning

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import org.json.JSONArray
import org.lightningdevkit.ldknode.*

/**
 * Manages on-chain wallet operations: deposit addresses, sends, and address tracking.
 * Extracted from LightningService.
 */
class OnchainWallet(private val context: Context) {

    companion object {
        private const val TAG = "OnchainWallet"
    }

    /** Node reference, set by LightningService after start */
    @Volatile var node: Node? = null

    /** RPC client for scantxoutset address checking */
    @Volatile var rpcClient: BitcoinRpcClient? = null

    private val depositAddressPrefs: SharedPreferences =
        context.getSharedPreferences("deposit_address", MODE_PRIVATE)

    private var cachedDepositAddress: String? = null

    init {
        cachedDepositAddress = depositAddressPrefs.getString("current_address", null)
    }

    // ── Deposit Address ──────────────────────────────────────────────

    fun getOnchainAddress(): Result<String> {
        if (cachedDepositAddress == null) {
            cachedDepositAddress = depositAddressPrefs.getString("current_address", null)
        }

        val cached = cachedDepositAddress
        if (cached != null) {
            val used = isAddressUsed(cached)
            if (!used) return Result.success(cached)
            Log.i(TAG, "Deposit address used, rotating: $cached")
        }

        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            var addr: String
            var attempts = 0
            do {
                addr = n.onchainPayment().newAddress()
                attempts++
                if (attempts > 20) break
            } while (isAddressUsed(addr))
            cachedDepositAddress = addr
            depositAddressPrefs.edit()
                .putString("current_address", addr)
                .apply()
            Log.i(TAG, "New deposit address: $addr (after $attempts attempts)")
            Result.success(addr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address", e)
            Result.failure(e)
        }
    }

    fun markDepositAddressUsed(address: String) {
        val usedSet = depositAddressPrefs.getStringSet("used_addresses", emptySet())?.toMutableSet() ?: mutableSetOf()
        usedSet.add(address)
        depositAddressPrefs.edit().putStringSet("used_addresses", usedSet).apply()
        Log.i(TAG, "Marked deposit address as used: $address")
    }

    /**
     * Check if address was ever used:
     * 1. Local tracking set (fast)
     * 2. On-chain via scantxoutset (catches usage after seed restore)
     */
    fun isAddressUsed(address: String): Boolean {
        val usedSet = depositAddressPrefs.getStringSet("used_addresses", emptySet()) ?: emptySet()
        if (address in usedSet) return true

        val rpc = rpcClient ?: return false
        try {
            val params = JSONArray().apply {
                put("start")
                put(JSONArray().apply { put("addr($address)") })
            }
            val result = rpc.callSync("scantxoutset", params, readTimeoutMs = 5_000)
            val resultObj = result?.optJSONObject("result")
            if (resultObj != null) {
                val totalAmount = resultObj.optDouble("total_amount", 0.0)
                val unspents = resultObj.optJSONArray("unspents")
                if (totalAmount > 0 || (unspents != null && unspents.length() > 0)) {
                    markDepositAddressUsed(address)
                    Log.i(TAG, "Address $address has on-chain UTXOs, marking as used")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scantxoutset check failed for address: ${e.message}")
        }
        return false
    }

    /** Clear cached deposit address (e.g. on seed restore) */
    fun clearDepositAddress() {
        cachedDepositAddress = null
        depositAddressPrefs.edit().clear().apply()
    }

    // ── Send ─────────────────────────────────────────────────────────

    fun sendOnchain(address: String, amountSats: Long, feeRate: FeeRate? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val rate = feeRate ?: FeeRate.fromSatPerVbUnchecked(4u.toULong())
            Result.success(n.onchainPayment().sendToAddress(address, amountSats.toULong(), rate))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send on-chain", e)
            Result.failure(e)
        }
    }

    fun sendAllOnchain(address: String, feeRate: FeeRate? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val rate = feeRate ?: FeeRate.fromSatPerVbUnchecked(4u.toULong())
            Result.success(n.onchainPayment().sendAllToAddress(address, false, rate))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send all on-chain", e)
            Result.failure(e)
        }
    }
}
