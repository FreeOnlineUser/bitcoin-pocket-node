package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for BIP39 seed-to-master-key derivation.
 * Uses known test vectors from BIP32 to verify our HMAC-SHA512 implementation.
 *
 * BIP32 test vector 1:
 * Seed: 000102030405060708090a0b0c0d0e0f
 * Master key (IL): e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35
 * Master chain code (IR): 873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508
 */
class SeedDerivationTest {

    private data class ExtendedKey(val key: ByteArray, val chainCode: ByteArray)

    private fun deriveMasterKey(seed: ByteArray): ExtendedKey {
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(SecretKeySpec("Bitcoin seed".toByteArray(), "HmacSHA512"))
        val result = hmac.doFinal(seed)
        return ExtendedKey(
            key = result.copyOfRange(0, 32),
            chainCode = result.copyOfRange(32, 64)
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `BIP32 test vector 1 master key`() {
        val seed = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val master = deriveMasterKey(seed)

        assertEquals(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35",
            master.key.toHex()
        )
        assertEquals(
            "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            master.chainCode.toHex()
        )
    }

    @Test
    fun `BIP32 test vector 2 master key`() {
        val seed = "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542".hexToBytes()
        val master = deriveMasterKey(seed)

        assertEquals(
            "4b03d6fc340455b363f51020ad3ecca4f0850280cf436c70c727923f6db46c3e",
            master.key.toHex()
        )
        assertEquals(
            "60499f801b896d83179a4374aeb7822aaeaceaa0db1f85ee3e904c4defbd9689",
            master.chainCode.toHex()
        )
    }

    @Test
    fun `master key is always 32 bytes`() {
        val seeds = listOf(
            ByteArray(16) { it.toByte() },
            ByteArray(32) { it.toByte() },
            ByteArray(64) { it.toByte() }
        )
        for (seed in seeds) {
            val master = deriveMasterKey(seed)
            assertEquals(32, master.key.size)
            assertEquals(32, master.chainCode.size)
        }
    }

    @Test
    fun `same seed produces same master key`() {
        val seed = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val m1 = deriveMasterKey(seed)
        val m2 = deriveMasterKey(seed)
        assertArrayEquals(m1.key, m2.key)
        assertArrayEquals(m1.chainCode, m2.chainCode)
    }

    @Test
    fun `different seeds produce different master keys`() {
        val s1 = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val s2 = "000102030405060708090a0b0c0d0e0e".hexToBytes()
        val m1 = deriveMasterKey(s1)
        val m2 = deriveMasterKey(s2)
        assertFalse(m1.key.contentEquals(m2.key))
    }
}
