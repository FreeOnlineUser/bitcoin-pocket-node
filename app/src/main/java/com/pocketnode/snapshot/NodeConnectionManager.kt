package com.pocketnode.snapshot

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
 * Manages RPC connection to a remote Bitcoin node (Umbrel, Start9, etc.)
 * for triggering and monitoring UTXO snapshot generation.
 */
class NodeConnectionManager(
    private val host: String,
    private val port: Int = 8332,
    private val rpcUser: String,
    private val rpcPassword: String
) {
    private val idCounter = AtomicInteger(0)

    data class ConnectionResult(
        val success: Boolean,
        val chain: String? = null,
        val blocks: Int = 0,
        val headers: Int = 0,
        val verificationProgress: Double = 0.0,
        val error: String? = null
    )

    data class DumpResult(
        val success: Boolean,
        val txoutsetHash: String? = null,
        val error: String? = null
    )

    /**
     * Raw RPC call to the remote node. Longer timeouts since remote nodes
     * may be on slow hardware.
     */
    suspend fun rpcCall(
        method: String,
        params: Any = JSONArray(),
        readTimeoutMs: Int = 30_000
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("http://$host:$port/")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty(
            "Authorization",
            "Basic ${Base64.getEncoder().encodeToString("$rpcUser:$rpcPassword".toByteArray())}"
        )
        conn.connectTimeout = 10_000
        conn.readTimeout = readTimeoutMs
        conn.doOutput = true

        val payload = JSONObject().apply {
            put("jsonrpc", "1.0")
            put("id", idCounter.incrementAndGet())
            put("method", method)
            put("params", params)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val responseCode = conn.responseCode
        val body = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw RuntimeException("RPC error: $err")
        }

        JSONObject(body)
    }

    /**
     * Test connection by calling getblockchaininfo.
     */
    suspend fun testConnection(): ConnectionResult {
        return try {
            val resp = rpcCall("getblockchaininfo")
            if (!resp.isNull("error") && resp.get("error") != JSONObject.NULL) {
                val err = resp.getJSONObject("error")
                ConnectionResult(false, error = err.optString("message", "Unknown RPC error"))
            } else {
                val result = resp.getJSONObject("result")
                ConnectionResult(
                    success = true,
                    chain = result.optString("chain"),
                    blocks = result.optInt("blocks"),
                    headers = result.optInt("headers"),
                    verificationProgress = result.optDouble("verificationprogress", 0.0)
                )
            }
        } catch (e: Exception) {
            ConnectionResult(false, error = e.message ?: "Connection failed")
        }
    }

    /**
     * Trigger dumptxoutset on the remote node.
     * The file is written on the REMOTE node's filesystem.
     *
     * @param filename The filename to dump to (relative to node's datadir, or absolute)
     * @return DumpResult with the hash if started successfully
     */
    suspend fun triggerDumpTxOutset(filename: String = "utxo-snapshot.dat"): DumpResult {
        return try {
            // IMPORTANT: Use "rollback" type to dump at the nearest hardcoded AssumeUTXO height.
            // "latest" dumps at the current tip which loadtxoutset will reject.
            val params = JSONArray().apply {
                put(filename)
                put("rollback")
            }
            // dumptxoutset with rollback can take a very long time — it rolls back the chainstate
            // to the nearest snapshot height before dumping
            val resp = rpcCall("dumptxoutset", params, readTimeoutMs = 7_200_000)

            if (!resp.isNull("error") && resp.get("error") != JSONObject.NULL) {
                val err = resp.getJSONObject("error")
                DumpResult(false, error = err.optString("message", "dumptxoutset failed"))
            } else {
                val result = resp.getJSONObject("result")
                DumpResult(
                    success = true,
                    txoutsetHash = result.optString("txoutset_hash", null)
                )
            }
        } catch (e: Exception) {
            DumpResult(false, error = e.message ?: "dumptxoutset failed")
        }
    }

    /**
     * Check if dumptxoutset is still in progress by looking at getblockchaininfo
     * or attempting another dumptxoutset call.
     */
    suspend fun checkDumpProgress(): DumpProgress {
        return try {
            val resp = rpcCall("getblockchaininfo")
            if (!resp.isNull("error") && resp.get("error") != JSONObject.NULL) {
                DumpProgress(status = DumpStatus.UNKNOWN, error = "Could not check status")
            } else {
                // Node is responsive — dump may be complete or still running
                // Try a small dumptxoutset to see if it's busy
                DumpProgress(status = DumpStatus.UNKNOWN)
            }
        } catch (e: Exception) {
            DumpProgress(status = DumpStatus.UNKNOWN, error = e.message)
        }
    }

    data class DumpProgress(
        val status: DumpStatus,
        val error: String? = null
    )

    enum class DumpStatus {
        IN_PROGRESS, COMPLETE, UNKNOWN
    }
}
