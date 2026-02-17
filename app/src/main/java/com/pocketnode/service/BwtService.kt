package com.pocketnode.service

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.util.ConfigGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages the BWT (Bitcoin Wallet Tracker) process lifecycle.
 * BWT provides an Electrum server interface to bitcoind, allowing
 * wallet apps like BlueWallet to connect to the local node.
 *
 * Runs as a child process alongside bitcoind (not a separate Android Service).
 * Started/stopped by BitcoindService after bitcoind is ready.
 */
class BwtService(private val context: Context) {

    companion object {
        private const val TAG = "BwtService"
        const val ELECTRUM_HOST = "127.0.0.1"
        const val ELECTRUM_PORT = 50001
        const val BWT_WALLET_NAME = "bwt"

        private val _state = MutableStateFlow(BwtState())
        val stateFlow: StateFlow<BwtState> = _state

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning
    }

    data class BwtState(
        val status: Status = Status.STOPPED,
        val electrumAddress: String = "$ELECTRUM_HOST:$ELECTRUM_PORT",
        val error: String? = null,
        val trackedAddresses: Int = 0
    ) {
        enum class Status { STOPPED, STARTING, RUNNING, ERROR }
    }

    private var bwtProcess: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start BWT, connecting to the local bitcoind.
     * Ensures the bwt wallet exists in bitcoind first.
     */
    fun start(saveState: Boolean = true) {
        if (_isRunning.value) return
        if (saveState) {
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("bwt_was_running", true).apply()
        }
        _state.value = BwtState(status = BwtState.Status.STARTING)

        scope.launch {
            try {
                // Ensure wallet exists
                ensureBwtWallet()

                // Find BWT binary
                val bwtPath = "${context.applicationInfo.nativeLibraryDir}/libbwt.so"
                val bwtFile = java.io.File(bwtPath)
                if (!bwtFile.exists()) {
                    _state.value = BwtState(status = BwtState.Status.ERROR, error = "BWT binary not found")
                    return@launch
                }

                // Get RPC credentials
                val creds = ConfigGenerator.readCredentials(context)
                if (creds == null) {
                    _state.value = BwtState(status = BwtState.Status.ERROR, error = "No RPC credentials")
                    return@launch
                }

                // Get saved xpubs/addresses from preferences
                val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                val xpubs = prefs.getStringSet("xpubs", emptySet()) ?: emptySet()
                val addresses = prefs.getStringSet("addresses", emptySet()) ?: emptySet()

                if (xpubs.isEmpty() && addresses.isEmpty()) {
                    _state.value = BwtState(status = BwtState.Status.ERROR,
                        error = "No xpubs or addresses configured. Add them in Connect Wallet settings.")
                    return@launch
                }

                // Build command
                val args = mutableListOf(
                    bwtPath,
                    "--bitcoind-url", "http://127.0.0.1:8332",
                    "--bitcoind-auth", "${creds.first}:${creds.second}",
                    "--bitcoind-wallet", BWT_WALLET_NAME,
                    "--electrum-addr", "$ELECTRUM_HOST:$ELECTRUM_PORT",
                    "--no-wait-sync",
                    "--rescan-since", "now",
                    "-W", // Create wallet if missing
                    "--no-startup-banner",
                    "-v"
                )

                // Add xpubs
                for (xpub in xpubs) {
                    args.add("--xpub")
                    args.add(xpub)
                }
                // Add addresses
                for (addr in addresses) {
                    args.add("--address")
                    args.add(addr)
                }

                Log.i(TAG, "Starting BWT with ${xpubs.size} xpubs, ${addresses.size} addresses")

                val pb = ProcessBuilder(args)
                pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                pb.redirectErrorStream(true)

                val process = pb.start()
                bwtProcess = process
                _isRunning.value = true
                _state.value = BwtState(
                    status = BwtState.Status.RUNNING,
                    trackedAddresses = xpubs.size + addresses.size
                )

                Log.i(TAG, "BWT started, Electrum on $ELECTRUM_HOST:$ELECTRUM_PORT")

                // Log output
                launch {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line -> Log.d(TAG, "bwt: $line") }
                        }
                    } catch (_: Exception) {}
                }

                // Wait for exit
                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                _isRunning.value = false
                Log.i(TAG, "BWT exited with code $exitCode")

                if (exitCode != 0) {
                    _state.value = BwtState(status = BwtState.Status.ERROR,
                        error = "BWT exited with code $exitCode")
                } else {
                    _state.value = BwtState(status = BwtState.Status.STOPPED)
                }
            } catch (e: Exception) {
                _isRunning.value = false
                Log.e(TAG, "BWT start failed", e)
                _state.value = BwtState(status = BwtState.Status.ERROR, error = e.message)
            }
        }
    }

    fun stop(saveState: Boolean = true) {
        if (saveState) {
            context.getSharedPreferences("pocketnode_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("bwt_was_running", false).apply()
        }
        bwtProcess?.let { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                Log.i(TAG, "BWT stopped")
            }
        }
        bwtProcess = null
        _isRunning.value = false
        _state.value = BwtState(status = BwtState.Status.STOPPED)
        scope.cancel()
    }

    private suspend fun ensureBwtWallet() {
        val creds = ConfigGenerator.readCredentials(context) ?: return
        val rpc = BitcoinRpcClient(creds.first, creds.second)

        // Check if wallet already loaded
        val wallets = rpc.call("listwallets")
        if (wallets != null) {
            val arr = wallets.optJSONArray("result") ?: wallets as? org.json.JSONArray
            // Check various response shapes
            val walletList = mutableListOf<String>()
            if (wallets.has("result")) {
                val resultArr = wallets.optJSONArray("result")
                if (resultArr != null) {
                    for (i in 0 until resultArr.length()) {
                        walletList.add(resultArr.getString(i))
                    }
                }
            }
            if (walletList.contains(BWT_WALLET_NAME)) {
                Log.d(TAG, "BWT wallet already loaded")
                return
            }
        }

        // Try loading existing wallet
        val loadResult = rpc.call("loadwallet", org.json.JSONArray().apply { put(BWT_WALLET_NAME) })
        if (loadResult != null && !loadResult.has("error")) {
            Log.i(TAG, "Loaded existing BWT wallet")
            return
        }

        // Create new wallet (legacy for importmulti compatibility)
        Log.i(TAG, "Creating BWT wallet...")
        val createResult = rpc.call("createwallet", org.json.JSONArray().apply {
            put(BWT_WALLET_NAME)  // name
            put(true)             // disable_private_keys
            put(true)             // blank
            put("")               // passphrase
            put(false)            // avoid_reuse
            put(false)            // descriptors (false = legacy, needed for importmulti)
        })
        if (createResult != null) {
            Log.i(TAG, "Created BWT wallet")
        }
    }

    // -- Saved config helpers --

    object SavedConfig {
        fun getXpubs(context: Context): Set<String> {
            return context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                .getStringSet("xpubs", emptySet()) ?: emptySet()
        }

        fun getAddresses(context: Context): Set<String> {
            return context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
                .getStringSet("addresses", emptySet()) ?: emptySet()
        }

        fun saveXpub(context: Context, xpub: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("xpubs", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(xpub)
            prefs.edit().putStringSet("xpubs", existing).apply()
        }

        fun saveAddress(context: Context, address: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("addresses", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.add(address)
            prefs.edit().putStringSet("addresses", existing).apply()
        }

        fun removeXpub(context: Context, xpub: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("xpubs", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.remove(xpub)
            prefs.edit().putStringSet("xpubs", existing).apply()
        }

        fun removeAddress(context: Context, address: String) {
            val prefs = context.getSharedPreferences("bwt_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("addresses", emptySet())?.toMutableSet() ?: mutableSetOf()
            existing.remove(address)
            prefs.edit().putStringSet("addresses", existing).apply()
        }
    }
}
