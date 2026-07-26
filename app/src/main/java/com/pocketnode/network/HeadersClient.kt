package com.pocketnode.network

import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

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

    private val MAGIC_BYTES = byteArrayOf(0xF9.toByte(), 0xBE.toByte(), 0xB4.toByte(), 0xD9.toByte())
    private const val PROTOCOL_VERSION = 70016
    // Blend in with the dominant implementation: a distinct UA on short-lived
    // connections would fingerprint the phone as a wallet-carrying node.
    private const val USER_AGENT = "/Satoshi:29.0.0/"
    private const val GENESIS_HASH = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val PROBE_DEADLINE_MS = 60_000L
    private const val MAX_PEER_ATTEMPTS = 10

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

    private fun parseAddr(addr: String): Pair<String, Int>? {
        val idx = addr.lastIndexOf(':')
        if (idx <= 0) return null
        val host = addr.substring(0, idx).removePrefix("[").removeSuffix("]")
        val port = addr.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }
    // A correct locator never floods; this guards the peer-sends-from-genesis
    // case if the locator is somehow unknown to the peer (~2.4 MB worst case).
    private const val MAX_HEADERS_PER_PROBE = 30_000

    private val DNS_SEEDS = listOf(
        "seed.bitcoin.sipa.be",
        "dnsseed.bluematt.me",
        "seed.bitcoinstats.com",
        "seed.btc.petertodd.org"
    )

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
        try {
            val tips = rpc.call("getchaintips")
                ?.takeIf { !it.optBoolean("_rpc_error", false) }
                ?.optJSONArray("value")
            var bestHeight = -1L
            var bestHash: String? = null
            if (tips != null) {
                for (i in 0 until tips.length()) {
                    val tip = tips.getJSONObject(i)
                    if (tip.optString("status") == "invalid") continue
                    val height = tip.optLong("height", -1)
                    if (height > bestHeight) {
                        bestHeight = height
                        bestHash = tip.optString("hash")
                    }
                }
            }
            bestHash?.let { hashes.add(it) }
        } catch (_: Exception) {}

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
        return hashes.distinct().map { hexToInternal(it) }
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
        val socket = if (torActive) {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", TorManager.SOCKS_PORT))
            Socket(proxy)
        } else {
            Socket()
        }
        socket.use { s ->
            val target = if (torActive) {
                InetSocketAddress.createUnresolved(host, port)
            } else {
                InetSocketAddress(host, port)
            }
            s.connect(target, CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(s.getInputStream().buffered())
            val output = s.getOutputStream()

            handshake(input, output, startHeight)

            val collected = mutableListOf<ByteArray>()
            var currentLocator = locator
            val genesisInternal = hexToInternal(GENESIS_HASH)

            while (collected.size < MAX_HEADERS_PER_PROBE) {
                sendMsg(output, "getheaders", getHeadersPayload(currentLocator))
                val batch = awaitHeaders(input, output) ?: break
                if (batch.isEmpty()) break
                collected.addAll(batch)
                if (batch.size < 2000) break
                currentLocator = listOf(dsha256(batch.last()), genesisInternal)
            }
            return collected
        }
    }

    private fun handshake(input: DataInputStream, output: OutputStream, startHeight: Int) {
        sendMsg(output, "version", versionPayload(startHeight))
        var gotVersion = false
        var gotVerack = false
        var guard = 0
        while (!(gotVersion && gotVerack)) {
            if (guard++ > 20) throw IOException("handshake: too many messages")
            val (cmd, payload) = readMsg(input)
            when (cmd) {
                "version" -> {
                    gotVersion = true
                    sendMsg(output, "verack", ByteArray(0))
                }
                "verack" -> gotVerack = true
                "ping" -> sendMsg(output, "pong", payload)
                else -> {} // wtxidrelay, sendaddrv2, etc.
            }
        }
    }

    /** Read until a headers message arrives; answer pings, ignore the rest. */
    private fun awaitHeaders(input: DataInputStream, output: OutputStream): List<ByteArray>? {
        var guard = 0
        while (guard++ < 50) {
            val (cmd, payload) = try {
                readMsg(input)
            } catch (_: SocketTimeoutException) {
                return null
            }
            when (cmd) {
                "headers" -> return parseHeaders(payload)
                "ping" -> sendMsg(output, "pong", payload)
                "getheaders" -> sendMsg(output, "headers", byteArrayOf(0)) // we have nothing for them
                else -> {}
            }
        }
        return null
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

    private fun versionPayload(startHeight: Int): ByteArray {
        val ua = USER_AGENT.toByteArray()
        val buf = ByteBuffer.allocate(86 + ua.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putLong(0L)                          // services: none, we are a leech
        buf.putLong(System.currentTimeMillis() / 1000)
        buf.putLong(0L); buf.put(ByteArray(16)); buf.putShort(0)  // addr_recv (unused by peers)
        buf.putLong(0L); buf.put(ByteArray(16)); buf.putShort(0)  // addr_from
        buf.putLong(Random.nextLong())           // nonce
        buf.put(ua.size.toByte())                // varstr length (< 0xfd)
        buf.put(ua)
        buf.putInt(startHeight)
        buf.put(0)                               // relay=false: no tx invs (BIP 37)
        return buf.array()
    }

    private fun getHeadersPayload(locator: List<ByteArray>): ByteArray {
        val buf = ByteBuffer.allocate(4 + 1 + locator.size * 32 + 32).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.put(locator.size.toByte())           // varint (< 0xfd entries)
        locator.forEach { buf.put(it) }
        buf.put(ByteArray(32))                   // hash_stop: all
        return buf.array()
    }

    private fun parseHeaders(payload: ByteArray): List<ByteArray> {
        val (count, varIntSize) = readVarInt(payload, 0)
        var offset = varIntSize
        val headers = ArrayList<ByteArray>(count.toInt())
        repeat(count.toInt()) {
            if (offset + 80 > payload.size) return headers
            headers.add(payload.copyOfRange(offset, offset + 80))
            offset += 80
            val (_, txnVarIntSize) = readVarInt(payload, offset)  // always 0 in headers msgs
            offset += txnVarIntSize
        }
        return headers
    }

    private fun sendMsg(output: OutputStream, command: String, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC_BYTES)
        val cmd = ByteArray(12)
        command.toByteArray().copyInto(cmd)
        header.put(cmd)
        header.putInt(payload.size)
        header.put(dsha256(payload), 0, 4)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    private fun readMsg(input: DataInputStream): Pair<String, ByteArray> {
        val header = ByteArray(24)
        input.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); buf.get(magic)
        if (!magic.contentEquals(MAGIC_BYTES)) throw IOException("bad magic")
        val cmdBytes = ByteArray(12); buf.get(cmdBytes)
        val command = String(cmdBytes).trimEnd { it.code == 0 }
        val length = buf.int
        if (length < 0 || length > 4_000_000) throw IOException("bad payload length $length")
        buf.int // checksum: payload integrity is TCP's job for our purposes
        val payload = ByteArray(length)
        input.readFully(payload)
        return command to payload
    }

    internal fun readVarInt(data: ByteArray, offset: Int): Pair<Long, Int> {
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0xFD -> first.toLong() to 1
            first == 0xFD -> {
                val v = ((data[offset + 1].toInt() and 0xFF) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8)).toLong()
                v to 3
            }
            first == 0xFE -> {
                var v = 0L
                for (i in 0 until 4) v = v or ((data[offset + 1 + i].toLong() and 0xFF) shl (8 * i))
                v to 5
            }
            else -> {
                var v = 0L
                for (i in 0 until 8) v = v or ((data[offset + 1 + i].toLong() and 0xFF) shl (8 * i))
                v to 9
            }
        }
    }

    private fun dsha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    /** Display hex (big-endian) to internal byte order (little-endian). */
    internal fun hexToInternal(hex: String): ByteArray {
        val bytes = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            bytes[31 - i] = ((hi shl 4) or lo).toByte()
        }
        return bytes
    }
}
