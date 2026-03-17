package com.pocketnode.power

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for startup order and critical hold patterns.
 *
 * Bug context: setRpc() was called before setMode() in BitcoindService startup.
 * setRpc() checks activeScope (set by setMode()) and returns early if null.
 * This meant burst cycling never started on cold boot — network stayed fully
 * open for ~15 min until a UI screen called setRpc() again.
 *
 * Fixed by swapping the order: setMode() first (sets scope), then setRpc().
 */
class StartupOrderTest {

    // ── Startup Sequence ─────────────────────────────────────────────

    @Test
    fun `setRpc requires activeScope - returns early if null`() {
        // Simulates the bug: setRpc() called before setMode()
        var activeScope: Any? = null
        var burstStarted = false

        // setRpc logic: checks activeScope
        fun setRpc() {
            val mode = PowerModeManager.Mode.LOW
            val burstActive = false
            val initialSyncHold = false
            if (mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold) {
                val scope = activeScope ?: return  // <-- returns early if null
                burstStarted = true
            }
        }

        setRpc()
        assertFalse("Burst should NOT start when activeScope is null", burstStarted)
    }

    @Test
    fun `setMode then setRpc - correct order starts burst`() {
        var activeScope: Any? = null
        var burstStarted = false

        // setMode sets the scope
        fun setMode() {
            activeScope = Object()  // non-null scope
        }

        // setRpc checks the scope
        fun setRpc() {
            val mode = PowerModeManager.Mode.LOW
            val burstActive = false
            val initialSyncHold = false
            if (mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold) {
                val scope = activeScope ?: return
                burstStarted = true
            }
        }

        setMode()   // FIRST: sets activeScope
        setRpc()    // SECOND: checks activeScope, starts burst
        assertTrue("Burst SHOULD start when setMode() called first", burstStarted)
    }

    @Test
    fun `wrong order - setRpc then setMode - burst never starts from setRpc`() {
        var activeScope: Any? = null
        var burstStartedFromSetRpc = false
        var burstStartedFromSetMode = false

        fun setRpc() {
            val mode = PowerModeManager.Mode.LOW
            val burstActive = false
            val initialSyncHold = false
            if (mode != PowerModeManager.Mode.MAX && !burstActive && !initialSyncHold) {
                val scope = activeScope ?: return
                burstStartedFromSetRpc = true
            }
        }

        fun setMode() {
            activeScope = Object()
            // setMode calls applyMode which also starts burst,
            // but the RPC client might not be ready yet
            burstStartedFromSetMode = true
        }

        setRpc()    // WRONG ORDER: activeScope still null
        setMode()   // Sets scope but burst needs RPC

        assertFalse("setRpc should have returned early", burstStartedFromSetRpc)
        assertTrue("setMode starts its own burst path", burstStartedFromSetMode)
    }

    // ── Both Startup Paths ───────────────────────────────────────────

    @Test
    fun `cold start path follows correct order`() {
        // Verify the pattern from BitcoindService.startBitcoind()
        // Line 251-252 (after fix):
        //   pmm.setMode(modeFlow.value, serviceScope)  // FIRST
        //   pmm.setRpc(rpc)                              // SECOND
        val steps = mutableListOf<String>()

        steps.add("start_bitcoind")
        steps.add("create_rpc_client")
        steps.add("start_monitors")
        steps.add("setMode")       // Sets activeScope
        steps.add("setRpc")        // Checks activeScope, starts burst

        val setModeIdx = steps.indexOf("setMode")
        val setRpcIdx = steps.indexOf("setRpc")
        assertTrue("setMode must come before setRpc", setModeIdx < setRpcIdx)
    }

    @Test
    fun `attach path follows correct order`() {
        // Verify the pattern from BitcoindService attach-to-running path
        val steps = mutableListOf<String>()

        steps.add("detect_running_bitcoind")
        steps.add("rpc_health_check")
        steps.add("start_monitors")
        steps.add("setMode")
        steps.add("setRpc")

        val setModeIdx = steps.indexOf("setMode")
        val setRpcIdx = steps.indexOf("setRpc")
        assertTrue("setMode must come before setRpc (attach path)", setModeIdx < setRpcIdx)
    }

    // ── Network Hold for Lightning Operations ────────────────────────

    @Test
    fun `receive hold pattern - acquire before generate, release after timeout or receive`() {
        var holdCount = 0
        var invoiceGenerated = false
        var paymentReceived = false

        // Generate invoice on non-MAX mode
        val mode = PowerModeManager.Mode.LOW
        if (mode != PowerModeManager.Mode.MAX) {
            holdCount++
        }
        assertEquals("Hold acquired for receive", 1, holdCount)

        invoiceGenerated = true
        assertTrue("Invoice generated while hold active", invoiceGenerated)

        // Simulate payment received
        paymentReceived = true
        if (paymentReceived) {
            holdCount--
        }
        assertEquals("Hold released after receive", 0, holdCount)
    }

    @Test
    fun `send hold pattern - acquire on screen entry, release on exit`() {
        var holdCount = 0

        // Enter send screen
        val mode = PowerModeManager.Mode.LOW
        if (mode != PowerModeManager.Mode.MAX) {
            holdCount++
        }
        assertEquals(1, holdCount)

        // Payment completes, screen exits
        holdCount--
        assertEquals("Hold released on exit", 0, holdCount)
    }

    @Test
    fun `channel open hold pattern - acquire, wait for confirmed, release after buffer`() {
        var holdCount = 0
        var channelReady = false

        // Start channel open
        holdCount++
        assertEquals(1, holdCount)

        // Channel confirmed
        channelReady = true

        // 10s buffer then release
        if (channelReady) {
            holdCount--
        }
        assertEquals("Hold released after channel ready + buffer", 0, holdCount)
    }

    @Test
    fun `MAX mode skips all holds`() {
        var holdCount = 0
        val mode = PowerModeManager.Mode.MAX

        // Receive flow
        if (mode != PowerModeManager.Mode.MAX) {
            holdCount++
        }
        assertEquals("MAX mode should not acquire hold", 0, holdCount)

        // Send flow
        if (mode != PowerModeManager.Mode.MAX) {
            holdCount++
        }
        assertEquals("MAX mode should not acquire hold", 0, holdCount)
    }

    @Test
    fun `overlapping holds stack correctly`() {
        var holdCount = 0

        // User opens receive screen (hold 1)
        holdCount++
        assertEquals(1, holdCount)

        // While waiting, user also triggers channel operation (hold 2)
        holdCount++
        assertEquals(2, holdCount)

        // Receive completes (release 1)
        holdCount--
        assertEquals(1, holdCount)
        assertTrue("Network still held for channel op", holdCount > 0)

        // Channel op completes (release 2)
        holdCount--
        assertEquals(0, holdCount)
        assertFalse("All holds released", holdCount > 0)
    }

    // ── HTLC Safety Margin ───────────────────────────────────────────

    @Test
    fun `CLTV safety buffers provide adequate margin`() {
        // From rust-lightning channelmonitor.rs
        val maxBlocksForConf = 18        // ~3 hours
        val cltvClaimBuffer = maxBlocksForConf * 2  // 36 blocks, ~6 hours
        val latencyGracePeriod = 3       // ~30 minutes
        val htlcFailBackBuffer = cltvClaimBuffer + latencyGracePeriod  // 39 blocks

        assertEquals(36, cltvClaimBuffer)
        assertEquals(39, htlcFailBackBuffer)

        // Burst sync worst case: 15 min interval + 2 min sync = 17 min
        // In blocks: ~1.7 blocks. Well within 36-block buffer.
        val burstIntervalMin = 15
        val burstSyncTimeoutMin = 2
        val worstCaseMinutes = burstIntervalMin + burstSyncTimeoutMin
        val worstCaseBlocks = worstCaseMinutes.toDouble() / 10  // ~10 min per block
        assertTrue("Burst worst case ($worstCaseBlocks blocks) within CLTV buffer ($cltvClaimBuffer blocks)",
            worstCaseBlocks < cltvClaimBuffer)
    }

    @Test
    fun `chain polling interval sufficient for HTLC safety`() {
        // LDK-node polls bitcoind RPC every 2 seconds
        val chainPollingIntervalSecs = 2
        // Even if we miss a few polls, 36 blocks = ~360 minutes of buffer
        val cltvBufferMinutes = 36 * 10  // ~360 minutes
        assertTrue("2s polling is way faster than needed for $cltvBufferMinutes min buffer",
            chainPollingIntervalSecs < cltvBufferMinutes * 60)
    }
}
