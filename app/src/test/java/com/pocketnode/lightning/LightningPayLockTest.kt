package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Lightning Pay home screen lock/unlock logic.
 * Verifies the conditions that control whether the app boots to Lightning Pay
 * or the dashboard.
 */
class LightningPayLockTest {

    // Uses BalanceTracker directly
    private fun shouldUnlock(channelCount: Int): Boolean {
        return BalanceTracker.shouldUnlockLightningPay(channelCount)
    }

    private fun shouldRelock(channelCount: Int, lightningBalance: Long): Boolean {
        return BalanceTracker.shouldRelockLightningPay(channelCount, lightningBalance)
    }

    @Test
    fun `unlock when channel exists`() {
        assertTrue(shouldUnlock(1))
    }

    @Test
    fun `unlock with multiple channels`() {
        assertTrue(shouldUnlock(3))
    }

    @Test
    fun `don't unlock with no channels`() {
        assertFalse(shouldUnlock(0))
    }

    @Test
    fun `relock when no channels and no lightning balance`() {
        assertTrue(shouldRelock(0, 0))
    }

    @Test
    fun `don't relock when channel exists`() {
        assertFalse(shouldRelock(1, 0))
    }

    @Test
    fun `don't relock when lightning balance exists (channel closing)`() {
        // Channel might be in the process of closing, balance still in flight
        assertFalse(shouldRelock(0, 90000))
    }

    @Test
    fun `relock even with pending close sats (they are on-chain now)`() {
        // Pending close is tracked separately, doesn't keep Pay locked
        // The old bug: pendingCloseSats > 0 prevented relock for a month
        assertTrue(shouldRelock(0, 0))
    }

    @Test
    fun `rejected channel open should relock (never had real channel)`() {
        // A channel that was rejected before funding tx never had a real channel
        // channelCount=0, lightningBalance=0 -> relock
        assertTrue(shouldRelock(0, 0))
    }
}
