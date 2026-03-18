package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.WatchtowerJusticeBlob
import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bridges LDK watchtower data to an LND watchtower server.
 *
 * Flow:
 * 1. Drain justice blobs from ldk-node (captured by WatchtowerPersister)
 * 2. Encrypt each blob using XChaCha20-Poly1305 (breach txid as key)
 * 3. SSH tunnel to Umbrel, connect to LND tower on localhost
 * 4. Push encrypted blobs via LND watchtower wire protocol
 *
 * The LND tower stores encrypted blobs indexed by hint (first 16 bytes of
 * breach txid). If the counterparty broadcasts a revoked commitment, the
 * tower sees the txid, decrypts the blob, and broadcasts the justice tx.
 */
class WatchtowerBridge(private val context: Context) {

    companion object {
        private const val TAG = "WatchtowerBridge"

        // LND watchtower wire message types
        private const val MSG_INIT: Int = 600
        private const val MSG_CREATE_SESSION: Int = 16
        private const val MSG_CREATE_SESSION_REPLY: Int = 17
        private const val MSG_STATE_UPDATE: Int = 18
        private const val MSG_STATE_UPDATE_REPLY: Int = 19

        // Blob type: anchor channels (type 1)
        private const val BLOB_TYPE_ANCHOR: Int = 1

        // Fee rate bounds (sat/kweight)
        private const val MIN_SWEEP_FEE_RATE: Long = 1000   // LND minimum (~4 sat/vB)
        private const val DEFAULT_SWEEP_FEE_RATE: Long = 2500 // ~10 sat/vB fallback
        private const val MAX_SWEEP_FEE_RATE: Long = 250000   // ~1000 sat/vB cap

        // Recreate session if current fee estimate drifts >50% from session rate
        private const val FEE_DRIFT_THRESHOLD: Double = 0.5

        // JusticeKit V0 plaintext size: 274 bytes
        // revocation_pubkey(33) + local_delay_pubkey(33) + csv_delay(4) +
        // sweep_address_len(2) + sweep_address(max ~34) + to_local_sig(64) + to_remote_sig(64)
        // Padded/fixed to 274
        private const val JUSTICE_KIT_V0_SIZE = 274

        // Encrypted blob: 24 (nonce) + 274 (plaintext) + 16 (MAC) = 314
        private const val ENCRYPTED_BLOB_SIZE = 314
    }

    // Tracks the fee rate used for the current session
    private var sessionFeeRate: Long = 0

    /**
     * Estimate sweep fee rate from local bitcoind via estimatesmartfee.
     * Uses confirmation target of 6 blocks (justice txs have CSV delay, so not urgent).
     * Returns fee rate in sat/kweight, clamped to [MIN, MAX].
     */
    private fun estimateSweepFeeRate(): Long {
        return try {
            val prefs = context.getSharedPreferences("bitcoin_prefs", Context.MODE_PRIVATE)
            val rpcPort = prefs.getInt("rpc_port", 8332)
            val rpcUser = prefs.getString("rpc_user", "pocketnode") ?: "pocketnode"
            val rpcPass = prefs.getString("rpc_password", "") ?: ""
            val rpc = BitcoinRpcClient(rpcUser, rpcPass, "127.0.0.1", rpcPort)

            val result = runBlocking {
                rpc.call("estimatesmartfee", JSONArray().put(6))
            }
            val feeRateBtcPerKb = result?.optDouble("feerate", -1.0) ?: -1.0

            if (feeRateBtcPerKb <= 0) {
                Log.w(TAG, "estimatesmartfee returned no estimate, using default")
                return DEFAULT_SWEEP_FEE_RATE
            }

            // Convert BTC/kB to sat/kweight:
            // BTC/kB * 100_000_000 = sat/kB, then / 4 = sat/kweight
            val satPerKw = (feeRateBtcPerKb * 100_000_000 / 4).toLong()
            val clamped = satPerKw.coerceIn(MIN_SWEEP_FEE_RATE, MAX_SWEEP_FEE_RATE)

            Log.i(TAG, "Estimated sweep fee: ${clamped} sat/kw (${clamped * 4 / 1000} sat/vB)")
            clamped
        } catch (e: Exception) {
            Log.w(TAG, "Fee estimation failed: ${e.message}, using default")
            DEFAULT_SWEEP_FEE_RATE
        }
    }

    /**
     * Check if the current fee environment has drifted enough to warrant
     * creating a new tower session with an updated fee rate.
     */
    private fun shouldRecreateSession(): Boolean {
        if (sessionFeeRate == 0L) return false // No active session
        val currentEstimate = estimateSweepFeeRate()
        val drift = Math.abs(currentEstimate - sessionFeeRate).toDouble() / sessionFeeRate
        if (drift > FEE_DRIFT_THRESHOLD) {
            Log.i(TAG, "Fee drift ${(drift * 100).toInt()}% exceeds threshold " +
                    "(session: $sessionFeeRate, current: $currentEstimate). Recreating session.")
            return true
        }
        return false
    }

    /**
     * Drain all pending justice blobs and push them to the configured LND tower.
     * Call this periodically (e.g. after each payment or channel update).
     *
     * Automatically estimates sweep fee rate from local bitcoind and recreates
     * the tower session if fees have drifted more than 50%.
     *
     * @return number of blobs successfully pushed, or -1 on error
     */
    /**
     * Initialize the watchtower bridge: create client key and verify tower connectivity.
     * Call once at startup (after LDK node is built) so the bridge is ready when blobs arrive.
     * Returns true if tower is reachable, false otherwise (key is still created for later).
     */
    fun initialize(): Boolean {
        val prefs = context.getSharedPreferences("watchtower_prefs", Context.MODE_PRIVATE)
        val towerPubKey = prefs.getString("tower_pubkey", null)
        val towerOnion = prefs.getString("tower_onion", null)
        val towerPort = prefs.getInt("tower_port", 9911)
        val sshHost = prefs.getString("ssh_host", null)
        val sshPort = prefs.getInt("ssh_port", 22)
        val sshUser = prefs.getString("ssh_user", null)

        if (towerPubKey == null) {
            Log.i(TAG, "initialize: no tower configured, skipping")
            return false
        }

        // Create client key if it doesn't exist (the key fix)
        val clientKey = getOrCreateClientKey()
        Log.i(TAG, "initialize: client key ready (${clientKey.size} bytes)")

        val towerPubKeyBytes = hexToBytes(towerPubKey)
        val sweepFeeRate = estimateSweepFeeRate()
        val native = WatchtowerNative.INSTANCE

        // Test Tor connectivity
        val torOk = if (towerOnion != null && towerOnion.endsWith(".onion")) {
            try {
                val ok = connectViaTor(native, towerOnion, towerPort, towerPubKeyBytes, clientKey, sweepFeeRate)
                if (ok) {
                    native.wtclient_disconnect()
                    sessionFeeRate = sweepFeeRate
                    Log.i(TAG, "initialize: Tor connection to tower verified ✓")
                }
                ok
            } catch (e: Exception) {
                Log.w(TAG, "initialize: Tor connection test failed: ${e.message}")
                false
            }
        } else false

        // Test SSH fallback if Tor failed
        val sshOk = if (!torOk && sshHost != null && sshUser != null) {
            try {
                val result = connectViaSsh(native, sshHost, sshPort, sshUser, towerOnion ?: "127.0.0.1",
                    towerPort, towerPubKeyBytes, clientKey, sweepFeeRate, prefs)
                val session = result.first
                val port = result.second
                val ok = result.third
                if (ok) {
                    native.wtclient_disconnect()
                    sessionFeeRate = sweepFeeRate
                    Log.i(TAG, "initialize: SSH tunnel to tower verified ✓")
                }
                try {
                    if (port > 0) session?.delPortForwardingL(port)
                    session?.disconnect()
                } catch (_: Exception) {}
                ok
            } catch (e: Exception) {
                Log.w(TAG, "initialize: SSH connection test failed: ${e.message}")
                false
            }
        } else false

        val reachable = torOk || sshOk
        if (!reachable) {
            Log.w(TAG, "initialize: tower not reachable (client key created for later)")
        }
        return reachable
    }

    fun drainAndPush(node: Node): Int {
        val blobs = node.watchtowerDrainJusticeBlobs()
        if (blobs.isEmpty()) {
            Log.d(TAG, "No pending justice blobs to push")
            return 0
        }

        Log.i(TAG, "Draining ${blobs.size} justice blob(s) for watchtower push")

        // Load tower config
        val prefs = context.getSharedPreferences("watchtower_prefs", Context.MODE_PRIVATE)
        val towerPubKey = prefs.getString("tower_pubkey", null)
        val towerOnion = prefs.getString("tower_onion", null)
        val towerPort = prefs.getInt("tower_port", 9911)
        val sshHost = prefs.getString("ssh_host", null)
        val sshPort = prefs.getInt("ssh_port", 22)
        val sshUser = prefs.getString("ssh_user", null)

        if (towerPubKey == null) {
            Log.w(TAG, "Watchtower not configured, skipping push")
            return -1
        }

        val clientKey = getOrCreateClientKey()
        val towerPubKeyBytes = hexToBytes(towerPubKey)
        val sweepFeeRate = estimateSweepFeeRate()
        val native = WatchtowerNative.INSTANCE

        // Try Tor first (direct .onion, no SSH needed), then fall back to SSH tunnel
        val connected = if (towerOnion != null && towerOnion.endsWith(".onion")) {
            connectViaTor(native, towerOnion, towerPort, towerPubKeyBytes, clientKey, sweepFeeRate)
        } else {
            false
        }

        // Fall back to SSH tunnel if Tor failed or no onion address
        var sshSession: Session? = null
        var localPort = -1
        val sshConnected = if (!connected && sshHost != null && sshUser != null) {
            try {
                val result = connectViaSsh(native, sshHost, sshPort, sshUser, towerOnion ?: "127.0.0.1",
                    towerPort, towerPubKeyBytes, clientKey, sweepFeeRate, prefs)
                sshSession = result.first
                localPort = result.second
                result.third
            } catch (e: Exception) {
                Log.e(TAG, "SSH tunnel failed: ${e.message}")
                false
            }
        } else {
            false
        }

        if (!connected && !sshConnected) {
            Log.w(TAG, "Cannot reach watchtower (Tor and SSH both failed), storing locally")
            return encryptAndStoreLocally(blobs)
        }

        if (connected || sshConnected) sessionFeeRate = sweepFeeRate

        // Push blobs
        var pushed = 0
        try {
            pushed = pushBlobs(native, blobs)
            native.wtclient_disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Watchtower push failed: ${e.message}, storing locally")
            pushed = encryptAndStoreLocally(blobs)
        } finally {
            try {
                if (localPort > 0) sshSession?.delPortForwardingL(localPort)
                sshSession?.disconnect()
            } catch (_: Exception) {}
        }

        return pushed
    }

    /**
     * Connect to tower via Tor.
     * Prefers shared SOCKS proxy (if running), falls back to legacy embedded Arti.
     */
    private fun connectViaTor(
        native: WatchtowerNative,
        onionHost: String,
        port: Int,
        towerPubKey: ByteArray,
        clientKey: ByteArray,
        sweepFeeRate: Long
    ): Boolean {
        // Prefer shared SOCKS proxy when running
        if (com.pocketnode.tor.TorManager.statusFlow.value == com.pocketnode.tor.TorManager.TorStatus.RUNNING) {
            Log.i(TAG, "Connecting to tower via SOCKS proxy: $onionHost:$port")
            val result = native.wtclient_connect_socks(
                "$onionHost:$port",
                towerPubKey,
                clientKey,
                1024,
                sweepFeeRate,
                com.pocketnode.tor.TorManager.SOCKS_ADDR
            )
            if (result == 0) {
                Log.i(TAG, "Connected to LND tower via SOCKS proxy (fee rate: $sweepFeeRate sat/kw)")
                return true
            }
            Log.w(TAG, "SOCKS proxy connection failed, trying legacy Tor")
        }

        // Legacy: embedded Arti per-connection (when SOCKS proxy not running)
        Log.i(TAG, "Attempting legacy Tor connection to $onionHost:$port")
        val torStateDir = java.io.File(context.filesDir, "tor_state").apply { mkdirs() }.absolutePath
        val torCacheDir = java.io.File(context.cacheDir, "tor_cache").apply { mkdirs() }.absolutePath

        val result = native.wtclient_connect_tor(
            "$onionHost:$port",
            towerPubKey,
            clientKey,
            1024,
            sweepFeeRate,
            torStateDir,
            torCacheDir
        )

        if (result == 0) {
            Log.i(TAG, "Connected to LND tower via legacy Tor (fee rate: $sweepFeeRate sat/kw)")
            return true
        }
        Log.w(TAG, "Tor connection failed, will try SSH fallback")
        return false
    }

    /**
     * Connect to tower via SSH tunnel (existing approach).
     * Returns (sshSession, localPort, connected).
     */
    private fun connectViaSsh(
        native: WatchtowerNative,
        sshHost: String,
        sshPort: Int,
        sshUser: String,
        towerOnion: String,
        towerPort: Int,
        towerPubKey: ByteArray,
        clientKey: ByteArray,
        sweepFeeRate: Long,
        prefs: android.content.SharedPreferences
    ): Triple<Session, Int, Boolean> {
        val jsch = JSch()
        val keyFile = java.io.File(context.filesDir, "ssh_key")
        if (keyFile.exists()) {
            jsch.addIdentity(keyFile.absolutePath)
        }

        val sshSession = jsch.getSession(sshUser, sshHost, sshPort)
        sshSession.setConfig("StrictHostKeyChecking", "no")
        val sshPassword = prefs.getString("ssh_password", null)
        if (sshPassword != null) {
            sshSession.setPassword(sshPassword)
        }
        sshSession.connect(15000)

        val localPort = sshSession.setPortForwardingL(0, towerOnion, towerPort)
        Log.i(TAG, "SSH tunnel established, local port $localPort -> $towerOnion:$towerPort")

        val connectResult = native.wtclient_connect(
            "127.0.0.1:$localPort",
            towerPubKey,
            clientKey,
            1024,
            sweepFeeRate
        )

        if (connectResult == 0) {
            Log.i(TAG, "Connected to LND tower via SSH tunnel (fee rate: $sweepFeeRate sat/kw)")
        }

        return Triple(sshSession, localPort, connectResult == 0)
    }

    /**
     * Push all blobs to the connected tower. Returns number pushed.
     */
    private fun pushBlobs(native: WatchtowerNative, blobs: List<WatchtowerJusticeBlob>): Int {
        var pushed = 0
        for (blob in blobs) {
            val result = native.wtclient_send_backup(
                blob.breachTxid,
                blob.revocationPubkey,
                blob.localDelayPubkey,
                blob.csvDelay.toInt(),
                blob.sweepAddress,
                blob.sweepAddress.size,
                blob.toLocalSig,
                blob.toRemotePubkey,
                blob.toRemoteSig
            )
            if (result == 0) {
                pushed++
                Log.d(TAG, "Pushed blob to tower (${pushed}/${blobs.size})")
            } else {
                Log.w(TAG, "Tower rejected blob, storing locally")
                val encrypted = encryptJusticeBlob(blob)
                if (encrypted != null) storeEncryptedBlob(blob.breachTxid, encrypted)
            }
        }
        val remaining = native.wtclient_remaining_updates()
        Log.i(TAG, "Pushed $pushed blob(s) to tower ($remaining slots remaining)")
        return pushed
    }

    /**
     * Fallback: encrypt all blobs and store locally when tower is unreachable.
     */
    private fun encryptAndStoreLocally(blobs: List<WatchtowerJusticeBlob>): Int {
        var count = 0
        for (blob in blobs) {
            val encrypted = encryptJusticeBlob(blob)
            if (encrypted != null) {
                storeEncryptedBlob(blob.breachTxid, encrypted)
                count++
            }
        }
        Log.i(TAG, "Stored $count blob(s) locally (tower unreachable)")
        return count
    }

    /**
     * Get or create a persistent 32-byte secp256k1 private key for this client.
     * Used as our identity when authenticating with the tower via Noise_XK.
     */
    private fun getOrCreateClientKey(): ByteArray {
        val keyFile = java.io.File(context.filesDir, "watchtower_client_key")
        if (keyFile.exists()) {
            return keyFile.readBytes()
        }
        val key = ByteArray(32)
        java.security.SecureRandom().nextBytes(key)
        keyFile.writeBytes(key)
        return key
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    /**
     * Encrypt a justice blob using XChaCha20-Poly1305.
     * Key = breach_txid (32 bytes), nonce = random 24 bytes.
     *
     * Output format: [24-byte nonce][encrypted 274 bytes][16-byte MAC]
     */
    private fun encryptJusticeBlob(blob: WatchtowerJusticeBlob): ByteArray? {
        try {
            // Build JusticeKit V0 plaintext (274 bytes)
            val plaintext = buildJusticeKitV0(blob) ?: return null

            // Generate 24-byte random nonce
            val nonce = ByteArray(24)
            java.security.SecureRandom().nextBytes(nonce)

            // XChaCha20-Poly1305 encrypt
            // Android API 28+ has ChaCha20-Poly1305 but not XChaCha20
            // We use the HChaCha20 construction to derive the subkey
            val subkey = hChaCha20(blob.breachTxid, nonce.sliceArray(0..15))
            val subNonce = ByteArray(12)
            // XChaCha20 sub-nonce: [0x00, 0x00, 0x00, 0x00, nonce[16..23]]
            System.arraycopy(nonce, 16, subNonce, 4, 8)

            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val keySpec = SecretKeySpec(subkey, "ChaCha20")
            val ivSpec = IvParameterSpec(subNonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

            val ciphertext = cipher.doFinal(plaintext)

            // Output: nonce + ciphertext (which includes MAC)
            val output = ByteArray(24 + ciphertext.size)
            System.arraycopy(nonce, 0, output, 0, 24)
            System.arraycopy(ciphertext, 0, output, 24, ciphertext.size)

            return output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt justice blob: ${e.message}")
            return null
        }
    }

    /**
     * Build LND JusticeKit V0 plaintext (274 bytes).
     */
    private fun buildJusticeKitV0(blob: WatchtowerJusticeBlob): ByteArray? {
        val buf = ByteBuffer.allocate(JUSTICE_KIT_V0_SIZE)
        buf.order(ByteOrder.BIG_ENDIAN)

        // Revocation pubkey (33 bytes)
        if (blob.revocationPubkey.size != 33) {
            Log.e(TAG, "Invalid revocation pubkey size: ${blob.revocationPubkey.size}")
            return null
        }
        buf.put(blob.revocationPubkey)

        // Local delay pubkey (33 bytes)
        if (blob.localDelayPubkey.size != 33) {
            Log.e(TAG, "Invalid local delay pubkey size: ${blob.localDelayPubkey.size}")
            return null
        }
        buf.put(blob.localDelayPubkey)

        // CSV delay (4 bytes, big-endian)
        buf.putInt(blob.csvDelay.toInt())

        // Sweep address length (2 bytes) + sweep address (variable, padded to 34)
        val sweepAddr = blob.sweepAddress
        buf.putShort(sweepAddr.size.toShort())
        buf.put(sweepAddr)
        // Pad to 34 bytes
        if (sweepAddr.size < 34) {
            buf.put(ByteArray(34 - sweepAddr.size))
        }

        // to_local signature (64 bytes)
        if (blob.toLocalSig.size != 64) {
            Log.e(TAG, "Invalid to_local sig size: ${blob.toLocalSig.size}")
            return null
        }
        buf.put(blob.toLocalSig)

        // to_remote signature (64 bytes)
        if (blob.toRemoteSig.size != 64) {
            Log.e(TAG, "Invalid to_remote sig size: ${blob.toRemoteSig.size}")
            return null
        }
        buf.put(blob.toRemoteSig)

        // Remaining padding (if any)
        while (buf.position() < JUSTICE_KIT_V0_SIZE) {
            buf.put(0)
        }

        return buf.array()
    }

    /**
     * HChaCha20: derive a 256-bit subkey for XChaCha20.
     * Takes a 256-bit key and 128-bit (16-byte) input, returns 256-bit subkey.
     */
    private fun hChaCha20(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(input.size == 16) { "Input must be 16 bytes" }

        // ChaCha20 state initialization
        val state = IntArray(16)

        // Constants "expand 32-byte k"
        state[0] = 0x61707865.toInt()
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574

        // Key (8 words)
        for (i in 0..7) {
            state[4 + i] = ByteBuffer.wrap(key, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        // Input (4 words) replaces counter + nonce
        for (i in 0..3) {
            state[12 + i] = ByteBuffer.wrap(input, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        // 20 rounds of ChaCha
        val working = state.copyOf()
        for (round in 0 until 10) {
            // Column rounds
            quarterRound(working, 0, 4, 8, 12)
            quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14)
            quarterRound(working, 3, 7, 11, 15)
            // Diagonal rounds
            quarterRound(working, 0, 5, 10, 15)
            quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13)
            quarterRound(working, 3, 4, 9, 14)
        }

        // Output: first 4 words + last 4 words (NOT added to state, unlike ChaCha20)
        val subkey = ByteArray(32)
        val out = ByteBuffer.wrap(subkey).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..3) out.putInt(working[i])
        for (i in 12..15) out.putInt(working[i])

        return subkey
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun Int.rotateLeft(bits: Int): Int =
        (this shl bits) or (this ushr (32 - bits))

    /**
     * Compute the 16-byte hint from a breach txid (first 16 bytes).
     */
    fun computeHint(breachTxid: ByteArray): ByteArray {
        require(breachTxid.size == 32) { "Breach txid must be 32 bytes" }
        return breachTxid.sliceArray(0..15)
    }

    /**
     * Store an encrypted blob locally for later push or inspection.
     */
    private fun storeEncryptedBlob(breachTxid: ByteArray, encrypted: ByteArray) {
        val dir = java.io.File(context.filesDir, "watchtower_blobs")
        if (!dir.exists()) dir.mkdirs()

        val hint = computeHint(breachTxid).joinToString("") { "%02x".format(it) }
        val file = java.io.File(dir, "$hint.blob")
        file.writeBytes(encrypted)
        Log.d(TAG, "Stored encrypted blob: ${file.name} (${encrypted.size} bytes)")
    }
}
