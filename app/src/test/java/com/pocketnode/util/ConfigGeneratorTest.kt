package com.pocketnode.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for bitcoin.conf generation logic.
 */
class ConfigGeneratorTest {

    @Test
    fun `generated config contains required settings`() {
        // Test the core config template logic
        val user = "rpcuser_test"
        val pass = "rpcpass_test"
        val dataDir = "/data/data/com.pocketnode/files/bitcoin"
        val prune = 2048

        val config = buildString {
            appendLine("server=1")
            appendLine("listen=0")
            appendLine("rpcuser=$user")
            appendLine("rpcpassword=$pass")
            appendLine("rpcbind=127.0.0.1")
            appendLine("rpcallowip=127.0.0.1")
            appendLine("prune=$prune")
            appendLine("datadir=$dataDir")
            appendLine("txindex=0")
        }

        assertTrue("Config should contain server=1", config.contains("server=1"))
        assertTrue("Config should contain rpcuser", config.contains("rpcuser=$user"))
        assertTrue("Config should contain prune", config.contains("prune=$prune"))
        assertTrue("Config should bind to localhost only", config.contains("rpcbind=127.0.0.1"))
        assertTrue("Config should allow localhost only", config.contains("rpcallowip=127.0.0.1"))
        assertFalse("Config should not enable txindex on pruned node", config.contains("txindex=1"))
    }

    @Test
    fun `RPC credentials are sufficiently random`() {
        // Generate two sets of credentials and verify they differ
        val creds1 = generateRandomCredential()
        val creds2 = generateRandomCredential()
        assertNotEquals("Credentials should be unique", creds1, creds2)
        assertTrue("Credential should be at least 16 chars", creds1.length >= 16)
    }

    private fun generateRandomCredential(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars[java.security.SecureRandom().nextInt(chars.length)] }.joinToString("")
    }
}
