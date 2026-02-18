package com.pocketnode.rpc

import android.content.Context
import android.content.SharedPreferences

/**
 * Bitcoin RPC configuration management
 */
data class RpcConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8332,
    val username: String = "",
    val password: String = "",
    val network: BitcoinNetwork = BitcoinNetwork.MAINNET
) {
    companion object {
        private const val PREFS_NAME = "bitcoin_rpc_config"
        private const val KEY_HOST = "rpc_host"
        private const val KEY_PORT = "rpc_port"
        private const val KEY_USERNAME = "rpc_username"
        private const val KEY_PASSWORD = "rpc_password"
        private const val KEY_NETWORK = "bitcoin_network"

        /**
         * Load RPC config from SharedPreferences
         */
        fun load(context: Context): RpcConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return RpcConfig(
                host = prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
                port = prefs.getInt(KEY_PORT, 8332),
                username = prefs.getString(KEY_USERNAME, "") ?: "",
                password = prefs.getString(KEY_PASSWORD, "") ?: "",
                network = BitcoinNetwork.valueOf(prefs.getString(KEY_NETWORK, BitcoinNetwork.MAINNET.name) ?: BitcoinNetwork.MAINNET.name)
            )
        }
    }

    /**
     * Save RPC config to SharedPreferences
     */
    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .putString(KEY_NETWORK, network.name)
            .apply()
    }

    /**
     * Get default port for the network
     */
    fun getDefaultPort(): Int = when (network) {
        BitcoinNetwork.MAINNET -> 8332
        BitcoinNetwork.TESTNET -> 18332
        BitcoinNetwork.REGTEST -> 18443
    }
}

enum class BitcoinNetwork(val displayName: String) {
    MAINNET("Mainnet"),
    TESTNET("Testnet"),
    REGTEST("Regtest")
}