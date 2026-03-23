package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Static Channel Backup (SCB): stores minimal channel info needed for recovery.
 * On channel loss, the user can connect to the peer and request force-close.
 * 
 * Unlike full state backups, SCB always works regardless of LDK session context.
 * The peer sees the reconnection, detects the state mismatch, and force-closes.
 *
 * Flow:
 * 1. On channel open: save peer pubkey + funding txid/vout + addresses
 * 2. On channel close: remove entry
 * 3. On recovery: user selects channel → connect to peer → peer force-closes
 */
class StaticChannelBackup(private val context: Context) {

    companion object {
        private const val TAG = "SCB"
        private const val STORAGE_DIR = "lightning"
        private const val BACKUP_FILE = "channel_backups.json"
    }

    data class ChannelBackupEntry(
        val channelId: String,
        val peerPubkey: String,
        val peerAlias: String,
        val fundingTxid: String,
        val fundingVout: Int,
        val capacitySats: Long,
        val peerAddresses: List<String>,
        val openedAt: Long // timestamp
    )

    private val backupFile = File(context.filesDir, "$STORAGE_DIR/$BACKUP_FILE")

    /**
     * Save a channel to the backup. Called on ChannelPending or ChannelReady.
     */
    fun saveChannel(
        channelId: String,
        peerPubkey: String,
        peerAlias: String,
        fundingTxid: String,
        fundingVout: Int,
        capacitySats: Long,
        peerAddresses: List<String>
    ) {
        val entries = loadAll().toMutableList()
        // Remove existing entry for same channel
        entries.removeAll { it.channelId == channelId }
        entries.add(ChannelBackupEntry(
            channelId = channelId,
            peerPubkey = peerPubkey,
            peerAlias = peerAlias,
            fundingTxid = fundingTxid,
            fundingVout = fundingVout,
            capacitySats = capacitySats,
            peerAddresses = peerAddresses,
            openedAt = System.currentTimeMillis()
        ))
        save(entries)
        Log.i(TAG, "Saved SCB for channel ${channelId.take(12)} with ${peerAlias.ifEmpty { peerPubkey.take(16) }}")
    }

    /**
     * Remove a channel from backup. Called on ChannelClosed when the close is final.
     */
    fun removeChannel(channelId: String) {
        val entries = loadAll().toMutableList()
        val removed = entries.removeAll { it.channelId == channelId }
        if (removed) {
            save(entries)
            Log.i(TAG, "Removed SCB for channel ${channelId.take(12)}")
        }
    }

    /**
     * Get all backed-up channels. Used by recovery UI.
     */
    fun loadAll(): List<ChannelBackupEntry> {
        if (!backupFile.exists()) return emptyList()
        return try {
            val json = JSONArray(backupFile.readText())
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                ChannelBackupEntry(
                    channelId = obj.getString("channelId"),
                    peerPubkey = obj.getString("peerPubkey"),
                    peerAlias = obj.optString("peerAlias", ""),
                    fundingTxid = obj.getString("fundingTxid"),
                    fundingVout = obj.getInt("fundingVout"),
                    capacitySats = obj.getLong("capacitySats"),
                    peerAddresses = (0 until obj.getJSONArray("peerAddresses").length())
                        .map { j -> obj.getJSONArray("peerAddresses").getString(j) },
                    openedAt = obj.optLong("openedAt", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SCB: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if we have backup entries that don't match current active channels.
     * These are channels that were lost and can be recovered.
     */
    fun getLostChannels(activeChannelIds: Set<String>): List<ChannelBackupEntry> {
        return loadAll().filter { it.channelId !in activeChannelIds }
    }

    /**
     * Export backup to a file (for external storage / sharing).
     */
    fun exportTo(outputFile: File) {
        if (backupFile.exists()) {
            backupFile.copyTo(outputFile, overwrite = true)
            Log.i(TAG, "SCB exported to ${outputFile.absolutePath}")
        }
    }

    private fun save(entries: List<ChannelBackupEntry>) {
        val json = JSONArray()
        entries.forEach { entry ->
            json.put(JSONObject().apply {
                put("channelId", entry.channelId)
                put("peerPubkey", entry.peerPubkey)
                put("peerAlias", entry.peerAlias)
                put("fundingTxid", entry.fundingTxid)
                put("fundingVout", entry.fundingVout)
                put("capacitySats", entry.capacitySats)
                put("peerAddresses", JSONArray(entry.peerAddresses))
                put("openedAt", entry.openedAt)
            })
        }
        backupFile.parentFile?.mkdirs()
        backupFile.writeText(json.toString(2))
    }
}
