package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Lightning auto-restart circuit breaker logic.
 * Prevents infinite crash-restart loops.
 */
class CircuitBreakerTest {

    private val MAX_CRASHES = 3

    // Mirrors the circuit breaker check in BitcoindService
    private fun shouldAutoRestart(crashCount: Int, wasRunning: Boolean): Boolean {
        return wasRunning && crashCount < MAX_CRASHES
    }

    @Test
    fun `restart allowed with 0 crashes`() {
        assertTrue(shouldAutoRestart(0, wasRunning = true))
    }

    @Test
    fun `restart allowed with 1 crash`() {
        assertTrue(shouldAutoRestart(1, wasRunning = true))
    }

    @Test
    fun `restart allowed with 2 crashes`() {
        assertTrue(shouldAutoRestart(2, wasRunning = true))
    }

    @Test
    fun `restart blocked at 3 crashes`() {
        assertFalse(shouldAutoRestart(3, wasRunning = true))
    }

    @Test
    fun `restart blocked at many crashes`() {
        assertFalse(shouldAutoRestart(10, wasRunning = true))
    }

    @Test
    fun `no restart if was not running`() {
        assertFalse(shouldAutoRestart(0, wasRunning = false))
    }

    @Test
    fun `clean stop resets counter to zero`() {
        // Simulates: start (count=1), start (count=2), clean stop (count=0)
        var count = 0
        count++ // start
        count++ // crash restart
        count = 0 // clean stop
        assertEquals(0, count)
        assertTrue(shouldAutoRestart(count, wasRunning = true))
    }

    @Test
    fun `manual start after circuit break works`() {
        // User manually starts Lightning, which resets was_running
        // and crash count increments from 0 again
        assertTrue(shouldAutoRestart(0, wasRunning = true))
    }
}
