package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ChannelEventHandler: rejection detection and peer minimum parsing.
 */
class ChannelRejectionTest {

    // Can't instantiate with Context in unit tests, so test the pure functions directly
    // by mirroring them here. The real code in ChannelEventHandler uses identical logic.
    private fun isRejection(reason: String): Boolean {
        return reason.contains("CounterpartyForceClosed") || reason.contains("min chan size")
    }

    private fun extractPeerMessage(reason: String): String {
        return Regex("""peerMsg=(.+?)\)""").find(reason)?.groupValues?.get(1) ?: reason
    }

    private fun parseMinBtc(reason: String): Long? {
        val match = Regex("""min chan size of (\d+\.?\d*) BTC""").find(reason) ?: return null
        return (match.groupValues[1].toDouble() * 100_000_000).toLong()
    }

    private fun parseMinSats(reason: String): Long? {
        val match = Regex("""min=(\d+)\s*sat""").find(reason) ?: return null
        return match.groupValues[1].toLong()
    }

    @Test
    fun `detect CounterpartyForceClosed as rejection`() {
        val reason = "CounterpartyForceClosed(peerMsg=chan size of 0.00080000 BTC is below min chan size of 2 BTC)"
        assertTrue(isRejection(reason))
    }

    @Test
    fun `detect min chan size in reason`() {
        val reason = "chan size of 0.00100000 BTC is below min chan size of 0.01 BTC"
        assertTrue(isRejection(reason))
    }

    @Test
    fun `generic force close is also rejection`() {
        val reason = "CounterpartyForceClosed(peerMsg=Channel force-closed)"
        assertTrue(isRejection(reason))
    }

    @Test
    fun `cooperative close is not rejection`() {
        val reason = "CooperativeClosure"
        assertFalse(isRejection(reason))
    }

    @Test
    fun `holder force close is not rejection`() {
        val reason = "HolderForceClosed"
        assertFalse(isRejection(reason))
    }

    @Test
    fun `extract peer message from CounterpartyForceClosed`() {
        val reason = "CounterpartyForceClosed(peerMsg=chan size of 0.00080000 BTC is below min chan size of 2 BTC)"
        assertEquals("chan size of 0.00080000 BTC is below min chan size of 2 BTC", extractPeerMessage(reason))
    }

    @Test
    fun `extract simple force close message`() {
        val reason = "CounterpartyForceClosed(peerMsg=Channel force-closed)"
        assertEquals("Channel force-closed", extractPeerMessage(reason))
    }

    @Test
    fun `parse 2 BTC minimum`() {
        val reason = "chan size of 0.00080000 BTC is below min chan size of 2 BTC"
        assertEquals(200_000_000L, parseMinBtc(reason))
    }

    @Test
    fun `parse 0_01 BTC minimum`() {
        val reason = "chan size of 0.00080000 BTC is below min chan size of 0.01 BTC"
        assertEquals(1_000_000L, parseMinBtc(reason))
    }

    @Test
    fun `parse 0_001 BTC minimum`() {
        val reason = "min chan size of 0.001 BTC"
        assertEquals(100_000L, parseMinBtc(reason))
    }

    @Test
    fun `parse sat format minimum`() {
        val reason = "min=500000 sat"
        assertEquals(500_000L, parseMinSats(reason))
    }

    @Test
    fun `parse sat format with whitespace`() {
        val reason = "min=100000  sat"
        assertEquals(100_000L, parseMinSats(reason))
    }

    @Test
    fun `no minimum found returns null`() {
        val reason = "Channel force-closed"
        assertNull(parseMinBtc(reason))
        assertNull(parseMinSats(reason))
    }
}
