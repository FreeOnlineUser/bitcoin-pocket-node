package com.pocketnode.network

import android.content.Context
import android.util.Log
import com.pocketnode.rpc.BitcoinRpcClient
import com.pocketnode.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SPV payment detection inside deferred blocks.
 *
 * While blocks wait for WiFi, this fetches BIP 158 filters (~25 KB/block)
 * for the headers-only region, matches them against wallet scripts, and
 * downloads only blocks that match (~2 MB each, rare). A downloaded block
 * is verified against the PoW header chain (hash + merkle root), giving
 * SPV-grade confirmation now; bitcoind still fully validates on WiFi.
 *
 * Fail-safe: any error just means the confirmation waits for WiFi.
 */
object SpvFetcher {
    private const val TAG = "SpvFetcher"

    private const val MAX_FILTERS_PER_SCAN = 990   // BIP 157 caps getcfilters at 1000
    private const val MAX_BLOCK_FETCHES_PER_SCAN = 6
    private const val SCAN_DEADLINE_MS = 45_000L
    private const val MSG_WITNESS_BLOCK = 2 or 0x40000000

    // sipa/bluematt seeders support service-bit filtering by subdomain:
    // x49 = NODE_NETWORK | NODE_WITNESS | NODE_COMPACT_FILTERS (0x49)
    private val FILTER_DNS_SEEDS = listOf(
        "x49.seed.bitcoin.sipa.be",
        "x49.dnsseed.bluematt.me"
    )

    @Volatile
    private var lastGoodFilterPeer: Pair<String, Int>? = null

    /**
     * Scan deferred blocks for payments to [watched] (script, address) pairs.
     * Returns the number of newly detected payments; 0 on any failure.
     */
    suspend fun scan(
        context: Context,
        rpc: BitcoinRpcClient,
        watched: List<Pair<ByteArray, String>>
    ): Int = withContext(Dispatchers.IO) {
        try {
            val deadline = System.currentTimeMillis() + SCAN_DEADLINE_MS

            val info = rpc.getBlockchainInfo()?.takeIf { !it.optBoolean("_rpc_error", false) }
                ?: return@withContext 0
            val blocks = info.optLong("blocks", 0L)
            val (tipHeight, tipHash) = HeadersClient.bestHeaderTip(rpc) ?: return@withContext 0
            if (tipHeight <= blocks) return@withContext 0

            val alreadyScanned = SpvTracker.lastScannedHeight(context)
            var fromHeight = maxOf(blocks + 1, alreadyScanned + 1)
            if (fromHeight > tipHeight) return@withContext 0
            if (tipHeight - fromHeight + 1 > MAX_FILTERS_PER_SCAN) {
                Log.w(TAG, "Scan range clamped: ${tipHeight - fromHeight + 1} blocks behind")
                fromHeight = tipHeight - MAX_FILTERS_PER_SCAN + 1
            }

            // Walk the header chain tip -> fromHeight so every cfilter response
            // can be checked against the exact expected hash at its height.
            val hashAtHeight = HashMap<Long, String>()
            var walkHash = tipHash
            var walkHeight = tipHeight
            while (walkHeight >= fromHeight) {
                hashAtHeight[walkHeight] = walkHash
                val hdr = rpc.call("getblockheader", JSONArray().put(walkHash))
                    ?.takeIf { !it.optBoolean("_rpc_error", false) } ?: return@withContext 0
                walkHash = hdr.optString("previousblockhash", "")
                if (walkHash.isEmpty()) break
                walkHeight--
            }

            val torActive = TorManager.enabledFlow.value &&
                TorManager.statusFlow.value == TorManager.TorStatus.RUNNING

            val session = openFilterPeer(rpc, blocks.toInt(), torActive) ?: run {
                Log.i(TAG, "No filter-serving peer reachable; SPV scan skipped")
                return@withContext 0
            }

            session.use { s ->
                // getcfilters: type(1) + start_height(u32 LE) + stop_hash(32)
                val req = ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN)
                req.put(0)
                req.putInt(fromHeight.toInt())
                req.put(Wire.hexToInternal(tipHash))
                s.send("getcfilters", req.array())

                val expectedCount = (tipHeight - fromHeight + 1).toInt()
                val matchedHashes = mutableListOf<Pair<Long, String>>() // height, hash hex
                var received = 0
                var height = fromHeight

                while (received < expectedCount && System.currentTimeMillis() < deadline) {
                    val payload = s.await("cfilter")?.second ?: break
                    if (payload.size < 34 || payload[0].toInt() != 0) break
                    val blockHashInternal = payload.copyOfRange(1, 33)
                    val blockHashHex = Wire.internalToHex(blockHashInternal)
                    val (filterLen, varIntSize) = Wire.readVarInt(payload, 33)
                    val filterBytes = payload.copyOfRange(33 + varIntSize,
                        (33 + varIntSize + filterLen).toInt().coerceAtMost(payload.size))

                    // Responses arrive in height order; the hash must match the
                    // header chain we already PoW-validated.
                    if (hashAtHeight[height] != blockHashHex) {
                        Log.w(TAG, "cfilter hash mismatch at $height; aborting scan")
                        return@withContext 0
                    }
                    if (Bip158.matches(filterBytes, blockHashInternal, watched.map { it.first })) {
                        matchedHashes.add(height to blockHashHex)
                    }
                    received++
                    height++
                }
                if (received == 0) return@withContext 0
                val scannedThrough = fromHeight + received - 1

                var found = 0
                for ((matchHeight, matchHash) in matchedHashes.take(MAX_BLOCK_FETCHES_PER_SCAN)) {
                    if (System.currentTimeMillis() > deadline) break
                    val payments = fetchAndExtract(s, matchHeight, matchHash, watched)
                    if (payments.isNotEmpty()) {
                        found += SpvTracker.recordPayments(context, payments)
                    }
                }

                SpvTracker.setLastScannedHeight(context, scannedThrough)
                Log.i(TAG, "SPV scan: $received filter(s) from ${matchedHashes.size} match(es), " +
                    "$found new payment(s), scanned through $scannedThrough")
                return@withContext found
            }
        } catch (e: Exception) {
            Log.w(TAG, "SPV scan failed: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext 0
        }
        @Suppress("UNREACHABLE_CODE")
        return@withContext 0
    }

    /** Connect to a peer advertising NODE_COMPACT_FILTERS. */
    private suspend fun openFilterPeer(
        rpc: BitcoinRpcClient,
        startHeight: Int,
        torActive: Boolean
    ): P2pSession? {
        val candidates = mutableListOf<Pair<String, Int>>()
        lastGoodFilterPeer?.let { candidates.add(it) }
        candidates.addAll(HeadersClient.knownGoodPeers())

        // addrman entries that advertise the filter service bit
        try {
            val res = rpc.call("getnodeaddresses", JSONArray().put(256))
            val arr = res?.takeIf { !it.optBoolean("_rpc_error", false) }?.optJSONArray("value")
            if (arr != null) {
                val withFilters = mutableListOf<Pair<String, Int>>()
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val services = entry.optLong("services", 0)
                    val network = entry.optString("network")
                    if (services and P2pSession.NODE_COMPACT_FILTERS == 0L) continue
                    val usable = network == "ipv4" || (torActive && network == "onion")
                    if (usable) withFilters.add(entry.optString("address") to entry.optInt("port", 8333))
                }
                candidates.addAll(withFilters.shuffled().take(8))
            }
        } catch (_: Exception) {}

        // Seeder-filtered DNS as last resort (clearnet only)
        if (!torActive) {
            for (seed in FILTER_DNS_SEEDS) {
                try {
                    InetAddress.getAllByName(seed).take(3).forEach { candidates.add(it.hostAddress to 8333) }
                    break
                } catch (_: Exception) {}
            }
        }

        for ((host, port) in candidates.distinct().take(8)) {
            try {
                val session = P2pSession(host, port, torActive)
                session.connect(startHeight)
                if (session.peerServices and P2pSession.NODE_COMPACT_FILTERS != 0L) {
                    lastGoodFilterPeer = host to port
                    return session
                }
                session.close()
            } catch (e: Exception) {
                Log.d(TAG, "Filter peer $host:$port failed: ${e.message}")
            }
        }
        return null
    }

    /** Download one block, verify hash + merkle root, extract watched outputs. */
    private fun fetchAndExtract(
        session: P2pSession,
        height: Long,
        blockHashHex: String,
        watched: List<Pair<ByteArray, String>>
    ): List<SpvTracker.SpvPayment> {
        val inv = ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN)
        inv.put(1)                       // count varint
        inv.putInt(MSG_WITNESS_BLOCK)
        inv.put(Wire.hexToInternal(blockHashHex))
        session.send("getdata", inv.array())

        val block = session.await("block")?.second ?: return emptyList()
        if (block.size < 81) return emptyList()

        // The block must be the one the PoW header chain committed to
        val actualHash = Wire.internalToHex(Wire.dsha256(block, 0, 80))
        if (actualHash != blockHashHex) throw IOException("block hash mismatch")

        val blockTime = ByteBuffer.wrap(block, 68, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val merkleRoot = block.copyOfRange(36, 68)

        val payments = mutableListOf<SpvTracker.SpvPayment>()
        val txids = mutableListOf<ByteArray>()

        val (txCount, headVarInt) = Wire.readVarInt(block, 80)
        var offset = 80 + headVarInt
        repeat(txCount.toInt()) {
            val tx = parseTx(block, offset) ?: throw IOException("tx parse failed")
            txids.add(tx.txid)
            for ((vout, out) in tx.outputs.withIndex()) {
                for ((script, address) in watched) {
                    if (out.script.contentEquals(script)) {
                        payments.add(SpvTracker.SpvPayment(
                            txid = Wire.internalToHex(tx.txid),
                            vout = vout,
                            valueSats = out.valueSats,
                            address = address,
                            height = height,
                            blockHash = blockHashHex,
                            blockTime = blockTime
                        ))
                    }
                }
            }
            offset = tx.end
        }

        if (!computeMerkleRoot(txids).contentEquals(merkleRoot)) {
            throw IOException("merkle root mismatch")
        }
        return payments
    }

    // ------------------------------------------------------------------
    // Block/tx parsing
    // ------------------------------------------------------------------

    private class TxOut(val valueSats: Long, val script: ByteArray)
    private class ParsedTx(val txid: ByteArray, val outputs: List<TxOut>, val end: Int)

    /** Parse one tx; txid excludes witness data (BIP 141). */
    private fun parseTx(data: ByteArray, start: Int): ParsedTx? {
        try {
            var cursor = start + 4  // version
            val segwit = data[cursor].toInt() == 0 && data[cursor + 1].toInt() == 1
            if (segwit) cursor += 2
            val bodyStart = cursor  // vin count onward

            val (vinCount, vinVar) = Wire.readVarInt(data, cursor); cursor += vinVar
            repeat(vinCount.toInt()) {
                cursor += 36
                val (scriptLen, sv) = Wire.readVarInt(data, cursor)
                cursor += sv + scriptLen.toInt() + 4
            }

            val outputs = mutableListOf<TxOut>()
            val (voutCount, voutVar) = Wire.readVarInt(data, cursor); cursor += voutVar
            repeat(voutCount.toInt()) {
                val value = ByteBuffer.wrap(data, cursor, 8).order(ByteOrder.LITTLE_ENDIAN).long
                cursor += 8
                val (scriptLen, sv) = Wire.readVarInt(data, cursor); cursor += sv
                outputs.add(TxOut(value, data.copyOfRange(cursor, cursor + scriptLen.toInt())))
                cursor += scriptLen.toInt()
            }
            val bodyEnd = cursor  // end of vouts

            if (segwit) {
                repeat(vinCount.toInt()) {
                    val (stackCount, cv) = Wire.readVarInt(data, cursor); cursor += cv
                    repeat(stackCount.toInt()) {
                        val (itemLen, iv) = Wire.readVarInt(data, cursor)
                        cursor += iv + itemLen.toInt()
                    }
                }
            }
            val end = cursor + 4  // locktime

            val txid = if (segwit) {
                val stripped = ByteArray(4 + (bodyEnd - bodyStart) + 4)
                data.copyInto(stripped, 0, start, start + 4)
                data.copyInto(stripped, 4, bodyStart, bodyEnd)
                data.copyInto(stripped, 4 + (bodyEnd - bodyStart), end - 4, end)
                Wire.dsha256(stripped)
            } else {
                Wire.dsha256(data, start, end - start)
            }
            return ParsedTx(txid, outputs, end)
        } catch (_: Exception) {
            return null
        }
    }

    internal fun computeMerkleRoot(txids: List<ByteArray>): ByteArray {
        if (txids.isEmpty()) return ByteArray(32)
        var level = txids
        while (level.size > 1) {
            val next = mutableListOf<ByteArray>()
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else left
                next.add(Wire.dsha256(left + right))
                i += 2
            }
            level = next
        }
        return level[0]
    }
}
