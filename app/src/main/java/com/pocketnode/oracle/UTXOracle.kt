/**
 * UTXOracle v9.1 — Kotlin Port for Bitcoin Pocket Node
 *
 * Ported from UTXOracle.py (Python, ~1800 lines) to a single Kotlin class.
 * This implements Steps 5–11 of the UTXOracle algorithm:
 *   Step 5:  Initialize 2400-bin histogram (200 bins/decade, 10^-6 to 10^6 BTC)
 *   Step 6:  Parse raw block hex, extract tx outputs with filters
 *   Step 7:  Smooth round BTC amounts, normalize histogram, cap extremes
 *   Step 8:  Construct smooth (Gaussian) + spike (hard-coded USD) stencils
 *   Step 9:  Slide stencils to find rough price estimate
 *   Step 10: Assign rough fiat prices to outputs, filter round BTC
 *   Step 11: Iterative center-finding for exact average price
 *
 * All constants, stencil values, bin counts, and filters are identical to Python.
 */

package com.pocketnode.oracle

import com.pocketnode.rpc.BitcoinRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class OracleResult(
    val price: Int,
    val date: String,
    val blockRange: IntRange,
    val outputCount: Int,
    val deviation: Double
)

class UTXOracle(private val rpc: BitcoinRpcClient) {

    private val _progress = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64)
    val progress: SharedFlow<String> = _progress

    private fun emit(msg: String) { _progress.tryEmit(msg) }

    // ── RPC helpers ──────────────────────────────────────────────────────

    private suspend fun rpcCall(method: String, vararg params: Any): JSONObject {
        val jsonParams = org.json.JSONArray().apply { params.forEach { put(it) } }
        return rpc.call(method, jsonParams)
            ?: throw RuntimeException("RPC call '$method' returned null")
    }

    private suspend fun rpcCallLong(method: String, vararg params: Any): JSONObject {
        val jsonParams = org.json.JSONArray().apply { params.forEach { put(it) } }
        return rpc.callLongRunning(method, jsonParams, timeoutMs = 120_000)
            ?: throw RuntimeException("RPC call '$method' returned null")
    }

    /** Extract scalar string result from RPC response.
     *  The RPC client wraps non-JSONObject results as {"value": ...} */
    private fun extractResult(resp: JSONObject): String {
        return when {
            resp.has("value") -> resp.get("value").toString()
            resp.has("result") && !resp.isNull("result") -> resp.get("result").toString()
            else -> resp.toString()
        }
    }

    private suspend fun getBlockHash(height: Int): String =
        extractResult(rpcCall("getblockhash", height)).trim().replace("\"", "")

    private suspend fun getBlockHeader(hash: String): JSONObject {
        // RPC client returns the result JSONObject directly for object results
        return rpcCall("getblockheader", hash, true)
    }

    private suspend fun getRawBlock(hash: String): String =
        extractResult(rpcCallLong("getblock", hash, 0)).trim().replace("\"", "")

    private suspend fun getBlockTime(height: Int): Pair<Long, String> {
        val hash = getBlockHash(height)
        val header = getBlockHeader(hash)
        return header.getLong("time") to hash
    }

    // ── Byte parsing helpers (mirrors Python struct reads) ───────────────

    private class BlockStream(private val data: ByteArray) {
        var pos: Int = 0

        fun read(n: Int): ByteArray {
            val slice = data.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }

        fun readByte(): Int {
            val b = data[pos].toInt() and 0xFF
            pos++
            return b
        }

        fun readUInt16LE(): Int {
            val b = read(2)
            return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        }

        fun readUInt32LE(): Long {
            val b = read(4)
            return (b[0].toLong() and 0xFF) or
                    ((b[1].toLong() and 0xFF) shl 8) or
                    ((b[2].toLong() and 0xFF) shl 16) or
                    ((b[3].toLong() and 0xFF) shl 24)
        }

        fun readUInt64LE(): Long {
            val b = read(8)
            var v = 0L
            for (i in 0..7) v = v or ((b[i].toLong() and 0xFF) shl (i * 8))
            return v
        }

        fun readVarint(): Long {
            val first = readByte()
            return when {
                first < 0xFD -> first.toLong()
                first == 0xFD -> readUInt16LE().toLong()
                first == 0xFE -> readUInt32LE()
                else -> readUInt64LE()
            }
        }

        fun tell(): Int = pos
        fun seek(p: Int) { pos = p }
    }

    private fun encodeVarint(i: Long): ByteArray {
        return when {
            i < 0xFD -> byteArrayOf(i.toByte())
            i <= 0xFFFF -> byteArrayOf(0xFD.toByte()) + ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(i.toShort()).array()
            i <= 0xFFFFFFFFL -> byteArrayOf(0xFE.toByte()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i.toInt()).array()
            else -> byteArrayOf(0xFF.toByte()) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(i).array()
        }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))

    private fun computeTxid(rawTx: ByteArray): String {
        val stream = BlockStream(rawTx)
        val version = stream.read(4)

        // Check segwit
        val markerFlag = stream.read(2)
        val isSegwit = markerFlag[0] == 0x00.toByte() && markerFlag[1] == 0x01.toByte()

        val stripped: ByteArray
        if (!isSegwit) {
            stripped = rawTx
        } else {
            val buf = mutableListOf<Byte>()
            buf.addAll(version.toList())

            // Inputs
            val inputCount = stream.readVarint()
            buf.addAll(encodeVarint(inputCount).toList())
            for (j in 0 until inputCount.toInt()) {
                buf.addAll(stream.read(32).toList()) // prev txid
                buf.addAll(stream.read(4).toList())  // vout
                val scriptLen = stream.readVarint()
                buf.addAll(encodeVarint(scriptLen).toList())
                buf.addAll(stream.read(scriptLen.toInt()).toList())
                buf.addAll(stream.read(4).toList())  // sequence
            }

            // Outputs
            val outputCount = stream.readVarint()
            buf.addAll(encodeVarint(outputCount).toList())
            for (j in 0 until outputCount.toInt()) {
                buf.addAll(stream.read(8).toList()) // value
                val scriptLen = stream.readVarint()
                buf.addAll(encodeVarint(scriptLen).toList())
                buf.addAll(stream.read(scriptLen.toInt()).toList())
            }

            // Skip witness
            for (j in 0 until inputCount.toInt()) {
                val stackCount = stream.readVarint()
                for (k in 0 until stackCount.toInt()) {
                    val itemLen = stream.readVarint()
                    stream.read(itemLen.toInt())
                }
            }

            // Locktime
            buf.addAll(stream.read(4).toList())
            stripped = buf.toByteArray()
        }

        val hash = doubleSha256(stripped)
        return hash.reversedArray().joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // ── Main algorithm ──────────────────────────────────────────────────

    suspend fun getPrice(date: String? = null): OracleResult = withContext(Dispatchers.IO) {
        runAlgorithm(dateMode = true, requestedDate = date)
    }

    suspend fun getPriceRecentBlocks(): OracleResult = withContext(Dispatchers.IO) {
        runAlgorithm(dateMode = false, requestedDate = null)
    }

    private suspend fun runAlgorithm(dateMode: Boolean, requestedDate: String?): OracleResult {
        emit("Connecting to node...")

        // Get current block height
        val blockCount = extractResult(rpcCall("getblockcount")).trim().toInt()
        val blockCountConsensus = blockCount - 6

        val topHash = getBlockHash(blockCountConsensus)
        val topHeader = getBlockHeader(topHash)
        val latestTimeSeconds = topHeader.getLong("time")

        // Determine blocks needed
        val blockNums = mutableListOf<Int>()
        val blockHashes = mutableListOf<String>()
        val blockTimes = mutableListOf<Long>()

        val secondsInADay = 86400L
        var priceDateStr = ""

        if (!dateMode) {
            // Block mode: last 144 blocks
            emit("Finding last 144 blocks...")
            val blockFinish = blockCount
            val blockStart = blockFinish - 144
            for (bn in blockStart until blockFinish) {
                val (time, hash) = getBlockTime(bn)
                blockNums.add(bn)
                blockHashes.add(hash)
                blockTimes.add(time)
            }
            priceDateStr = "recent-blocks"
        } else {
            // Date mode
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

            val targetDate: Calendar
            if (requestedDate == null) {
                // Yesterday UTC
                cal.timeInMillis = latestTimeSeconds * 1000
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_MONTH, -1)
                targetDate = cal
            } else {
                // Parse YYYY-MM-DD or YYYY/MM/DD
                val parts = requestedDate.replace("-", "/").split("/")
                cal.set(Calendar.YEAR, parts[0].toInt())
                cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                targetDate = cal
            }

            val priceDaySeconds = targetDate.timeInMillis / 1000
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            priceDateStr = sdf.format(targetDate.time)

            emit("Finding blocks for $priceDateStr...")

            // Estimate block height
            val secondsSincePriceDay = latestTimeSeconds - priceDaySeconds
            val blocksAgoEstimate = (144.0 * secondsSincePriceDay / secondsInADay).roundToInt()
            var estimate = blockCountConsensus - blocksAgoEstimate

            var (timeS, _) = getBlockTime(estimate)
            var secDiff = timeS - priceDaySeconds
            var jumpEst = (144.0 * secDiff / secondsInADay).roundToInt()
            var lastEst = 0
            var lastLastEst = 0

            while (jumpEst > 6 && jumpEst != lastLastEst) {
                lastLastEst = lastEst
                lastEst = jumpEst
                estimate = estimate - jumpEst
                val (t, _) = getBlockTime(estimate)
                timeS = t
                secDiff = timeS - priceDaySeconds
                jumpEst = (144.0 * secDiff / secondsInADay).roundToInt()
            }

            // Fine-tune to exact first block of the day
            if (timeS > priceDaySeconds) {
                while (timeS > priceDaySeconds) {
                    estimate--
                    timeS = getBlockTime(estimate).first
                }
                estimate++
            } else if (timeS < priceDaySeconds) {
                while (timeS < priceDaySeconds) {
                    estimate++
                    timeS = getBlockTime(estimate).first
                }
            }

            // Collect all blocks for that UTC day
            fun dayOfMonth(timeSec: Long): Int {
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                c.timeInMillis = timeSec * 1000
                return c.get(Calendar.DAY_OF_MONTH)
            }

            var (t1, h1) = getBlockTime(estimate)
            val day1 = dayOfMonth(t1)
            var endBlock = estimate
            var (tEnd, hEnd) = getBlockTime(endBlock)
            var day2 = dayOfMonth(tEnd)

            emit("Loading blocks for $priceDateStr...")
            while (day1 == day2) {
                blockNums.add(endBlock)
                blockHashes.add(hEnd)
                blockTimes.add(tEnd)
                endBlock++
                val (tt, hh) = getBlockTime(endBlock)
                tEnd = tt; hEnd = hh
                day2 = dayOfMonth(tEnd)
            }
        }

        if (blockNums.isEmpty()) throw RuntimeException("No blocks found")

        // ── Step 5: Initialize histogram ─────────────────────────────────
        val firstBinValue = -6
        val lastBinValue = 6
        val rangeBinValues = lastBinValue - firstBinValue // 12

        val bins = mutableListOf(0.0)
        for (exp in -6 until 6) {
            for (b in 0 until 200) {
                bins.add(10.0.pow(exp + b / 200.0))
            }
        }
        val numBins = bins.size
        val binCounts = DoubleArray(numBins)

        // ── Step 6: Fetch decoded blocks (verbosity 1), extract outputs ──
        // Uses JSON-decoded blocks instead of raw hex parsing.
        // Eliminates SHA256 txid computation and binary parsing — much faster on mobile.
        emit("Loading transactions from ${blockNums.size} blocks...")

        val todaysTxids = HashSet<String>()
        val rawOutputs = mutableListOf<Double>()
        val blockHeightsDec = mutableListOf<Int>()
        val blockTimesDec = mutableListOf<Long>()

        for ((idx, bh) in blockHashes.withIndex()) {
            emit("Block ${idx + 1}/${blockHashes.size}")
            kotlinx.coroutines.yield()

            // Verbosity 2: full transaction objects with vout values, vin refs
            val block = rpcCallLong("getblock", bh, 2)
            val txArray = block.getJSONArray("tx")
            val txsToAdd = mutableListOf<Double>()

            for (txIdx in 0 until txArray.length()) {
                val tx = txArray.getJSONObject(txIdx)
                val txid = tx.getString("txid")
                val vin = tx.getJSONArray("vin")
                val vout = tx.getJSONArray("vout")
                val inputCount = vin.length()
                val outputCount = vout.length()

                // Coinbase check
                val isCoinbase = inputCount > 0 && vin.getJSONObject(0).has("coinbase")

                // Input txids for same-day filter
                val inputTxids = mutableListOf<String>()
                for (i in 0 until inputCount) {
                    val input = vin.getJSONObject(i)
                    if (input.has("txid")) inputTxids.add(input.getString("txid"))
                }

                // Output values + OP_RETURN check
                var hasOpReturn = false
                val outputValues = mutableListOf<Double>()
                for (i in 0 until outputCount) {
                    val output = vout.getJSONObject(i)
                    val valueBtc = output.getDouble("value")
                    val scriptPubKey = output.optJSONObject("scriptPubKey")
                    if (scriptPubKey?.optString("type", "") == "nulldata") hasOpReturn = true
                    if (valueBtc > 1e-5 && valueBtc < 1e5) outputValues.add(valueBtc)
                }

                // Witness size filter via tx weight (proxy for Python's 500-byte witness check)
                // witness_bytes ≈ (weight - vsize) / inputCount
                val weight = tx.optInt("weight", 0)
                val vsize = tx.optInt("vsize", 0)
                val witnessExceeds = if (vsize > 0 && inputCount > 0) {
                    ((weight - vsize).toLong() / inputCount) > 500
                } else false

                todaysTxids.add(txid)
                val isSameDayTx = inputTxids.any { it in todaysTxids }

                // Apply filters (same as Python original)
                if (inputCount <= 5 && outputCount == 2 && !isCoinbase &&
                    !hasOpReturn && !witnessExceeds && !isSameDayTx) {
                    for (amount in outputValues) {
                        val amountLog = log10(amount)
                        val pctInRange = (amountLog - firstBinValue) / rangeBinValues.toDouble()
                        var binEst = (pctInRange * numBins).toInt()
                        while (bins[binEst] <= amount) binEst++
                        val binNumber = binEst - 1
                        binCounts[binNumber] += 1.0
                        txsToAdd.add(amount)
                    }
                }
            }

            // Store outputs
            for (amt in txsToAdd) {
                rawOutputs.add(amt)
                blockHeightsDec.add(blockNums[idx])
                blockTimesDec.add(blockTimes[idx])
            }
        }

        emit("Processing histogram...")

        // ── Step 7: Remove round BTC amounts ─────────────────────────────
        for (n in 0..200) binCounts[n] = 0.0
        for (n in 1601 until numBins) binCounts[n] = 0.0

        val roundBtcBins = intArrayOf(
            201, 401, 461, 496, 540, 601, 661, 696, 740,
            801, 861, 896, 940, 1001, 1061, 1096, 1140, 1201
        )
        for (r in roundBtcBins) {
            binCounts[r] = 0.5 * (binCounts[r + 1] + binCounts[r - 1])
        }

        var curveSum = 0.0
        for (n in 201..1600) curveSum += binCounts[n]

        for (n in 201..1600) {
            binCounts[n] /= curveSum
            if (binCounts[n] > 0.008) binCounts[n] = 0.008
        }

        // ── Step 8: Construct stencils ───────────────────────────────────
        val numElements = 803
        val mean = 411
        val stdDev = 201

        val smoothStencil = DoubleArray(numElements) { x ->
            val expPart = -((x - mean).toDouble().pow(2)) / (2.0 * stdDev.toDouble().pow(2))
            0.00150 * Math.E.pow(expPart) + 0.0000005 * x
        }

        val spikeStencil = DoubleArray(numElements)
        // Hard-coded spike values (identical to Python)
        spikeStencil[40]  = 0.001300198324984352  // $1
        spikeStencil[141] = 0.001676746949820743  // $5
        spikeStencil[201] = 0.003468805546942046  // $10
        spikeStencil[202] = 0.001991977522512513
        spikeStencil[236] = 0.001905066647961839  // $15
        spikeStencil[261] = 0.003341772718156079  // $20
        spikeStencil[262] = 0.002588902624584287
        spikeStencil[296] = 0.002577893841190244  // $30
        spikeStencil[297] = 0.002733728814200412
        spikeStencil[340] = 0.003076117748975647  // $50
        spikeStencil[341] = 0.005613067550103145
        spikeStencil[342] = 0.003088253178535568
        spikeStencil[400] = 0.002918457489366139  // $100
        spikeStencil[401] = 0.006174500465286022
        spikeStencil[402] = 0.004417068070043504
        spikeStencil[403] = 0.002628663628020371
        spikeStencil[436] = 0.002858828161543839  // $150
        spikeStencil[461] = 0.004097463611984264  // $200
        spikeStencil[462] = 0.003345917406120509
        spikeStencil[496] = 0.002521467726855856  // $300
        spikeStencil[497] = 0.002784125730361008
        spikeStencil[541] = 0.003792850444811335  // $500
        spikeStencil[601] = 0.003688240815848247  // $1000
        spikeStencil[602] = 0.002392400117402263
        spikeStencil[636] = 0.001280993059008106  // $1500
        spikeStencil[661] = 0.001654665137536031  // $2000
        spikeStencil[662] = 0.001395501347054946
        spikeStencil[741] = 0.001154279140906312  // $5000
        spikeStencil[801] = 0.000832244504868709  // $10000

        // ── Step 9: Rough price estimate ─────────────────────────────────
        emit("Finding rough price...")

        val centerP001 = 601
        val halfStencil = (spikeStencil.size + 1) / 2
        val leftP001 = centerP001 - halfStencil
        val rightP001 = centerP001 + halfStencil

        val minSlide = -141  // $500k
        val maxSlide = 201   // $5k

        var bestSlide = 0
        var bestSlideScore = 0.0
        var totalScore = 0.0

        for (slide in minSlide until maxSlide) {
            val shiftedStart = leftP001 + slide
            val shiftedEnd = rightP001 + slide

            var slideScoSmooth = 0.0
            var slideSco = 0.0
            for (n in 0 until numElements) {
                val cv = binCounts[shiftedStart + n]
                slideScoSmooth += cv * smoothStencil[n]
                slideSco += cv * spikeStencil[n]
            }

            if (slide < 150) slideSco += slideScoSmooth * 0.65

            if (slideSco > bestSlideScore) {
                bestSlideScore = slideSco
                bestSlide = slide
            }
            totalScore += slideSco
        }

        val usd100best = bins[centerP001 + bestSlide]
        val btcUsdBest = 100.0 / usd100best

        // Neighbor up
        var neighborUpScore = 0.0
        for (n in 0 until numElements) {
            neighborUpScore += binCounts[leftP001 + bestSlide + 1 + n] * spikeStencil[n]
        }
        // Neighbor down
        var neighborDownScore = 0.0
        for (n in 0 until numElements) {
            neighborDownScore += binCounts[leftP001 + bestSlide - 1 + n] * spikeStencil[n]
        }

        val bestNeighbor = if (neighborDownScore > neighborUpScore) -1 else 1
        val neighborScore = maxOf(neighborUpScore, neighborDownScore)

        val usd100_2nd = bins[centerP001 + bestSlide + bestNeighbor]
        val btcUsd2nd = 100.0 / usd100_2nd

        val avgScore = totalScore / (maxSlide - minSlide)
        val a1 = bestSlideScore - avgScore
        val a2 = abs(neighborScore - avgScore)
        val w1 = a1 / (a1 + a2)
        val w2 = a2 / (a1 + a2)
        val roughPriceEstimate = (w1 * btcUsdBest + w2 * btcUsd2nd).toInt()

        emit("Rough price: ~$$roughPriceEstimate")

        // ── Step 10: Intraday price points ───────────────────────────────
        val usds = intArrayOf(5, 10, 15, 20, 25, 30, 40, 50, 100, 150, 200, 300, 500, 1000)
        val pctRangeWide = 0.25

        // Build micro round satoshi remove list (same as Python)
        val microRemoveList = mutableListOf<Double>()
        var ii = 0.00005
        while (ii < 0.0001) { microRemoveList.add(ii); ii += 0.00001 }
        ii = 0.0001
        while (ii < 0.001) { microRemoveList.add(ii); ii += 0.00001 }
        ii = 0.001
        while (ii < 0.01) { microRemoveList.add(ii); ii += 0.0001 }
        ii = 0.01
        while (ii < 0.1) { microRemoveList.add(ii); ii += 0.001 }
        ii = 0.1
        while (ii < 1.0) { microRemoveList.add(ii); ii += 0.01 }
        val pctMicroRemove = 0.0001

        val outputPrices = mutableListOf<Double>()
        val outputBlocks = mutableListOf<Int>()
        val outputTimesOut = mutableListOf<Long>()

        for (i in rawOutputs.indices) {
            val n = rawOutputs[i]
            val b = blockHeightsDec[i]
            val t = blockTimesDec[i]

            for (usd in usds) {
                val avbtc = usd.toDouble() / roughPriceEstimate
                val btcUp = avbtc + pctRangeWide * avbtc
                val btcDn = avbtc - pctRangeWide * avbtc

                if (n > btcDn && n < btcUp) {
                    var append = true
                    for (r in microRemoveList) {
                        val rmDn = r - pctMicroRemove * r
                        val rmUp = r + pctMicroRemove * r
                        if (n > rmDn && n < rmUp) {
                            append = false
                            break
                        }
                    }
                    if (append) {
                        outputPrices.add(usd.toDouble() / n)
                        outputBlocks.add(b)
                        outputTimesOut.add(t)
                    }
                }
            }
        }

        // ── Step 11: Exact average price ─────────────────────────────────
        emit("Finding exact price...")

        fun findCentralOutput(prices: List<Double>, priceMin: Double, priceMax: Double): Pair<Double, Double> {
            val filtered = prices.filter { it > priceMin && it < priceMax }.sorted()
            val nn = filtered.size
            if (nn == 0) return roughPriceEstimate.toDouble() to 0.0

            // Prefix sums
            val prefixSum = DoubleArray(nn)
            var total = 0.0
            for (i in 0 until nn) { total += filtered[i]; prefixSum[i] = total }

            var minDist = Double.MAX_VALUE
            var bestIdx = 0
            for (i in 0 until nn) {
                val leftCount = i
                val rightCount = nn - i - 1
                val leftSum = if (i > 0) prefixSum[i - 1] else 0.0
                val rightSum = total - prefixSum[i]
                val dist = (filtered[i] * leftCount - leftSum) + (rightSum - filtered[i] * rightCount)
                if (dist < minDist) { minDist = dist; bestIdx = i }
            }

            val bestOutput = filtered[bestIdx]

            // MAD
            val deviations = filtered.map { abs(it - bestOutput) }.sorted()
            val m = deviations.size
            val mad = if (m % 2 == 0) (deviations[m / 2 - 1] + deviations[m / 2]) / 2.0
                      else deviations[m / 2]

            return bestOutput to mad
        }

        val pctRangeTight = 0.05
        var priceUp = roughPriceEstimate + pctRangeTight * roughPriceEstimate
        var priceDn = roughPriceEstimate - pctRangeTight * roughPriceEstimate
        var (centralPrice, avDev) = findCentralOutput(outputPrices, priceDn, priceUp)

        // Iterative convergence
        val avs = mutableSetOf<Double>()
        avs.add(centralPrice)
        var converged = false
        for (iter in 0 until 100) {
            priceUp = centralPrice + pctRangeTight * centralPrice
            priceDn = centralPrice - pctRangeTight * centralPrice
            val (newPrice, newDev) = findCentralOutput(outputPrices, priceDn, priceUp)
            if (newPrice in avs) { centralPrice = newPrice; avDev = newDev; converged = true; break }
            avs.add(newPrice)
            centralPrice = newPrice
            avDev = newDev
        }

        // Wide deviation check
        val pctRangeMed = 0.1
        priceUp = centralPrice + pctRangeMed * centralPrice
        priceDn = centralPrice - pctRangeMed * centralPrice
        val priceRange = priceUp - priceDn
        val (_, finalDev) = findCentralOutput(outputPrices, priceDn, priceUp)
        val devPct = finalDev / priceRange

        val finalPrice = centralPrice.toInt()
        emit("Price: $$finalPrice")

        return OracleResult(
            price = finalPrice,
            date = priceDateStr,
            blockRange = blockNums.first()..blockNums.last(),
            outputCount = outputPrices.size,
            deviation = devPct
        )
    }

    private fun ByteArray.reversed(): ByteArray = this.reversedArray()
}
