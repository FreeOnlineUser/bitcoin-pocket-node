package com.pocketnode.lightning

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for StateBackupManager health check logic.
 * Verifies backup won't overwrite good state with corrupt/empty state.
 */
class BackupHealthTest {

    // Mirrors health check logic from StateBackupManager
    private fun shouldBackup(
        currentMonitors: Int,
        previousMonitors: Int,
        currentManagerSize: Long,
        previousManagerSize: Long
    ): Boolean {
        // If no previous backup, always allow
        if (previousMonitors == 0 && previousManagerSize == 0L) return true

        // Block if ALL monitors gone AND manager didn't grow (corruption/fresh start)
        if (currentMonitors == 0 && previousMonitors > 0) {
            // Legitimate close: manager grows (close state added)
            // Corruption/fresh start: manager stays same or shrinks
            if (currentManagerSize <= previousManagerSize) return false
        }

        // Block if manager shrank >20% (likely corruption)
        if (previousManagerSize > 0 && currentManagerSize < previousManagerSize * 80 / 100) {
            return false
        }

        return true
    }

    @Test
    fun `allow first backup with no previous state`() {
        assertTrue(shouldBackup(1, 0, 500, 0))
    }

    @Test
    fun `allow normal backup with stable state`() {
        assertTrue(shouldBackup(1, 1, 500, 500))
    }

    @Test
    fun `allow backup when manager grows (channel activity)`() {
        assertTrue(shouldBackup(1, 1, 600, 500))
    }

    @Test
    fun `allow backup when monitor removed but manager grew (legitimate close)`() {
        assertTrue(shouldBackup(0, 1, 700, 500))
    }

    @Test
    fun `block backup when monitors gone and manager same (corruption)`() {
        assertFalse(shouldBackup(0, 1, 500, 500))
    }

    @Test
    fun `block backup when monitors gone and manager shrank (fresh start)`() {
        assertFalse(shouldBackup(0, 1, 297, 500))
    }

    @Test
    fun `block backup when manager shrank significantly`() {
        assertFalse(shouldBackup(1, 1, 300, 500))
    }

    @Test
    fun `allow backup with slight manager shrink under 20 percent`() {
        assertTrue(shouldBackup(1, 1, 450, 500))
    }

    @Test
    fun `allow second channel open (monitors increase)`() {
        assertTrue(shouldBackup(2, 1, 600, 500))
    }

    @Test
    fun `allow one close when second channel still active`() {
        assertTrue(shouldBackup(1, 2, 550, 600))
    }
}
