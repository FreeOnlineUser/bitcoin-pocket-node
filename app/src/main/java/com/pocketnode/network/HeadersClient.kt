package com.pocketnode.network

import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.InetAddress

/**
 * Minimal Bitcoin P2P client that fetches block headers without enabling
 * bitcoind's networking. Used on metered connections: a header is 80 bytes,
 * so staying tip-aware costs KBs where downloading blocks costs MBs.
 *
 * Fetched headers are fed to bitcoind via submitheader, which validates
 * proof-of-work — the resulting "behind by N blocks" signal is trustless.
 */
object HeadersClient {
    private const val TAG = "HeadersClient"

    private const val GENESIS_HASH = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
    private const val PROBE_DEADLINE_MS = 60_000L
    private const val MAX_PEER_ATTEMPTS = 10
    // A correct locator never floods; this guards the peer-sends-from-genesis
    // case if the locator is somehow unknown to the peer (~2.4 MB worst case).
    private const val MAX_HEADERS_PER_PROBE = 30_000

    private val DNS_SEEDS = listOf(
        "seed.bitcoin.sipa.be",
        "dnsseed.bluematt.me",
        "seed.bitcoinstats.com",
        "seed.btc.petertodd.org"
    )

    // The peer that served the last successful probe. Addrman entries are
    // mostly stale; a peer that answered 15 minutes ago almost always
    // answers again, so one connect usually settles the whole probe.
    @Volatile
    private var lastGoodPeer: Pair<String, Int>? = null

    // Peers bitcoind was actually connected to at the end of the last full
    // burst — verified live, unlike addrman gossip (observed ~90% dead).
    @Volatile
    private var livePeers: List<Pair<String, Int>> = emptyList()

    /** Feed peers seen on a live bitcoind connection; they head the candidate list. */
    fun rememberLivePeers(addrs: List<String>) {
        // Raw IPv6 hosts are dropped: phones frequently sit on ULA-only or
        // broken v6, where every attempt burns a full connect timeout.
        val parsed = addrs.mapNotNull { parseAddr(it) }.filter { !it.first.contains(":") }
        if (parsed.isNotEmpty()) livePeers = parsed.take(8)
    }

    /** Verified-live candidates for other P2P fetchers (SpvFetcher). */
    internal fun knownGoodPeers(): List<Pair<String, Int>> =
        (listOfNotNull(lastGoodPeer) + livePeers).distinct()

    private fun parseAddr(addr: String): Pair<String, Int>? {
        val idx = addr.lastIndexOf(':')
        if (idx <= 0) return null
        val host = addr.substring(0, idx).removePrefix("[").removeSuffix("]")
        val port = addr.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }

    data class ProbeResult(
        val success: Boolean,
        val headersFetched: Int = 0,
        val peer: String? = null,
        val error: String? = null
    )

    /**
     * Fetch new headers from one P2P peer and submit them to bitcoind.
     * bitcoind's network can stay off; only this short-lived socket touches
     * the network. Returns failure if no peer could be reached — callers
     * should fall back to a full sync in that case.
     */
    suspend fun probe(rpc: BitcoinRpcClient): ProbeResult = withContext(Dispatchers.IO) {
        val startHeight = rpc.getBlockchainInfo()
            ?.takeIf { !it.optBoolean("_rpc_error", false) }
            ?.optLong("blocks", 0L) ?: return@withContext ProbeResult(false, error = "RPC not ready")

        val locator = buildLocator(rpc)
        if (locator.isEmpty()) return@withContext ProbeResult(false, error = "no locator")

        val torActive = TorManager.enabledFlow.value &&
            TorManager.statusFlow.value == TorManager.TorStatus.RUNNING
        val fromAddrman = peerCandidates(rpc, torActive)
        val candidates = (listOfNotNull(lastGoodPeer) + livePeers + fromAddrman).distinct()
        if (candidates.isEmpty()) return@withContext ProbeResult(false, error = "no peer candidates")

        val deadline = System.currentTimeMillis() + PROBE_DEADLINE_MS
        var lastError: String? = null

        for ((host, port) in candidates.take(MAX_PEER_ATTEMPTS)) {
            if (System.currentTimeMillis() > deadline) break
            try {
                val headers = fetchHeaders(host, port, locator, startHeight.toInt(), torActive)
                val accepted = submitAll(rpc, headers)
                lastGoodPeer = host to port
                return@withContext ProbeResult(true, accepted, "$host:$port")
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.d(TAG, "Peer $host:$port failed: $lastError")
            }
        }
        ProbeResult(false, error = lastError ?: "all peers failed")
    }

    // ------------------------------------------------------------------
    // Peer discovery
    // ------------------------------------------------------------------

    /** Candidate peers from bitcoind's addrman (works with network off), DNS seeds as fallback. */
    private suspend fun peerCandidates(rpc: BitcoinRpcClient, torActive: Boolean): List<Pair<String, Int>> {
        data class Candidate(val host: String, val port: Int, val lastSeen: Long)
        val pool = mutableListOf<Candidate>()
        try {
            val res = rpc.call("getnodeaddresses", JSONArray().put(128))
            val arr = res?.takeIf { !it.optBoolean("_rpc_error", false) }?.optJSONArray("value")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val network = entry.optString("network")
                    val address = entry.optString("address")
                    val port = entry.optInt("port", 8333)
                    val lastSeen = entry.optLong("time", 0)
                    if (address.isEmpty()) continue
                    // No ipv6: phones are often ULA-only/broken-v6 and every
                    // v6 candidate then burns a full connect timeout.
                    val usable = if (torActive) {
                        network == "onion" || network == "ipv4"
                    } else {
                        network == "ipv4"
                    }
                    if (usable) pool.add(Candidate(address, port, lastSeen))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getnodeaddresses failed: ${e.message}")
        }
        // Addrman is mostly stale gossip. Recently-seen entries on the
        // standard port are far likelier to answer; oddball ports are a
        // last resort (and often not even mainnet).
        val (standard, oddPort) = pool.partition { it.port == 8333 }
        val out = (standard.sortedByDescending { it.lastSeen }.take(16).shuffled() +
            oddPort.sortedByDescending { it.lastSeen }.take(4).shuffled())
            .map { it.host to it.port }
            .toMutableList()

        // DNS seeds only on clearnet: resolving them through the system would
        // leak the lookup outside Tor.
        if (out.isEmpty() && !torActive) {
            for (seed in DNS_SEEDS.shuffled()) {
                try {
                    InetAddress.getAllByName(seed).take(4).forEach { out.add(it.hostAddress to 8333) }
                    if (out.isNotEmpty()) break
                } catch (_: Exception) {}
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Locator
    // ------------------------------------------------------------------

    /**
     * Block locator, newest first: best known header (may be ahead of the
     * block tip), exponential back-off over the active chain, genesis last.
     */
    private suspend fun buildLocator(rpc: BitcoinRpcClient): List<ByteArray> {
        val hashes = mutableListOf<String>()

        // Best non-invalid header tip — avoids re-fetching headers bitcoind
        // already knows from a previous probe.
        bestHeaderTip(rpc)?.let { hashes.add(it.second) }

        // Exponential back-off over the active chain for reorg tolerance
        try {
            val info = rpc.getBlockchainInfo()?.takeIf { !it.optBoolean("_rpc_error", false) }
            val tip = info?.optLong("blocks", 0L) ?: 0L
            var height = tip
            var step = 1L
            while (height > 0 && hashes.size < 20) {
                val hash = rpc.call("getblockhash", JSONArray().put(height))?.optString("value")
                if (hash.isNullOrEmpty()) break
                hashes.add(hash)
                if (hashes.size > 10) step *= 2
                height -= step
            }
        } catch (_: Exception) {}

        hashes.add(GENESIS_HASH)
        return hashes.distinct().map { Wire.hexToInternal(it) }
    }

    /** Highest non-invalid chain tip as (height, hash), or null. */
    internal suspend fun bestHeaderTip(rpc: BitcoinRpcClient): Pair<Long, String>? {
        try {
            val tips = rpc.call("getchaintips")
                ?.takeIf { !it.optBoolean("_rpc_error", false) }
                ?.optJSONArray("value") ?: return null
            var bestHeight = -1L
            var bestHash: String? = null
            for (i in 0 until tips.length()) {
                val tip = tips.getJSONObject(i)
                if (tip.optString("status") == "invalid") continue
                val height = tip.optLong("height", -1)
                if (height > bestHeight) {
                    bestHeight = height
                    bestHash = tip.optString("hash")
                }
            }
            return bestHash?.let { bestHeight to it }
        } catch (_: Exception) {
            return null
        }
    }

    // ------------------------------------------------------------------
    // P2P
    // ------------------------------------------------------------------

    /** Connect, handshake, getheaders-loop. Returns raw 80-byte headers, oldest first. */
    private fun fetchHeaders(
        host: String,
        port: Int,
        locator: List<ByteArray>,
        startHeight: Int,
        torActive: Boolean
    ): List<ByteArray> {
        P2pSession(host, port, torActive).use { session ->
            session.connect(startHeight)

            val collected = mutableListOf<ByteArray>()
            var currentLocator = locator
            val genesisInternal = Wire.hexToInternal(GENESIS_HASH)

            while (collected.size < MAX_HEADERS_PER_PROBE) {
                session.send("getheaders", getHeadersPayload(currentLocator))
                val batch = session.await("headers")?.second?.let { parseHeaders(it) } ?: break
                if (batch.isEmpty()) break
                collected.addAll(batch)
                if (batch.size < 2000) break
                currentLocator = listOf(Wire.dsha256(batch.last()), genesisInternal)
            }
            return collected
        }
    }

    // ------------------------------------------------------------------
    // Submission
    // ------------------------------------------------------------------

    /** Submit headers to bitcoind in order. PoW is validated there; duplicates are no-ops. */
    private suspend fun submitAll(rpc: BitcoinRpcClient, headers: List<ByteArray>): Int {
        var accepted = 0
        var consecutiveFailures = 0
        for (header in headers) {
            val hex = header.joinToString("") { "%02x".format(it) }
            val res = rpc.call("submitheader", JSONArray().put(hex))
            if (res != null && !res.optBoolean("_rpc_error", false)) {
                accepted++
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures == 1) {
                    Log.w(TAG, "submitheader rejected: ${res?.optString("message") ?: "no response"}")
                }
                if (consecutiveFailures >= 5) {
                    Log.w(TAG, "submitheader: 5 consecutive failures, stopping (accepted $accepted)")
                    break
                }
            }
        }
        return accepted
    }

    // ------------------------------------------------------------------
    // Wire format
    // ------------------------------------------------------------------

    private fun getHeadersPayload(locator: List<ByteArray>): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(4 + 1 + locator.size * 32 + 32)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(Wire.PROTOCOL_VERSION)
        buf.put(locator.size.toByte())           // varint (< 0xfd entries)
        locator.forEach { buf.put(it) }
        buf.put(ByteArray(32))                   // hash_stop: all
        return buf.array()
    }

    private fun parseHeaders(payload: ByteArray): List<ByteArray> {
        val (count, varIntSize) = Wire.readVarInt(payload, 0)
        var offset = varIntSize
        val headers = ArrayList<ByteArray>(count.toInt())
        repeat(count.toInt()) {
            if (offset + 80 > payload.size) return headers
            headers.add(payload.copyOfRange(offset, offset + 80))
            offset += 80
            val (_, txnVarIntSize) = Wire.readVarInt(payload, offset)  // always 0 in headers msgs
            offset += txnVarIntSize
        }
        return headers
    }
}
