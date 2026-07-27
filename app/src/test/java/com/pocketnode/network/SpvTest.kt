package com.pocketnode.network

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-function tests for the SPV stack: SipHash-2-4, BIP 158 range
 * mapping, Bech32 address decoding, and merkle root computation.
 */
class SpvTest {

    // --- SipHash-2-4: reference vectors from the SipHash paper ---
    // key = 000102030405060708090a0b0c0d0e0f, input = first N bytes of
    // 00 01 02 ... ; expected outputs from the reference implementation.

    private val k0 = 0x0706050403020100L
    private val k1 = 0x0f0e0d0c0b0a0908L

    private fun seq(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun `siphash empty input`() {
        assertEquals(0x726fdb47dd0e0e31L, Bip158.sipHash24(k0, k1, seq(0)))
    }

    @Test
    fun `siphash one byte`() {
        assertEquals(0x74f839c593dc67fdL, Bip158.sipHash24(k0, k1, seq(1)))
    }

    @Test
    fun `siphash eight bytes - full word`() {
        assertEquals(0x93f5f5799a932462UL.toLong(), Bip158.sipHash24(k0, k1, seq(8)))
    }

    @Test
    fun `siphash fifteen bytes`() {
        assertEquals(0xa129ca6149be45e5UL.toLong(), Bip158.sipHash24(k0, k1, seq(15)))
    }

    // --- BIP 158 range mapping ---

    @Test
    fun `mapToRange stays in range and is monotonic-ish`() {
        val f = 100L * 784931L
        val v1 = Bip158.mapToRange(0L, f)
        val v2 = Bip158.mapToRange(-1L, f) // 0xFFFF... = max uint64
        assertEquals(0L, v1)
        assertEquals(f - 1, v2)
    }

    @Test
    fun `mapToRange matches known multiplication`() {
        // hash = 2^63 (top bit set) maps to f/2
        val f = 784931L * 42L
        assertEquals(f / 2, Bip158.mapToRange(Long.MIN_VALUE, f))
    }

    // --- Bech32 (BIP 173 / BIP 350 vectors) ---

    @Test
    fun `bech32 P2WPKH vector`() {
        val script = Bech32.addressToScriptPubKey("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        assertNotNull(script)
        assertEquals(
            "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            script!!.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `bech32m P2TR vector`() {
        val script = Bech32.addressToScriptPubKey(
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
        )
        assertNotNull(script)
        assertEquals(
            "512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            script!!.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun `bech32 rejects bad checksum`() {
        assertNull(Bech32.addressToScriptPubKey("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"))
    }

    @Test
    fun `bech32 rejects testnet hrp`() {
        assertNull(Bech32.addressToScriptPubKey("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
    }

    // --- Merkle root ---

    @Test
    fun `merkle root of single tx is the txid`() {
        val txid = ByteArray(32) { it.toByte() }
        assertArrayEquals(txid, SpvFetcher.computeMerkleRoot(listOf(txid)))
    }

    @Test
    fun `merkle root of two txs`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertArrayEquals(Wire.dsha256(a + b), SpvFetcher.computeMerkleRoot(listOf(a, b)))
    }

    @Test
    fun `merkle root duplicates odd last entry`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val c = ByteArray(32) { 3 }
        val level1 = listOf(Wire.dsha256(a + b), Wire.dsha256(c + c))
        val expected = Wire.dsha256(level1[0] + level1[1])
        assertArrayEquals(expected, SpvFetcher.computeMerkleRoot(listOf(a, b, c)))
    }
}
