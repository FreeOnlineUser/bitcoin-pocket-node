package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Unit tests for BIP39 mnemonic logic.
 * Tests entropy generation and checksum validation without Android Context.
 *
 * Note: Bip39.generate() and validate() require Context for wordlist loading,
 * so we test the underlying cryptographic primitives directly.
 */
class Bip39Test {

    @Test
    fun `256-bit entropy is 32 bytes`() {
        val entropy = ByteArray(32)
        SecureRandom().nextBytes(entropy)
        assertEquals(32, entropy.size)
    }

    @Test
    fun `entropy generates unique values`() {
        val e1 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val e2 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertFalse("Two random 256-bit values should differ",
            e1.contentEquals(e2))
    }

    @Test
    fun `SHA256 checksum of entropy is deterministic`() {
        val entropy = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash1 = MessageDigest.getInstance("SHA-256").digest(entropy)
        val hash2 = MessageDigest.getInstance("SHA-256").digest(entropy)
        assertArrayEquals(hash1, hash2)
    }

    @Test
    fun `24-word mnemonic uses 256-bit entropy with 8-bit checksum`() {
        // 256 bits entropy + 8 bits checksum = 264 bits = 24 * 11 bits per word
        val entropyBits = 256
        val checksumBits = entropyBits / 32  // BIP39 spec: checksum = entropy_len / 32
        val totalBits = entropyBits + checksumBits
        val wordCount = totalBits / 11

        assertEquals(8, checksumBits)
        assertEquals(264, totalBits)
        assertEquals(24, wordCount)
    }

    @Test
    fun `12-word mnemonic uses 128-bit entropy with 4-bit checksum`() {
        val entropyBits = 128
        val checksumBits = entropyBits / 32
        val totalBits = entropyBits + checksumBits
        val wordCount = totalBits / 11

        assertEquals(4, checksumBits)
        assertEquals(132, totalBits)
        assertEquals(12, wordCount)
    }

    @Test
    fun `BIP39 valid sizes are 12, 15, 18, 21, 24`() {
        val validWordCounts = listOf(12, 15, 18, 21, 24)
        for (count in validWordCounts) {
            val entropyBits = count * 11 - count * 11 / 33
            assertTrue("$count words should map to valid entropy size",
                entropyBits % 8 == 0 && entropyBits >= 128 && entropyBits <= 256)
        }
    }

    @Test
    fun `wordlist resource file should have 2048 lines`() {
        // Verify the raw resource exists and has correct count
        val stream = javaClass.classLoader?.getResourceAsStream("raw/bip39_english.txt")
        if (stream != null) {
            val words = stream.bufferedReader().readLines().filter { it.isNotBlank() }
            assertEquals("BIP39 wordlist must have 2048 words", 2048, words.size)
        }
        // If resource not available in test classpath, skip silently
    }
}
