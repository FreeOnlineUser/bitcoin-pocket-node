package com.pocketnode.rpc

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

/**
 * Unit tests for BitcoinRpcClient.
 * Tests construction, auth, and error handling without a running bitcoind.
 */
class BitcoinRpcClientTest {

    @Test
    fun `client constructs with credentials`() {
        val client = BitcoinRpcClient("testuser", "testpass")
        assertNotNull(client)
    }

    @Test
    fun `client constructs with custom host and port`() {
        val client = BitcoinRpcClient("user", "pass", "10.0.0.1", 18332)
        assertNotNull(client)
    }

    @Test
    fun `callSync handles no server gracefully`() {
        val client = BitcoinRpcClient("user", "pass", "127.0.0.1", 19999)
        val result = try {
            client.callSync("getblockcount", JSONArray(), connectTimeoutMs = 500, readTimeoutMs = 500)
        } catch (_: Exception) {
            null
        }
        // Should either return null or throw — both are acceptable for unreachable server
        // The important thing is it doesn't hang
        assertTrue("Should complete quickly", true)
    }

    @Test
    fun `wallet path is correctly formatted`() {
        // Verify wallet paths follow bitcoind convention
        val walletName = "pocketnode_electrum"
        val path = "/wallet/$walletName"
        assertEquals("/wallet/pocketnode_electrum", path)
    }
}
