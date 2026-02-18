package com.pocketnode.rpc

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Bitcoin Core RPC client for making JSON-RPC calls
 */
class BitcoinRpcClient(
    private val rpcHost: String = "127.0.0.1",
    private val rpcPort: Int = 8332,
    private val rpcUser: String = "",
    private val rpcPassword: String = ""
) {
    companion object {
        private const val TAG = "BitcoinRpcClient"
        private const val CONNECT_TIMEOUT = 10000 // 10 seconds
        private const val READ_TIMEOUT = 30000 // 30 seconds
    }

    private val requestId = AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Make a Bitcoin RPC call
     * @param method RPC method name
     * @param params RPC parameters (can be list or object)
     * @return JSON response as JsonElement
     */
    suspend fun call(method: String, params: JsonElement = JsonArray(emptyList())): JsonElement = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("jsonrpc", "1.0")
            put("id", requestId.getAndIncrement())
            put("method", method)
            put("params", params)
        }

        Log.d(TAG, "RPC Call: $method")
        
        val url = URL("http://$rpcHost:$rpcPort/")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Basic " + 
                android.util.Base64.encodeToString("$rpcUser:$rpcPassword".toByteArray(), android.util.Base64.NO_WRAP))
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }

        try {
            // Send request
            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val response = json.parseToJsonElement(responseText).jsonObject
                
                // Check for RPC errors
                val error = response["error"]
                if (error != null && error !is JsonNull) {
                    throw RpcException("RPC Error: $error")
                }
                
                return@withContext response["result"] ?: JsonNull
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                throw RpcException("HTTP Error $responseCode: $errorText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "RPC call failed for method $method", e)
            if (e is RpcException) throw e
            throw RpcException("RPC call failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Convenience method for RPC calls with array parameters
     */
    suspend fun call(method: String, vararg params: JsonElement): JsonElement {
        return call(method, JsonArray(params.toList()))
    }

    /**
     * Get raw mempool with verbose transaction details
     */
    suspend fun getRawMempool(): JsonObject {
        val result = call("getrawmempool", JsonPrimitive(true))
        return result.jsonObject
    }

    /**
     * Get mempool info statistics
     */
    suspend fun getMempoolInfo(): JsonObject {
        val result = call("getmempoolinfo")
        return result.jsonObject
    }

    /**
     * Estimate smart fee for confirmation target
     */
    suspend fun estimateSmartFee(confTarget: Int): JsonObject {
        val result = call("estimatesmartfee", JsonPrimitive(confTarget))
        return result.jsonObject
    }

    /**
     * Get best block hash
     */
    suspend fun getBestBlockHash(): String {
        val result = call("getbestblockhash")
        return result.jsonPrimitive.content
    }

    /**
     * Get block by hash
     * @param blockHash Block hash
     * @param verbosity 0=hex, 1=json, 2=json with tx details
     */
    suspend fun getBlock(blockHash: String, verbosity: Int = 1): JsonElement {
        return call("getblock", JsonPrimitive(blockHash), JsonPrimitive(verbosity))
    }

    /**
     * Get raw transaction
     */
    suspend fun getRawTransaction(txid: String, verbose: Boolean = true): JsonElement {
        return call("getrawtransaction", JsonPrimitive(txid), JsonPrimitive(verbose))
    }

    /**
     * Test if Bitcoin RPC is reachable
     */
    suspend fun ping(): Boolean {
        return try {
            call("getblockchaininfo")
            true
        } catch (e: Exception) {
            Log.w(TAG, "RPC ping failed", e)
            false
        }
    }
}

/**
 * Exception for Bitcoin RPC errors
 */
class RpcException(message: String, cause: Throwable? = null) : Exception(message, cause)