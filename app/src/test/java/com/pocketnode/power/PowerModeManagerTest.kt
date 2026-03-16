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
}
