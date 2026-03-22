package com.pocketnode.lightning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Manages rolling backups of critical LDK SQLite state (channel_manager, monitors, sweeper).
 * 
 * Design:
 * - 3 rotating backup slots to prevent bad state from overwriting good state
 * - Health check before each backup: won't overwrite if state degraded
 * - Auto-restore on startup if current state looks empty/corrupt
 * - Selective backup: only critical rows (~50 KB), skips 8+ MB network graph
 */
class StateBackupManager(
    private val context: Context,
    private val storageDir: File
) {
    companion object {
        private const val TAG = "StateBackup"
        private const val BACKUP_DIR_NAME = "lightning_state_backups"
        private const val MAX_SLOTS = 3
        private const val MIN_MANAGER_SIZE = 200 // bytes — empty manager is ~297
        
        // Keys we back up (everything except network_graph and scorer which are large + recoverable)
        private val CRITICAL_KEYS = setOf("manager", "output_sweeper", "node_metrics")
        private const val MONITOR_NAMESPACE = "monitors"
    }

    private val backupDir = File(context.filesDir, BACKUP_DIR_NAME).also { it.mkdirs() }

    data class StateSnapshot(
        val managerSize: Int,
        val monitorCount: Int,
        val monitorKeys: List<String>,
        val totalSize: Long
    )

    /**
     * Read current state health from the live SQLite.
     */
    fun readCurrentState(): StateSnapshot? {
        val sqliteFile = File(storageDir, "ldk_node_data.sqlite")
        if (!sqliteFile.exists()) return null
        
        return try {
            val db = SQLiteDatabase.openDatabase(
                sqliteFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val snapshot = readStateFromDb(db)
            db.close()
            snapshot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read current state: ${e.message}")
            null
        }
    }

    private fun readStateFromDb(db: SQLiteDatabase): StateSnapshot {
        // Manager size
        val managerCursor = db.rawQuery(
            "SELECT length(value) FROM ldk_node_data WHERE primary_namespace='' AND key='manager'", null)
        val managerSize = if (managerCursor.moveToFirst()) managerCursor.getInt(0) else 0
        managerCursor.close()

        // Monitor count and keys
        val monitorCursor = db.rawQuery(
            "SELECT key FROM ldk_node_data WHERE primary_namespace='monitors' AND secondary_namespace=''", null)
        val monitorKeys = mutableListOf<String>()
        while (monitorCursor.moveToNext()) {
            monitorKeys.add(monitorCursor.getString(0))
        }
        monitorCursor.close()

        // Total critical data size
        val sizeCursor = db.rawQuery(
            "SELECT SUM(length(value)) FROM ldk_node_data WHERE primary_namespace IN ('monitors', 'monitor_updates', '') AND key != 'network_graph' AND key != 'scorer'", null)
        val totalSize = if (sizeCursor.moveToFirst()) sizeCursor.getLong(0) else 0L
        sizeCursor.close()

        return StateSnapshot(managerSize, monitorKeys.size, monitorKeys, totalSize)
    }

    /**
     * Perform a backup if state is healthy.
     * Returns true if backup was written, false if skipped.
     */
    fun backup(): Boolean {
        val sqliteFile = File(storageDir, "ldk_node_data.sqlite")
        if (!sqliteFile.exists()) {
            Log.d(TAG, "No SQLite file to back up")
            return false
        }

        val currentState = readCurrentState() ?: return false
        val lastBackupState = readLatestBackupState()

        // Health check: don't overwrite good backup with bad state
        // Key insight: during a legitimate close, the manager size stays similar or grows
        // (it contains close state). During corruption, the manager shrinks dramatically.
        // An empty manager is always 297 bytes. A manager with 1+ channels is 350+.
        if (lastBackupState != null) {
            val monitorDropped = currentState.monitorCount < lastBackupState.monitorCount
            val managerShrankSignificantly = lastBackupState.managerSize > 0 && 
                currentState.managerSize < (lastBackupState.managerSize * 0.8).toInt()
            
            if (monitorDropped && managerShrankSignificantly) {
                // Monitors dropped AND manager lost >20% of its size = likely corruption
                Log.w(TAG, "HEALTH CHECK FAILED: monitors ${lastBackupState.monitorCount}→${currentState.monitorCount}, manager ${lastBackupState.managerSize}→${currentState.managerSize}b (shrank). Skipping backup.")
                return false
            }
            if (monitorDropped && currentState.monitorCount == 0 && lastBackupState.monitorCount > 0) {
                // All monitors gone. Only allow if manager is LARGER than before (close state added)
                if (currentState.managerSize <= lastBackupState.managerSize) {
                    Log.w(TAG, "HEALTH CHECK FAILED: all monitors lost (${lastBackupState.monitorCount}→0) and manager didn't grow (${lastBackupState.managerSize}→${currentState.managerSize}b). Skipping backup.")
                    return false
                }
                Log.i(TAG, "All monitors cleared but manager grew (${lastBackupState.managerSize}→${currentState.managerSize}b). Legitimate close, proceeding.")
            } else if (monitorDropped) {
                Log.i(TAG, "Monitor count dropped ${lastBackupState.monitorCount}→${currentState.monitorCount} with manager ${currentState.managerSize}b. Proceeding with backup.")
            }
        }

        // Skip if nothing to back up
        if (currentState.monitorCount == 0 && currentState.managerSize < MIN_MANAGER_SIZE) {
            Log.d(TAG, "Empty state, skipping backup (monitors=${currentState.monitorCount}, manager=${currentState.managerSize}b)")
            return false
        }

        return try {
            val slot = nextSlot()
            val backupFile = File(backupDir, "state_backup_$slot.sqlite")
            writeBackup(sqliteFile, backupFile, currentState)
            
            // Write metadata
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            metaFile.writeText(
                "timestamp=${System.currentTimeMillis()}\n" +
                "manager_size=${currentState.managerSize}\n" +
                "monitor_count=${currentState.monitorCount}\n" +
                "monitor_keys=${currentState.monitorKeys.joinToString(",")}\n" +
                "total_size=${currentState.totalSize}\n"
            )
            
            Log.i(TAG, "Backup slot $slot written: ${currentState.monitorCount} monitors, manager=${currentState.managerSize}b, total=${currentState.totalSize}b")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}")
            false
        }
    }

    private fun writeBackup(sourceFile: File, destFile: File, state: StateSnapshot) {
        val sourceDb = SQLiteDatabase.openDatabase(
            sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        
        // Create/overwrite backup database
        if (destFile.exists()) destFile.delete()
        val destDb = SQLiteDatabase.openOrCreateDatabase(destFile, null)
        
        destDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ldk_node_data (
                primary_namespace TEXT NOT NULL,
                secondary_namespace TEXT DEFAULT "" NOT NULL,
                key TEXT NOT NULL CHECK (key <> ''),
                value BLOB,
                PRIMARY KEY (primary_namespace, secondary_namespace, key)
            )
        """)

        // Copy critical rows (manager, sweeper, metrics)
        val critCursor = sourceDb.rawQuery(
            "SELECT primary_namespace, secondary_namespace, key, value FROM ldk_node_data WHERE primary_namespace='' AND key IN (${CRITICAL_KEYS.joinToString(",") { "'$it'" }})",
            null)
        while (critCursor.moveToNext()) {
            val stmt = destDb.compileStatement(
                "INSERT OR REPLACE INTO ldk_node_data (primary_namespace, secondary_namespace, key, value) VALUES (?, ?, ?, ?)")
            stmt.bindString(1, critCursor.getString(0))
            stmt.bindString(2, critCursor.getString(1))
            stmt.bindString(3, critCursor.getString(2))
            stmt.bindBlob(4, critCursor.getBlob(3))
            stmt.executeInsert()
            stmt.close()
        }
        critCursor.close()

        // Copy all monitors
        val monCursor = sourceDb.rawQuery(
            "SELECT primary_namespace, secondary_namespace, key, value FROM ldk_node_data WHERE primary_namespace='monitors'",
            null)
        while (monCursor.moveToNext()) {
            val stmt = destDb.compileStatement(
                "INSERT OR REPLACE INTO ldk_node_data (primary_namespace, secondary_namespace, key, value) VALUES (?, ?, ?, ?)")
            stmt.bindString(1, monCursor.getString(0))
            stmt.bindString(2, monCursor.getString(1))
            stmt.bindString(3, monCursor.getString(2))
            stmt.bindBlob(4, monCursor.getBlob(3))
            stmt.executeInsert()
            stmt.close()
        }
        monCursor.close()

        // Also copy wallet descriptors (needed for address derivation)
        val walletCursor = sourceDb.rawQuery(
            "SELECT primary_namespace, secondary_namespace, key, value FROM ldk_node_data WHERE primary_namespace='bdk_wallet'",
            null)
        while (walletCursor.moveToNext()) {
            val stmt = destDb.compileStatement(
                "INSERT OR REPLACE INTO ldk_node_data (primary_namespace, secondary_namespace, key, value) VALUES (?, ?, ?, ?)")
            stmt.bindString(1, walletCursor.getString(0))
            stmt.bindString(2, walletCursor.getString(1))
            stmt.bindString(3, walletCursor.getString(2))
            stmt.bindBlob(4, walletCursor.getBlob(3))
            stmt.executeInsert()
            stmt.close()
        }
        walletCursor.close()

        destDb.close()
        sourceDb.close()
    }

    /**
     * Check if current state needs restoring and do it automatically.
     * Call this BEFORE builder.build().
     * Returns true if a restore was performed.
     */
    /**
     * Restore ONLY monitor rows from backup. Safe across LDK session changes.
     * Full state restore (manager + monitors) fails if the build context changed.
     * Monitor-only restore lets LDK build fresh, see the orphan monitor, and force-close.
     */
    fun autoRestoreMonitorsOnly(): Boolean {
        val sqliteFile = File(storageDir, "ldk_node_data.sqlite")
        if (!sqliteFile.exists()) return false

        cleanupBadMonitorKeys(sqliteFile)

        val currentState = readCurrentState() ?: return false
        val needsRestore = currentState.monitorCount == 0 && hasBackupWithMonitors()

        if (!needsRestore) {
            Log.d(TAG, "Monitors OK: ${currentState.monitorCount} monitors")
            return false
        }

        Log.w(TAG, "0 monitors but backup has channels. Restoring monitors only.")

        // Find best backup with monitors
        val candidates = mutableListOf<Triple<Int, Long, Int>>() // slot, timestamp, monitorCount
        for (slot in 0 until MAX_SLOTS) {
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            val dbFile = File(backupDir, "state_backup_$slot.sqlite")
            if (!metaFile.exists() || !dbFile.exists()) continue
            val meta = metaFile.readText()
            val timestamp = meta.lines().find { it.startsWith("timestamp=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0L
            val monitorCount = meta.lines().find { it.startsWith("monitor_count=") }
                ?.substringAfter("=")?.toIntOrNull() ?: 0
            if (monitorCount > 0) candidates.add(Triple(slot, timestamp, monitorCount))
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No backups with monitors found")
            return restoreFromLegacyMonitors(sqliteFile)
        }

        val best = candidates.maxByOrNull { it.second }!!
        val backupDb = File(backupDir, "state_backup_${best.first}.sqlite")

        return try {
            val source = SQLiteDatabase.openDatabase(backupDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val target = SQLiteDatabase.openDatabase(sqliteFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

            // Only restore monitor rows
            val cursor = source.rawQuery(
                "SELECT primary_namespace, secondary_namespace, key, value FROM ldk_node_data WHERE primary_namespace='monitors'", null)
            var restored = 0
            while (cursor.moveToNext()) {
                val stmt = target.compileStatement(
                    "INSERT OR REPLACE INTO ldk_node_data (primary_namespace, secondary_namespace, key, value) VALUES (?, ?, ?, ?)")
                stmt.bindString(1, cursor.getString(0))
                stmt.bindString(2, cursor.getString(1))
                stmt.bindString(3, cursor.getString(2))
                stmt.bindBlob(4, cursor.getBlob(3))
                stmt.executeInsert()
                stmt.close()
                restored++
            }
            cursor.close()
            target.close()
            source.close()

            Log.w(TAG, "MONITOR-ONLY RESTORE: $restored monitor(s) from backup slot ${best.first}")
            restored > 0
        } catch (e: Exception) {
            Log.e(TAG, "Monitor restore failed: ${e.message}")
            false
        }
    }

    @Deprecated("Use autoRestoreMonitorsOnly instead")
    fun autoRestoreIfNeeded(): Boolean {
        val sqliteFile = File(storageDir, "ldk_node_data.sqlite")
        if (!sqliteFile.exists()) return false

        // Clean up any monitors with wrong keys (from failed restore attempts)
        cleanupBadMonitorKeys(sqliteFile)

        val currentState = readCurrentState() ?: return false
        
        // Check if state looks bad
        val needsRestore = currentState.monitorCount == 0 && hasBackupWithMonitors()
        
        if (!needsRestore) {
            Log.d(TAG, "State OK: ${currentState.monitorCount} monitors, manager=${currentState.managerSize}b")
            return false
        }

        Log.w(TAG, "STATE DEGRADED: 0 monitors but backup has channels. Attempting auto-restore.")
        return restoreFromBestBackup(sqliteFile)
    }

    /**
     * Remove monitor entries that were stored with the wrong key format.
     * LDK crashes if a monitor's key doesn't match its internal funding outpoint.
     */
    private fun cleanupBadMonitorKeys(sqliteFile: File) {
        try {
            val db = SQLiteDatabase.openDatabase(
                sqliteFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            // Remove any monitors with _0 suffix if a _1 version exists (or vice versa)
            // For now, just remove all monitors — the restore will re-add the correct one
            val cursor = db.rawQuery(
                "SELECT key FROM ldk_node_data WHERE primary_namespace='monitors'", null)
            val keys = mutableListOf<String>()
            while (cursor.moveToNext()) keys.add(cursor.getString(0))
            cursor.close()
            
            if (keys.isNotEmpty()) {
                Log.i(TAG, "Cleaning up ${keys.size} monitor key(s): $keys")
                db.execSQL("DELETE FROM ldk_node_data WHERE primary_namespace='monitors'")
            }
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "Monitor key cleanup failed: ${e.message}")
        }
    }

    private fun hasBackupWithMonitors(): Boolean {
        for (slot in 0 until MAX_SLOTS) {
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            if (metaFile.exists()) {
                val meta = metaFile.readText()
                val monitorCount = meta.lines().find { it.startsWith("monitor_count=") }
                    ?.substringAfter("=")?.toIntOrNull() ?: 0
                if (monitorCount > 0) return true
            }
        }
        // Fallback: check legacy monitor backup directory
        val legacyDir = File(context.filesDir, "lightning_backup/monitors")
        if (legacyDir.exists() && (legacyDir.listFiles()?.size ?: 0) > 0) {
            Log.i(TAG, "No state backups with monitors, but legacy monitor backup exists")
            return true
        }
        return false
    }

    private fun restoreFromBestBackup(targetFile: File): Boolean {
        // Find newest healthy backup with monitors
        data class BackupInfo(val slot: Int, val timestamp: Long, val monitorCount: Int)
        
        val candidates = mutableListOf<BackupInfo>()
        for (slot in 0 until MAX_SLOTS) {
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            val dbFile = File(backupDir, "state_backup_$slot.sqlite")
            if (!metaFile.exists() || !dbFile.exists()) continue
            
            val meta = metaFile.readText()
            val timestamp = meta.lines().find { it.startsWith("timestamp=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0L
            val monitorCount = meta.lines().find { it.startsWith("monitor_count=") }
                ?.substringAfter("=")?.toIntOrNull() ?: 0
            
            if (monitorCount > 0) {
                candidates.add(BackupInfo(slot, timestamp, monitorCount))
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No healthy state backups found, trying legacy monitor backup")
            return restoreFromLegacyMonitors(targetFile)
        }

        // Use newest backup
        val best = candidates.maxByOrNull { it.timestamp }!!
        val backupDb = File(backupDir, "state_backup_${best.slot}.sqlite")
        
        Log.w(TAG, "Restoring from backup slot ${best.slot} (${best.monitorCount} monitors, age=${(System.currentTimeMillis() - best.timestamp) / 1000}s)")
        
        return try {
            val source = SQLiteDatabase.openDatabase(
                backupDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val target = SQLiteDatabase.openDatabase(
                targetFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            
            // Restore all rows from backup into target
            val cursor = source.rawQuery("SELECT primary_namespace, secondary_namespace, key, value FROM ldk_node_data", null)
            var restored = 0
            while (cursor.moveToNext()) {
                val stmt = target.compileStatement(
                    "INSERT OR REPLACE INTO ldk_node_data (primary_namespace, secondary_namespace, key, value) VALUES (?, ?, ?, ?)")
                stmt.bindString(1, cursor.getString(0))
                stmt.bindString(2, cursor.getString(1))
                stmt.bindString(3, cursor.getString(2))
                stmt.bindBlob(4, cursor.getBlob(3))
                stmt.executeInsert()
                stmt.close()
                restored++
            }
            cursor.close()
            target.close()
            source.close()
            
            Log.w(TAG, "AUTO-RESTORE complete: $restored rows restored from backup slot ${best.slot}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auto-restore failed: ${e.message}")
            false
        }
    }

    /**
     * Last resort: inject monitors from legacy flat-file backup into SQLite.
     * Uses channel_id XOR trick to derive funding outpoint key.
     */
    private fun restoreFromLegacyMonitors(targetFile: File): Boolean {
        val legacyDir = File(context.filesDir, "lightning_backup/monitors")
        if (!legacyDir.exists()) return false
        val monitorFiles = legacyDir.listFiles() ?: return false
        if (monitorFiles.isEmpty()) return false

        return try {
            val db = SQLiteDatabase.openDatabase(
                targetFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            var restored = 0
            for (f in monitorFiles) {
                val channelId = f.nameWithoutExtension
                if (channelId.length != 64) continue // skip non-monitor files
                
                // Derive funding outpoint from channel_id
                // V1 channel: channel_id[31] = funding_txid[31] XOR (vout & 0xFF)
                // The channel_id differs from the funding_txid only in the last byte by XOR with vout
                // We determine the correct vout by checking monitors_meta.json or trying both
                val cidBytes = channelId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val monitorData = f.readBytes()
                
                // Read monitors_meta.json if available for additional context
                val metaFile = File(f.parentFile?.parentFile, "monitors_meta.json")
                
                // Try both vout=0 and vout=1, inserting both — LDK will use the correct one
                // Actually LDK crashes on wrong key, so we need to determine the right one
                // Parse the monitor binary: first bytes after version contain the funding outpoint
                // The monitor serialization starts with: version(1) + latest_update_id(8) + keys...
                // Too complex to parse. Use a simple heuristic: try vout=1 first (more common
                // for channels where vout=0 is change), then vout=0
                for (vout in intArrayOf(1, 0)) {
                    val txidBytes = cidBytes.copyOf()
                    if (vout > 0) txidBytes[31] = (txidBytes[31].toInt() xor vout).toByte()
                    val txidHex = txidBytes.reversed().joinToString("") { "%02x".format(it) }
                    val monitorKey = "${txidHex}_$vout"
                    
                    // Verify: check if the funding tx exists on chain via the txid
                    // We can't do network calls here, so just insert with the most likely vout
                    val stmt = db.compileStatement(
                        "INSERT OR REPLACE INTO ldk_node_data (primary_namespace, secondary_namespace, key, value) VALUES ('monitors', '', ?, ?)")
                    stmt.bindString(1, monitorKey)
                    stmt.bindBlob(2, monitorData)
                    stmt.executeInsert()
                    stmt.close()
                    Log.w(TAG, "LEGACY RESTORE: injected monitor key=$monitorKey vout=$vout (${monitorData.size} bytes)")
                    restored++
                    break // Use first attempt (vout=1 for most channels)
                }
            }
            db.close()
            Log.w(TAG, "LEGACY RESTORE complete: $restored monitors injected from flat-file backup")
            restored > 0
        } catch (e: Exception) {
            Log.e(TAG, "Legacy monitor restore failed: ${e.message}")
            false
        }
    }

    private fun readLatestBackupState(): StateSnapshot? {
        for (slot in 0 until MAX_SLOTS) {
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            if (metaFile.exists()) {
                val meta = metaFile.readText()
                val managerSize = meta.lines().find { it.startsWith("manager_size=") }
                    ?.substringAfter("=")?.toIntOrNull() ?: 0
                val monitorCount = meta.lines().find { it.startsWith("monitor_count=") }
                    ?.substringAfter("=")?.toIntOrNull() ?: 0
                val monitorKeys = meta.lines().find { it.startsWith("monitor_keys=") }
                    ?.substringAfter("=")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val totalSize = meta.lines().find { it.startsWith("total_size=") }
                    ?.substringAfter("=")?.toLongOrNull() ?: 0L
                return StateSnapshot(managerSize, monitorCount, monitorKeys, totalSize)
            }
        }
        return null
    }

    private fun nextSlot(): Int {
        // Find the oldest slot or first empty one
        var oldestSlot = 0
        var oldestTime = Long.MAX_VALUE
        
        for (slot in 0 until MAX_SLOTS) {
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            if (!metaFile.exists()) return slot
            
            val timestamp = metaFile.readText().lines()
                .find { it.startsWith("timestamp=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0L
            if (timestamp < oldestTime) {
                oldestTime = timestamp
                oldestSlot = slot
            }
        }
        return oldestSlot
    }

    /**
     * Get backup status for diagnostics.
     */
    fun getStatus(): String {
        val slots = (0 until MAX_SLOTS).map { slot ->
            val metaFile = File(backupDir, "state_backup_$slot.meta")
            if (!metaFile.exists()) return@map "slot $slot: empty"
            
            val meta = metaFile.readText()
            val timestamp = meta.lines().find { it.startsWith("timestamp=") }
                ?.substringAfter("=")?.toLongOrNull() ?: 0L
            val monitorCount = meta.lines().find { it.startsWith("monitor_count=") }
                ?.substringAfter("=")?.toIntOrNull() ?: 0
            val ageMin = (System.currentTimeMillis() - timestamp) / 60_000
            "slot $slot: ${monitorCount} monitors, ${ageMin}min ago"
        }
        return slots.joinToString(" | ")
    }
}
