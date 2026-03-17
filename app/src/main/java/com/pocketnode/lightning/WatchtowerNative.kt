package com.pocketnode.lightning

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA bindings to libldk_watchtower_client.so.
 *
 * Provides the Noise_XK transport + LND wire protocol for pushing
 * encrypted justice blobs to an LND watchtower.
 */
interface WatchtowerNative : Library {

    /**
     * Connect to an LND watchtower and establish a session.
     *
     * @param address Tower address as "host:port"
     * @param towerPubkey 33-byte compressed secp256k1 public key
     * @param clientKey 32-byte private key for this client
     * @param maxUpdates Maximum updates per session
     * @param sweepFeeRate Fee rate in sat/kweight for justice txs
     * @return 0 on success, -1 on error
     */
    fun wtclient_connect(
        address: String,
        towerPubkey: ByteArray,
        clientKey: ByteArray,
        maxUpdates: Short,
        sweepFeeRate: Long
    ): Int

    /**
     * Send a single backup (encrypted justice blob) to the tower.
     *
     * @return 0 on success, -1 on error
     */
    fun wtclient_send_backup(
        breachTxid: ByteArray,
        revPubkey: ByteArray,
        localDelayPubkey: ByteArray,
        csvDelay: Int,
        sweepAddr: ByteArray,
        sweepAddrLen: Int,
        toLocalSig: ByteArray,
        toRemotePubkey: ByteArray,
        toRemoteSig: ByteArray
    ): Int

    /** Disconnect from the tower. */
    fun wtclient_disconnect()

    /** Check if connected. Returns 1 if connected, 0 if not. */
    fun wtclient_is_connected(): Int

    /** Get remaining update slots. Returns count or -1 if not connected. */
    fun wtclient_remaining_updates(): Int

    /**
     * Connect to an LND watchtower via embedded Tor (.onion address).
     * No SSH tunnel needed -- connects directly through the Tor network.
     *
     * @param onionAddress Tower .onion:port address
     * @param towerPubkey 33-byte compressed secp256k1 public key
     * @param clientKey 32-byte private key for this client
     * @param maxUpdates Maximum updates per session
     * @param sweepFeeRate Fee rate in sat/kweight for justice txs
     * @param stateDir Directory for Tor consensus cache (persistent)
     * @param cacheDir Directory for Tor temp cache
     * @return 0 on success, -1 on error
     */
    /** Test connection via TCP (handshake + Init only, no session). */
    fun wtclient_test_connection(
        address: String,
        towerPubkey: ByteArray,
        clientKey: ByteArray
    ): Int

    /** Test connection via Tor (handshake + Init only, no session). */
    fun wtclient_test_connection_tor(
        onionAddress: String,
        towerPubkey: ByteArray,
        clientKey: ByteArray,
        stateDir: String,
        cacheDir: String
    ): Int

    fun wtclient_connect_tor(
        onionAddress: String,
        towerPubkey: ByteArray,
        clientKey: ByteArray,
        maxUpdates: Short,
        sweepFeeRate: Long,
        stateDir: String,
        cacheDir: String
    ): Int

    // ── Watchtower via SOCKS ────────────────────────────────────────

    /**
     * Connect to a watchtower via the shared SOCKS5 proxy.
     * Preferred method for .onion connections.
     *
     * @param address Tower address (host:port or .onion:port)
     * @param towerPubkey 33-byte compressed secp256k1 public key
     * @param clientKey 32-byte private key
     * @param maxUpdates Maximum updates per session
     * @param sweepFeeRate Fee rate in sat/kweight
     * @param proxyAddr SOCKS5 proxy address (e.g. "127.0.0.1:9050")
     * @return 0 on success, -1 on error
     */
    fun wtclient_connect_socks(
        address: String,
        towerPubkey: ByteArray,
        clientKey: ByteArray,
        maxUpdates: Short,
        sweepFeeRate: Long,
        proxyAddr: String
    ): Int

    // ── Arti SOCKS5 Proxy ──────────────────────────────────────────

    /**
     * Start the Arti SOCKS5 proxy on 127.0.0.1:<port>.
     * All traffic (bitcoind, HTTP, watchtower) routes through this.
     *
     * @param stateDir Persistent Tor state directory
     * @param cacheDir Tor cache directory
     * @param port SOCKS5 listen port (e.g. 9050)
     * @return 0 on success, -1 on error
     */
    fun arti_start_socks(stateDir: String, cacheDir: String, port: Short): Int

    /**
     * Stop the Arti SOCKS5 proxy.
     * @return 0 on success, -1 if not running
     */
    fun arti_stop_socks(): Int

    /**
     * Check if the Arti SOCKS5 proxy is running.
     * @return 1 if running, 0 if not
     */
    fun arti_is_running(): Int

    companion object {
        val INSTANCE: WatchtowerNative by lazy {
            Native.load("ldk_watchtower_client", WatchtowerNative::class.java)
        }
    }
}
