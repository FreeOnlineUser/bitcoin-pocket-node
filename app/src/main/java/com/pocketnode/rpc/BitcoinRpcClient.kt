package com.pocketnode.rpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal JSON-RPC client for communicating with the local bitcoind instance.
 *
 * All calls go to 127.0.0.1:8332 — this never leaves the device.
 */
class BitcoinRpcClient(
    private val rpcUser: String,
    private val rpcPassword: String,
    private val host: String = "127.0.0.1",
    private val port: Int = 8332
) {
    private val idCounter = AtomicInteger(0)

    /**
     * Make a raw JSON-RPC call. Returns the "result" field, or null on error.
     */
    suspend fun call(method: String, params: Any = JSONArray(), connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 30_000): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$host:$port/")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", basicAuth())
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("jsonrpc", "1.0")
                    put("id", idCounter.incrementAndGet())
                    put("method", method)
                    put("params", params)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                val response = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: java.io.IOException) {
                    // HTTP 500 during warmup — read error stream
                    conn.errorStream?.bufferedReader()?.readText()
                }

                if (response == null) return@withContext null
                val json = JSONObject(response)

                if (json.isNull("error")) {
                    val result = json.get("result")
                    if (result is JSONObject) result else JSONObject().put("value", result)
                } else {
                    // Return error info so callers can detect warmup vs real errors
                    val err = json.optJSONObject("error")
                    if (err != null) {
                        JSONObject().put("_rpc_error", true)
                            .put("code", err.optInt("code"))
                            .put("message", err.optString("message"))
                    } else null
                }
            } catch (e: Exception) {
                android.util.Log.d("BitcoinRpc", "call($method): ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    /** Get blockchain info (block height, chain, sync progress, etc.) */
    suspend fun getBlockchainInfo(): JSONObject? = call("getblockchaininfo")

    /** Get connected peer count */
    suspend fun getPeerCount(): Int {
        return try {
            val result = call("getpeerinfo")
            // getpeerinfo returns an array — we wrapped it, so unwrap
            // Actually, let's do a direct getconnectioncount instead
            val countResult = call("getconnectioncount")
            countResult?.optInt("value", 0) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Load a UTXO snapshot file via the loadtxoutset RPC.
     * This blocks for ~25 minutes on phone hardware.
     * @param snapshotPath Absolute path to the snapshot file on the device.
     * @return Result JSON or null on error.
     */
    suspend fun loadTxOutset(snapshotPath: String): JSONObject? {
        val params = JSONArray().apply { put(snapshotPath) }
        return callLongRunning("loadtxoutset", params, timeoutMs = 3_600_000)
    }

    /**
     * Long-running RPC call with extended timeout (for loadtxoutset, dumptxoutset, etc.)
     */
    suspend fun callLongRunning(method: String, params: Any = JSONArray(), timeoutMs: Int = 3_600_000): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$host:$port/")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", basicAuth())
                conn.connectTimeout = 10_000
                conn.readTimeout = timeoutMs
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("jsonrpc", "1.0")
                    put("id", idCounter.incrementAndGet())
                    put("method", method)
                    put("params", params)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)

                if (json.isNull("error")) {
                    val result = json.get("result")
                    if (result is JSONObject) result else JSONObject().put("value", result)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Stop the bitcoind daemon gracefully via RPC. */
    suspend fun stop(): JSONObject? = call("stop")

    private fun basicAuth(): String {
        val credentials = "$rpcUser:$rpcPassword"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
        return "Basic $encoded"
    }
}
