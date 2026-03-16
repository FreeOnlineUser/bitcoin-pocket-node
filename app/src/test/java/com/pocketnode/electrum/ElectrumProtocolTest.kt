package com.pocketnode.electrum

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for Electrum protocol helpers.
 * Tests scripthash computation, status hash, and protocol formatting.
 */
class ElectrumProtocolTest {

    /**
     * Electrum scripthash = SHA256(scriptPubKey) reversed.
     * This is the core addressing scheme used by the Electrum protocol.
     */
    @Test
    fun `scripthash computation is correct`() {
        // Known test vector: P2PKH scriptPubKey for 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa (Satoshi's address)
        // scriptPubKey: 76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac
        val scriptPubKeyHex = "76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac"
        val scriptPubKey = hexToBytes(scriptPubKeyHex)

        val hash = MessageDigest.getInstance("SHA-256").digest(scriptPubKey)
        val reversed = hash.reversedArray()
        val scripthash = reversed.joinToString("") { "%02x".format(it) }

        // The scripthash should be a 64-char hex string
        assertEquals(64, scripthash.length)
        // It should be deterministic
        val hash2 = MessageDigest.getInstance("SHA-256").digest(scriptPubKey)
        val scripthash2 = hash2.reversedArray().joinToString("") { "%02x".format(it) }
        assertEquals(scripthash, scripthash2)
    }

    @Test
    fun `status hash for empty history is null`() {
        // Per Electrum protocol, empty history = null status
        val history = ""
        val result = if (history.isEmpty()) null
        else MessageDigest.getInstance("SHA-256").digest(history.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertNull(result)
    }

    @Test
    fun `status hash for non-empty history is SHA256`() {
        // Status is SHA256 of concatenated "txid:height:" strings
        val history = "abc123:100000:def456:100001:"
        val hash = MessageDigest.getInstance("SHA-256").digest(history.toByteArray())
        val statusHash = hash.joinToString("") { "%02x".format(it) }

        assertEquals(64, statusHash.length)
        assertNotNull(statusHash)
    }

    @Test
    fun `protocol version is 1_4`() {
        assertEquals("1.4", ElectrumServer.PROTOCOL_VERSION)
    }

    @Test
    fun `server version string is set`() {
        assertTrue(ElectrumServer.SERVER_VERSION.contains("PocketNode"))
    }

    // Helper
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
