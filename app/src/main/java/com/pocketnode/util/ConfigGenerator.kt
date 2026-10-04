package com.pocketnode.util

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * Generates and manages the bitcoin.conf configuration file.
 *
 * Creates a mobile-optimized config on first run with random RPC credentials.
 * Credentials are persisted so the app can reconnect across restarts.
 */
object ConfigGenerator {

    private const val TAG = "ConfigGenerator"
    private const val PREFS_NAME = "pocketnode_prefs"
    private const val KEY_RPC_USER = "rpc_user"
    private const val KEY_RPC_PASS = "rpc_password"

    /**
     * Ensure bitcoin.conf exists with mobile-optimized settings.
     * @return The bitcoind data directory.
     */
    fun ensureConfig(context: Context): File {
        val dataDir = File(context.filesDir, "bitcoin")
        dataDir.mkdirs()

        val confFile = File(dataDir, "bitcoin.conf")
        if (confFile.exists()) {
            Log.d(TAG, "bitcoin.conf already exists at ${confFile.absolutePath}")
            // Sync stored creds from existing conf if needed
            syncCredsFromConf(context, confFile)
            // Migrate: add persistmempool if missing (added in v0.6)
            migrateConfig(confFile)
            return dataDir
        }

        val (user, password) = getOrCreateCredentials(context)

        val config = """
            # Bitcoin Pocket Node — auto-generated config
            # Mobile-optimized settings for pruned node operation
            
            # Network
            server=1
            listen=1
            bind=127.0.0.1
            listenonion=0
            maxconnections=8
            whitelist=download@127.0.0.1
            
            # Storage — pruned to ~2GB
            prune=2048
            
            # Mempool — partial mempool for fee estimation + privacy cover traffic.
            # Max mode raises this to 150-300 by phone RAM; set your own value here
            # to keep it fixed.
            maxmempool=50
            persistmempool=1
            blockreconstructionextratxn=10
            
            # Performance
            dbcache=256
            
            # RPC — localhost only
            rpcbind=127.0.0.1
            rpcallowip=127.0.0.1
            rpcuser=$user
            rpcpassword=$password
            
            # REST API — used by LDK for block sync (avoids RPC HTTP client issues)
            rest=1
            
            # Wallet enabled for BWT (Electrum server) address tracking
            # disablewallet=0
            deprecatedrpc=create_bdb
            
            # BWT v0.2.4 compat: return warnings as string, not array (Core 28+)
            deprecatedrpc=warnings
        """.trimIndent()

        confFile.writeText(config)
        Log.i(TAG, "Generated bitcoin.conf at ${confFile.absolutePath}")

        return dataDir
    }

    /**
     * Read the stored RPC credentials.
     * @return Pair(user, password) or null if not yet generated.
     */
    fun readCredentials(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val user = prefs.getString(KEY_RPC_USER, null) ?: return null
        val password = prefs.getString(KEY_RPC_PASS, null) ?: return null
        return Pair(user, password)
    }

    /**
     * If an existing bitcoin.conf has different creds than what we have stored,
     * update our stored creds to match (e.g., picking up a test data dir).
     */
    private fun syncCredsFromConf(context: Context, confFile: File) {
        try {
            val lines = confFile.readLines()
            val confUser = lines.firstOrNull { it.startsWith("rpcuser=") }?.substringAfter("=")?.trim()
            val confPass = lines.firstOrNull { it.startsWith("rpcpassword=") }?.substringAfter("=")?.trim()
            if (confUser != null && confPass != null) {
                val stored = readCredentials(context)
                if (stored == null || stored.first != confUser || stored.second != confPass) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_RPC_USER, confUser)
                        .putString(KEY_RPC_PASS, confPass)
                        .apply()
                    Log.i(TAG, "Synced RPC credentials from existing bitcoin.conf")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync creds from conf", e)
        }
    }

    /**
     * One-time config migrations for existing installs.
     * Appends missing settings that were added after initial release.
     */
    private fun migrateConfig(confFile: File) {
        try {
            val content = confFile.readText()
            val additions = mutableListOf<String>()

            if (!content.contains("persistmempool")) {
                additions.add("persistmempool=1")
            }

            // Whitelist localhost so Lightning/Neutrino can always connect
            // even when maxconnections slots are full
            if (!content.contains("whitelist=download@127.0.0.1")) {
                additions.add("whitelist=download@127.0.0.1")
            }

            // BWT v0.2.4 compat: warnings as string not array (Core 28+)
            if (!content.contains("deprecatedrpc=warnings")) {
                additions.add("deprecatedrpc=warnings")
            }

            // Disable Tor listener so 8333 stays a normal port (Lightning whitelist works)
            if (!content.contains("listenonion=0")) {
                additions.add("listenonion=0")
            }

            // REST API for LDK block sync
            if (!content.contains("rest=1")) {
                additions.add("rest=1")
            }



            if (additions.isNotEmpty()) {
                confFile.appendText("\n# Added by config migration\n" +
                    additions.joinToString("\n") + "\n")
                Log.i(TAG, "Migrated bitcoin.conf: added ${additions.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Config migration failed", e)
        }
    }

    private fun getOrCreateCredentials(context: Context): Pair<String, String> {
        val existing = readCredentials(context)
        if (existing != null) return existing

        val user = "pocketnode"
        val password = generateSecureToken(32)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_RPC_USER, user)
            .putString(KEY_RPC_PASS, password)
            .apply()

        Log.i(TAG, "Generated new RPC credentials")
        return Pair(user, password)
    }

    private fun generateSecureToken(length: Int): String {
        // Exclude ambiguous characters: 0/O, 1/l/I
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
