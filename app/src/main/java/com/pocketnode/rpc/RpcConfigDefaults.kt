package com.pocketnode.rpc

import android.content.Context

/**
 * Default RPC configurations for testing and development
 */
object RpcConfigDefaults {
    
    /**
     * Initialize default RPC config if none exists
     */
    fun initializeDefaultsIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("bitcoin_rpc_config", Context.MODE_PRIVATE)
        
        // Only set defaults if no configuration exists
        if (!prefs.contains("rpc_host")) {
            val defaultConfig = RpcConfig(
                host = "127.0.0.1",
                port = 18443, // Default to regtest for development
                username = "user",
                password = "password",
                network = BitcoinNetwork.REGTEST
            )
            
            defaultConfig.save(context)
            android.util.Log.d("RpcConfigDefaults", "Initialized default RPC config: regtest on ${defaultConfig.host}:${defaultConfig.port}")
        }
    }
    
    /**
     * Create a test configuration for local regtest node
     */
    fun createRegtestConfig(): RpcConfig {
        return RpcConfig(
            host = "127.0.0.1",
            port = 18443,
            username = "user",
            password = "password",
            network = BitcoinNetwork.REGTEST
        )
    }
    
    /**
     * Create a test configuration for local testnet node
     */
    fun createTestnetConfig(): RpcConfig {
        return RpcConfig(
            host = "127.0.0.1",
            port = 18332,
            username = "user",
            password = "password",
            network = BitcoinNetwork.TESTNET
        )
    }
}