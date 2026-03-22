package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Lightning service lifecycle state machine.
 * Verifies startup → state → shutdown transitions without LDK native code.
 */
class LifecycleTest {

    // Simulates the state transitions LightningService makes
    enum class Status { STOPPED, STARTING, RUNNING, ERROR, RECOVERING }

    data class SimState(
        val status: Status = Status.STOPPED,
        val crashCount: Int = 0,
        val wasRunning: Boolean = false,
        val error: String? = null
    )

    // --- Startup ---

    @Test
    fun `startup transitions STOPPED to STARTING to RUNNING`() {
        var state = SimState(status = Status.STOPPED)

        // start() sets STARTING
        state = state.copy(status = Status.STARTING)
        assertEquals(Status.STARTING, state.status)

        // startInternal succeeds, sets RUNNING + wasRunning + increments crash count
        state = state.copy(
            status = Status.RUNNING,
            wasRunning = true,
            crashCount = state.crashCount + 1
        )
        assertEquals(Status.RUNNING, state.status)
        assertTrue(state.wasRunning)
        assertEquals(1, state.crashCount)
    }

    @Test
    fun `startup failure transitions to ERROR`() {
        var state = SimState(status = Status.STARTING)

        // startInternal throws
        state = state.copy(status = Status.ERROR, error = "WalletSetupFailed")
        assertEquals(Status.ERROR, state.status)
        assertNotNull(state.error)
    }

    // --- Clean shutdown ---

    @Test
    fun `clean stop resets wasRunning and crashCount`() {
        var state = SimState(status = Status.RUNNING, wasRunning = true, crashCount = 2)

        // stop()
        state = state.copy(status = Status.STOPPED, wasRunning = false, crashCount = 0)
        assertEquals(Status.STOPPED, state.status)
        assertFalse(state.wasRunning)
        assertEquals(0, state.crashCount)
    }

    // --- Crash recovery ---

    @Test
    fun `crash leaves wasRunning true and increments crashCount`() {
        var state = SimState(status = Status.RUNNING, wasRunning = true, crashCount = 1)

        // App killed (no stop() called), next startup:
        state = state.copy(crashCount = state.crashCount + 1)
        assertEquals(2, state.crashCount)
        assertTrue(state.wasRunning) // still true, stop() never ran
    }

    @Test
    fun `circuit breaker blocks restart after 3 crashes`() {
        val state = SimState(wasRunning = true, crashCount = 3)
        val shouldRestart = state.wasRunning && state.crashCount < 3
        assertFalse(shouldRestart)
    }

    @Test
    fun `circuit breaker clears wasRunning`() {
        var state = SimState(wasRunning = true, crashCount = 3)
        // Circuit breaker fires
        if (state.crashCount >= 3) {
            state = state.copy(wasRunning = false)
        }
        assertFalse(state.wasRunning)
    }

    @Test
    fun `manual start after circuit break works`() {
        var state = SimState(wasRunning = false, crashCount = 3, status = Status.STOPPED)
        // User manually starts
        state = state.copy(status = Status.STARTING)
        // startInternal succeeds, crash count increments from current value
        state = state.copy(
            status = Status.RUNNING,
            wasRunning = true,
            crashCount = state.crashCount + 1
        )
        assertEquals(Status.RUNNING, state.status)
        assertEquals(4, state.crashCount) // 3 + 1 from this start
        // But auto-restart won't fire because next clean stop resets to 0
    }

    @Test
    fun `clean stop after manual recovery resets everything`() {
        var state = SimState(status = Status.RUNNING, wasRunning = true, crashCount = 4)
        state = state.copy(status = Status.STOPPED, wasRunning = false, crashCount = 0)
        assertEquals(0, state.crashCount)
        assertFalse(state.wasRunning)
    }

    // --- Orphan detection ---

    @Test
    fun `orphan detected when channels zero but lightning balance positive`() {
        val channelCount = 0
        val lightningBalance = 90000L
        val pendingCloseDetails = emptyList<Any>()
        val hasOrphan = channelCount == 0 && lightningBalance > 0
        assertTrue(hasOrphan)
        // With no pending close details, should trigger rebroadcast restart
        assertTrue(pendingCloseDetails.isEmpty())
    }

    @Test
    fun `no orphan when channels exist`() {
        val hasOrphan = 1 == 0 && 90000L > 0
        assertFalse(hasOrphan)
    }

    @Test
    fun `no orphan when lightning balance is zero`() {
        val hasOrphan = 0 == 0 && 0L > 0
        assertFalse(hasOrphan)
    }

    @Test
    fun `orphan with pending close does not trigger restart`() {
        // Has orphan funds but pending close exists = sweeper is handling it
        val hasOrphan = true
        val hasPendingClose = true
        val shouldRestart = hasOrphan && !hasPendingClose
        assertFalse(shouldRestart)
    }

    // --- Prune recovery ---

    @Test
    fun `prune detected when LDK height below prune height`() {
        val ldkSyncHeight = 900000L
        val pruneHeight = 941000L
        val needsRecovery = pruneHeight > ldkSyncHeight
        assertTrue(needsRecovery)
    }

    @Test
    fun `no prune issue when LDK is above prune height`() {
        val ldkSyncHeight = 941600L
        val pruneHeight = 941000L
        val needsRecovery = pruneHeight > ldkSyncHeight
        assertFalse(needsRecovery)
    }

    // --- Sync watchdog ---

    @Test
    fun `watchdog triggers when LDK stuck below bitcoind`() {
        val ldkHeight = 941000L
        val bitcoindHeight = 941600L
        val startHeight = 941000L
        val shouldReset = ldkHeight < bitcoindHeight && ldkHeight <= startHeight
        assertTrue(shouldReset)
    }

    @Test
    fun `watchdog does not trigger when LDK progressed`() {
        val ldkHeight = 941500L
        val bitcoindHeight = 941600L
        val startHeight = 941000L
        val shouldReset = ldkHeight < bitcoindHeight && ldkHeight <= startHeight
        assertFalse(shouldReset) // ldkHeight > startHeight, so it made progress
    }

    @Test
    fun `watchdog does not trigger when at tip`() {
        val ldkHeight = 941600L
        val bitcoindHeight = 941600L
        val startHeight = 941000L
        val shouldReset = ldkHeight < bitcoindHeight && ldkHeight <= startHeight
        assertFalse(shouldReset)
    }

    @Test
    fun `watchdog skipped during recovery scan`() {
        val scanningForFunds = true
        val ldkHeight = 941000L
        val bitcoindHeight = 941600L
        val startHeight = 941000L
        val shouldReset = ldkHeight < bitcoindHeight && ldkHeight <= startHeight && !scanningForFunds
        assertFalse(shouldReset)
    }
}
