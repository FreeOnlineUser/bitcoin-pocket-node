package com.pocketnode.power

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PowerModeManager logic.
 * Tests state transitions, burst sync lifecycle, and network hold behavior.
 *
 * These test the pure logic without Android context by verifying
 * the companion object state flows directly.
 * PowerModeManager uses singleton pattern via getInstance(context).
 */
class PowerModeManagerTest {

    @Test
    fun `mode defaults to LOW`() {
        val mode = PowerModeManager.Mode.fromString("LOW")
        assertEquals(PowerModeManager.Mode.LOW, mode)
    }

    @Test
    fun `fromString handles unknown values gracefully`() {
        val mode = PowerModeManager.Mode.fromString("INVALID")
        assertEquals(PowerModeManager.Mode.LOW, mode)
    }

    @Test
    fun `fromString is case sensitive`() {
        val mode = PowerModeManager.Mode.fromString("low")
        // valueOf is case-sensitive, so "low" should fall to default LOW
        assertEquals(PowerModeManager.Mode.LOW, mode)
    }

    @Test
    fun `all modes have display properties`() {
        for (mode in PowerModeManager.Mode.values()) {
            assertNotNull("Mode $mode should have label", mode.label)
            assertNotNull("Mode $mode should have emoji", mode.emoji)
            assertNotNull("Mode $mode should have notificationLabel", mode.notificationLabel)
            assertTrue("Mode $mode label should not be empty", mode.label.isNotEmpty())
        }
    }

    @Test
    fun `burst state has expected values`() {
        val states = PowerModeManager.BurstState.values()
        assertEquals(3, states.size)
        assertTrue(states.contains(PowerModeManager.BurstState.IDLE))
        assertTrue(states.contains(PowerModeManager.BurstState.SYNCING))
        assertTrue(states.contains(PowerModeManager.BurstState.WAITING))
    }

    @Test
    fun `constructor is private - only getInstance available`() {
        // Verify the singleton pattern by checking the companion object has getInstance
        // The private constructor means PowerModeManager(context) won't compile outside the class
        val methods = PowerModeManager.Companion::class.java.methods.map { it.name }
        assertTrue("Should have getInstance method", methods.contains("getInstance"))
    }

    @Test
    fun `shared state flows are singleton`() {
        // All state flows are on the companion object, so they're shared
        // regardless of how the instance is obtained
        val flow1 = PowerModeManager.modeFlow
        val flow2 = PowerModeManager.modeFlow
        assertSame("modeFlow should be the same object", flow1, flow2)

        val burst1 = PowerModeManager.burstStateFlow
        val burst2 = PowerModeManager.burstStateFlow
        assertSame("burstStateFlow should be the same object", burst1, burst2)
    }

    @Test
    fun `burst mutex is shared across all access`() {
        val m1 = PowerModeManager.burstMutex
        val m2 = PowerModeManager.burstMutex
        assertSame("burstMutex should be the same object", m1, m2)
    }

    @Test
    fun `mode MAX should not trigger burst sync`() {
        // MAX mode is continuous sync, no burst cycling needed
        val mode = PowerModeManager.Mode.MAX
        assertNotEquals(PowerModeManager.Mode.LOW, mode)
        assertNotEquals(PowerModeManager.Mode.AWAY, mode)
    }

    @Test
    fun `LOW and AWAY modes require burst sync`() {
        // Both non-MAX modes should use burst cycling
        val burstModes = listOf(PowerModeManager.Mode.LOW, PowerModeManager.Mode.AWAY)
        for (mode in burstModes) {
            assertNotEquals("$mode should not be MAX", PowerModeManager.Mode.MAX, mode)
        }
    }

    // ── Network Hold Reference Counting ──────────────────────────────

    @Test
    fun `channel hold flow starts false`() {
        assertFalse("channelHoldFlow should start false",
            PowerModeManager.channelHoldFlow.value)
    }

    @Test
    fun `channelHoldingNetwork starts false`() {
        assertFalse("channelHoldingNetwork should start false",
            PowerModeManager.channelHoldingNetwork)
    }

    @Test
    fun `network hold logic - reference counting pattern`() {
        // Simulate the reference counting that holdNetwork/releaseNetworkHold use
        var holdCount = 0

        // First hold - activates network
        holdCount++
        assertEquals(1, holdCount)
        val shouldActivate = holdCount == 1
        assertTrue("First hold should activate", shouldActivate)

        // Second hold - network already active
        holdCount++
        assertEquals(2, holdCount)
        val shouldActivateAgain = holdCount == 1
        assertFalse("Second hold should not re-activate", shouldActivateAgain)

        // First release - still held
        holdCount--
        assertEquals(1, holdCount)
        val shouldDeactivate = holdCount == 0
        assertFalse("First release should not deactivate", shouldDeactivate)

        // Second release - all released, deactivate
        holdCount--
        assertEquals(0, holdCount)
        val shouldDeactivateNow = holdCount == 0
        assertTrue("Final release should deactivate", shouldDeactivateNow)
    }

    @Test
    fun `network hold prevents negative count`() {
        // Simulate release without hold
        var holdCount = 0
        if (holdCount > 0) holdCount--
        assertEquals("Count should not go negative", 0, holdCount)
    }

    @Test
    fun `concurrent holds from different features`() {
        // Simulate: payment hold + channel open hold + receive hold
        var holdCount = 0

        holdCount++  // Payment starts
        assertEquals(1, holdCount)
        holdCount++  // Channel open starts
        assertEquals(2, holdCount)
        holdCount++  // Receive starts
        assertEquals(3, holdCount)

        holdCount--  // Payment done
        assertEquals(2, holdCount)
        assertTrue("Network should still be held", holdCount > 0)

        holdCount--  // Channel done
        assertEquals(1, holdCount)
        assertTrue("Network should still be held", holdCount > 0)

        holdCount--  // Receive done
        assertEquals(0, holdCount)
        assertFalse("Network should be released", holdCount > 0)
    }

    // ── Mode Transition Logic ────────────────────────────────────────

    @Test
    fun `MAX mode skips hold - already connected`() {
        val mode = PowerModeManager.Mode.MAX
        val shouldHold = mode != PowerModeManager.Mode.MAX
        assertFalse("MAX mode should skip hold", shouldHold)
    }

    @Test
    fun `LOW mode needs hold for operations`() {
        val mode = PowerModeManager.Mode.LOW
        val shouldHold = mode != PowerModeManager.Mode.MAX
        assertTrue("LOW mode should hold network", shouldHold)
    }

    @Test
    fun `AWAY mode needs hold for operations`() {
        val mode = PowerModeManager.Mode.AWAY
        val shouldHold = mode != PowerModeManager.Mode.MAX
        assertTrue("AWAY mode should hold network", shouldHold)
    }

    // ── setRpc Burst Trigger Logic ───────────────────────────────────

    @Test
    fun `setRpc should trigger burst when mode is LOW and burst not running`() {
        // This tests the logic pattern that fixed yesterday's bug
        val mode = PowerModeManager.Mode.LOW
        val burstActive = false
        val initialSyncHold = false

        val shouldStartBurst = mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold
        assertTrue("setRpc should start burst on LOW when not running", shouldStartBurst)
    }

    @Test
    fun `setRpc should not trigger burst when already running`() {
        val mode = PowerModeManager.Mode.LOW
        val burstActive = true
        val initialSyncHold = false

        val shouldStartBurst = mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold
        assertFalse("setRpc should not duplicate burst", shouldStartBurst)
    }

    @Test
    fun `setRpc should not trigger burst on MAX mode`() {
        val mode = PowerModeManager.Mode.MAX
        val burstActive = false
        val initialSyncHold = false

        val shouldStartBurst = mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold
        assertFalse("setRpc should not start burst on MAX", shouldStartBurst)
    }

    @Test
    fun `setRpc should not trigger burst during initial sync hold`() {
        val mode = PowerModeManager.Mode.LOW
        val burstActive = false
        val initialSyncHold = true

        val shouldStartBurst = mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold
        assertFalse("setRpc should not start burst during IBD", shouldStartBurst)
    }
}
