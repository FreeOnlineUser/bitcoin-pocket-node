package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for balance display logic.
 * Verifies on-chain balance subtracts pending close to avoid double-counting,
 * and pending close visibility thresholds.
 */
class BalanceLogicTest {

    // Mirrors the logic in LightningService.updateState()
    private fun displayOnchain(totalOnchain: Long, pendingClose: Long): Long {
        return maxOf(0L, totalOnchain - pendingClose)
    }

    // Mirrors the logic in LightningService for AwaitingThresholdConfirmations
    private fun pendingCloseVisible(confirmationHeight: Int, currentHeight: Int): Boolean {
        val spendableAt = confirmationHeight + 6  // ANTI_REORG_DELAY
        val blocksLeft = maxOf(0, spendableAt - currentHeight)
        return blocksLeft > 0
    }

    private fun blocksRemaining(confirmationHeight: Int, currentHeight: Int): Int {
        val spendableAt = confirmationHeight + 6
        return maxOf(0, spendableAt - currentHeight)
    }

    @Test
    fun `on-chain subtracts pending close`() {
        // Total includes both real wallet UTXOs and sweeper-tracked pending close
        assertEquals(81157, displayOnchain(171199, 90042))
    }

    @Test
    fun `on-chain zero when pending close equals total`() {
        // During sync, only pending close UTXO discovered
        assertEquals(0, displayOnchain(89920, 90042))
    }

    @Test
    fun `on-chain never negative`() {
        assertEquals(0, displayOnchain(50000, 100000))
    }

    @Test
    fun `on-chain full when no pending close`() {
        assertEquals(171199, displayOnchain(171199, 0))
    }

    @Test
    fun `pending close visible when under 6 confirmations`() {
        // Confirmed at height 1000, current height 1003 -> 3 blocks remaining
        assertTrue(pendingCloseVisible(1000, 1003))
        assertEquals(3, blocksRemaining(1000, 1003))
    }

    @Test
    fun `pending close visible at exactly 5 confirmations`() {
        // Confirmed at 1000, current 1005 -> spendableAt 1006, 1 block left
        assertTrue(pendingCloseVisible(1000, 1005))
        assertEquals(1, blocksRemaining(1000, 1005))
    }

    @Test
    fun `pending close hidden at 6 confirmations`() {
        // Confirmed at 1000, current 1006 -> spendableAt 1006, 0 blocks left
        assertFalse(pendingCloseVisible(1000, 1006))
        assertEquals(0, blocksRemaining(1000, 1006))
    }

    @Test
    fun `pending close hidden well past threshold`() {
        // Sweeper tracks for 4038 blocks but UI should hide after 6
        assertFalse(pendingCloseVisible(1000, 5038))
    }

    @Test
    fun `blocks remaining never negative`() {
        assertEquals(0, blocksRemaining(1000, 9999))
    }
}
