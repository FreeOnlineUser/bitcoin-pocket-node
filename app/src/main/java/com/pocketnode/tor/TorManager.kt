package com.pocketnode.tor

import android.content.Context
import android.content.SharedPreferences
import com.pocketnode.lightning.WatchtowerNative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Manages the Arti SOCKS5 proxy lifecycle.
 *
 * One Arti instance, one SOCKS proxy, everything routes through it:
 * bitcoind (-proxy), HTTP calls, watchtower.
 *
 * Singleton via getInstance(context). Toggle persisted in SharedPreferences.
 */
class TorManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "pocketnode_prefs"
        private const val KEY_TOR_ENABLED = "tor_enabled"
        const val SOCKS_PORT: Int = 9050
        const val SOCKS_ADDR: String = "127.0.0.1:$SOCKS_PORT"

        /** Current proxy status. */
        val statusFlow: MutableStateFlow<TorStatus> = MutableStateFlow(TorStatus.OFF)

        /** Whether the user has enabled Tor. */
        val enabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)

        @Volatile
        private var instance: TorManager? = null

        fun getInstance(context: Context): TorManager {
            return instance ?: synchronized(this) {
                instance ?: TorManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class TorStatus {
        OFF,            // Proxy not running
        BOOTSTRAPPING,  // Arti bootstrapping Tor consensus
        RUNNING,        // SOCKS proxy accepting connections
        ERROR           // Failed to start
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Tor state/cache directories (persistent, survives app restarts)
    private val stateDir: String = File(context.filesDir, "tor_state").apply { mkdirs() }.absolutePath
    private val cacheDir: String = File(context.cacheDir, "tor_cache").apply { mkdirs() }.absolutePath

    init {
        enabledFlow.value = prefs.getBoolean(KEY_TOR_ENABLED, false)
    }

    /** Check if user has enabled Tor. */
    fun isEnabled(): Boolean = enabledFlow.value

    /** Check if proxy is currently running. */
    fun isRunning(): Boolean {
        return try {
            WatchtowerNative.INSTANCE.arti_is_running() == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Set whether Tor should be enabled.
     * Persists the preference. Call start()/stop() separately to apply.
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOR_ENABLED, enabled).apply()
        enabledFlow.value = enabled
    }

    /**
     * Start the Arti SOCKS5 proxy.
     * Blocks while Arti bootstraps (downloads Tor consensus).
     * Call from a background thread.
     *
     * @return true if proxy started successfully
     */
    fun start(): Boolean {
        if (isRunning()) {
            statusFlow.value = TorStatus.RUNNING
            return true
        }

        statusFlow.value = TorStatus.BOOTSTRAPPING

        return try {
            val result = WatchtowerNative.INSTANCE.arti_start_socks(
                stateDir,
                cacheDir,
                SOCKS_PORT.toShort()
            )
            if (result == 0) {
                statusFlow.value = TorStatus.RUNNING
                true
            } else {
                statusFlow.value = TorStatus.ERROR
                false
            }
        } catch (e: Exception) {
            statusFlow.value = TorStatus.ERROR
            false
        }
    }

    /**
     * Stop the Arti SOCKS5 proxy.
     */
    fun stop() {
        try {
            WatchtowerNative.INSTANCE.arti_stop_socks()
        } catch (_: Exception) {}
        statusFlow.value = TorStatus.OFF
    }

    /**
     * Start or stop based on the current enabled preference.
     * Call from a background thread (start blocks during bootstrap).
     */
    fun applyState(): Boolean {
        return if (isEnabled()) {
            start()
        } else {
            stop()
            true
        }
    }
}
