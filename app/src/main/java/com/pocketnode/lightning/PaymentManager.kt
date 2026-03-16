package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import com.pocketnode.power.PowerModeManager
import org.lightningdevkit.ldknode.*
import org.lightningdevkit.ldknode.PaymentPathStatus

/**
 * Handles Lightning payment operations: send, receive, and payment tracking.
 * Extracted from LightningService to reduce god-object complexity.
 *
 * Holds a reference to the ldk-node Node instance, provided by LightningService.
 * All payment methods handle network holds automatically on Low/Away mode.
 */
class PaymentManager(private val context: Context) {

    /** Persistent alias cache — survives restarts and gossip pruning */
    private val aliasCache: MutableMap<String, String> by lazy {
        val prefs = context.getSharedPreferences("node_aliases", Context.MODE_PRIVATE)
        val all = prefs.all
        val map = mutableMapOf<String, String>()
        for ((k, v) in all) { if (v is String) map[k] = v }
        map
    }

    private fun cacheAlias(pubkey: String, alias: String) {
        aliasCache[pubkey] = alias
        context.getSharedPreferences("node_aliases", Context.MODE_PRIVATE)
            .edit().putString(pubkey, alias).apply()
    }

    private fun lookupAlias(graph: NetworkGraph, pubkey: String): String? {
        // Check persistent cache first
        aliasCache[pubkey]?.let { return it }
        // Try graph lookup
        try {
            val nodeInfo = graph.node(pubkey)
            val alias = nodeInfo?.announcementInfo?.alias
            if (alias != null && !alias.startsWith(pubkey.take(8))) {
                cacheAlias(pubkey, alias)
                return alias
            }
        } catch (_: Exception) {}
        // Fallback: fetch from mempool.space API (same API we already use)
        return try {
            val url = java.net.URL("https://mempool.space/api/v1/lightning/nodes/$pubkey")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                // Simple JSON parse for "alias":"value"
                val match = Regex("\"alias\"\\s*:\\s*\"([^\"]+)\"").find(body)
                val alias = match?.groupValues?.get(1)
                if (alias != null && alias.isNotBlank() && !alias.startsWith(pubkey.take(8))) {
                    Log.d(TAG, "Fetched alias '$alias' for ${pubkey.take(16)}... from mempool.space")
                    cacheAlias(pubkey, alias)
                    alias
                } else null
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "Mempool alias fetch failed for ${pubkey.take(16)}: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "PaymentManager"

        /** Clean raw Rust failure reason into human-readable text */
        fun cleanFailureReason(failReason: String?, paymentStatus: PaymentStatus?): String {
            return when {
                failReason != null && (failReason.contains("ChannelFailure") && failReason.contains("is_permanent: false")) ->
                    "Routing peer didn't have enough outgoing liquidity"
                failReason != null && failReason.contains("TemporaryChannelFailure") ->
                    "Routing peer didn't have enough outgoing liquidity"
                failReason != null && failReason.contains("FeeInsufficient") ->
                    "Fee too low for route"
                failReason != null && failReason.contains("IncorrectOrUnknownPaymentDetails") ->
                    "Invoice expired or already paid"
                failReason != null && failReason.contains("NodeFailure") ->
                    "Routing node unreachable"
                paymentStatus == PaymentStatus.FAILED -> "No route found with enough liquidity"
                else -> "Payment timed out"
            }
        }

        /** Default fee: 50 sats or 0.5% of payment, whichever is higher */
        fun defaultRouteConfig(amountMsat: Long = 0): RouteParametersConfig {
            val percentFee = (amountMsat * 5 / 1000).coerceAtLeast(50_000) // 0.5% or 50 sats min
            return RouteParametersConfig(
                maxTotalRoutingFeeMsat = percentFee.toULong(),
                maxTotalCltvExpiryDelta = 1008u,
                maxPathCount = 4u.toUByte(),
                maxChannelSaturationPowerOfHalf = 2u.toUByte()
            )
        }

        /** Bumped fee for retry: 2% of payment or 500 sats, whichever is higher */
        fun bumpedRouteConfig(amountMsat: Long): RouteParametersConfig {
            val percentFee = (amountMsat * 20 / 1000).coerceAtLeast(500_000) // 2% or 500 sats min
            return RouteParametersConfig(
                maxTotalRoutingFeeMsat = percentFee.toULong(),
                maxTotalCltvExpiryDelta = 1008u,
                maxPathCount = 4u.toUByte(),
                maxChannelSaturationPowerOfHalf = 2u.toUByte()
            )
        }
    }

    /** Payment result with routing failure detail for retry UI */
    sealed class PaymentResult {
        data class Success(val paymentId: String) : PaymentResult()
        data class Pending(val paymentId: String) : PaymentResult()
        data class RoutingFailed(
            val invoiceStr: String,
            val amountMsat: Long,
            val currentMaxFeeMsat: Long,
            val bumpedMaxFeeMsat: Long
        ) : PaymentResult()
        data class Failed(val error: String) : PaymentResult()
    }

    /** Node reference, set by LightningService after start */
    @Volatile var node: Node? = null

    /** Event handler callback, set by LightningService */
    var handleEvents: (() -> Unit)? = null

    /** Payment path tracker for UI display */
    val tracker = PaymentTracker()

    // ── Send ─────────────────────────────────────────────────────────

    fun payInvoice(invoiceStr: String, routeConfig: RouteParametersConfig? = null): Result<String> {
        val result = payInvoiceWithRetry(invoiceStr, routeConfig)
        return when (result) {
            is PaymentResult.Success -> Result.success(result.paymentId)
            is PaymentResult.Pending -> Result.success("PENDING:${result.paymentId}")
            is PaymentResult.RoutingFailed -> Result.failure(RoutingException(result))
            is PaymentResult.Failed -> Result.failure(Exception(result.error))
        }
    }

    /**
     * Pay invoice with structured result. UI should check for RoutingException
     * to offer fee bump retry dialog.
     */
    fun payInvoiceWithRetry(invoiceStr: String, routeConfig: RouteParametersConfig? = null): PaymentResult {
        val n = node ?: return PaymentResult.Failed("Node not running")
        val pmm = PowerModeManager.getInstance(context)
        val needsHold = PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX
        if (needsHold) {
            ensureRpc(pmm)
            pmm.holdNetwork()
        }
        return try {
            val invoice = Bolt11Invoice.fromStr(invoiceStr)
            val amountMsat = invoice.amountMilliSatoshis()?.toLong() ?: 0L
            val config = routeConfig ?: defaultRouteConfig(amountMsat)

            // Wire up graph for alias lookups and clear previous paths
            tracker.networkGraph = n.networkGraph()
            tracker.startPayment("pending", amountMsat)
            n.clearPaymentPaths()
            lastSeenPathCount = 0

            val paymentId = n.bolt11Payment().send(invoice, config)
            Log.i(TAG, "Payment queued: $paymentId (maxFee=${config.maxTotalRoutingFeeMsat}msat)")

            // Try to capture route info from peer list / graph
            captureRouteHops(n, paymentId, amountMsat)

            // Wait for LDK to resolve — no arbitrary timeout.
            // Safety cap at 5 min; if still PENDING, report honestly.
            val result = waitForPayment(n, paymentId, 300)
            val payment = n.listPayments().find { it.id == paymentId }
            val actualStatus = payment?.status

            when {
                result || actualStatus == PaymentStatus.SUCCEEDED -> {
                    Log.i(TAG, "Payment confirmed successful: $paymentId")
                    tracker.onPaymentSucceeded(paymentId)
                    PaymentResult.Success(paymentId)
                }
                actualStatus == PaymentStatus.PENDING -> {
                    // Still in-flight after 5 min safety cap — NOT failed
                    Log.w(TAG, "Payment $paymentId still PENDING after safety cap — in-flight")
                    tracker.onPaymentPending(paymentId)
                    PaymentResult.Pending(paymentId)
                }
                else -> {
                    // Actually failed — get real reason
                    val pathAttempts = n.paymentPathAttempts()
                    val failedPath = pathAttempts.lastOrNull { it.status == PaymentPathStatus.FAILED }
                    val failReason = failedPath?.failureReason
                    tracker.onPaymentFailed(paymentId, failedPath?.failedScid?.toString(), failReason)

                    if (actualStatus == PaymentStatus.FAILED && routeConfig == null) {
                        val bumped = bumpedRouteConfig(amountMsat)
                        Log.w(TAG, "Payment routing failed. Current max: ${config.maxTotalRoutingFeeMsat}msat, bump to: ${bumped.maxTotalRoutingFeeMsat}msat")
                        PaymentResult.RoutingFailed(
                            invoiceStr = invoiceStr,
                            amountMsat = amountMsat,
                            currentMaxFeeMsat = config.maxTotalRoutingFeeMsat?.toLong() ?: 0,
                            bumpedMaxFeeMsat = bumped.maxTotalRoutingFeeMsat?.toLong() ?: 0
                        )
                    } else {
                        val cleanReason = cleanFailureReason(failReason, payment?.status)
                        PaymentResult.Failed(cleanReason)
                    }
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            Log.e(TAG, "Failed to pay invoice: $msg", e)
            // Routing errors from LDK come as exceptions too
            if (msg.contains("Route", ignoreCase = true) || msg.contains("path", ignoreCase = true)) {
                val invoice = try { Bolt11Invoice.fromStr(invoiceStr) } catch (_: Exception) { null }
                val amountMsat = invoice?.amountMilliSatoshis()?.toLong() ?: 0L
                val config = routeConfig ?: defaultRouteConfig(amountMsat)
                val bumped = bumpedRouteConfig(amountMsat)
                if (routeConfig == null) {
                    PaymentResult.RoutingFailed(
                        invoiceStr = invoiceStr,
                        amountMsat = amountMsat,
                        currentMaxFeeMsat = config.maxTotalRoutingFeeMsat?.toLong() ?: 0,
                        bumpedMaxFeeMsat = bumped.maxTotalRoutingFeeMsat?.toLong() ?: 0
                    )
                } else {
                    PaymentResult.Failed(msg)
                }
            } else {
                PaymentResult.Failed(msg)
            }
        } finally {
            if (needsHold) pmm.releaseNetworkHold()
        }
    }

    /** Custom exception that carries retry info for the UI */
    class RoutingException(val detail: PaymentResult.RoutingFailed) :
        Exception("Routing failed (max fee ${detail.currentMaxFeeMsat / 1000} sats). Retry with ${detail.bumpedMaxFeeMsat / 1000} sats?")

    fun payOffer(offerStr: String, amountMsat: Long? = null): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        val pmm = PowerModeManager.getInstance(context)
        val needsHold = PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX
        if (needsHold) {
            ensureRpc(pmm)
            pmm.holdNetwork()
        }
        return try {
            val offer = Offer.fromStr(offerStr)
            val amt = amountMsat ?: 0L
            val config = defaultRouteConfig(amt)
            val paymentId = if (amountMsat != null)
                n.bolt12Payment().sendUsingAmount(offer, amountMsat.toULong(), null, null, config)
            else
                n.bolt12Payment().send(offer, null, null, config)
            val id = paymentId.toString()
            Log.i(TAG, "BOLT12 payment queued: $id, waiting for result...")
            val result = waitForPayment(n, id, 30)
            if (result) {
                Log.i(TAG, "BOLT12 payment confirmed successful: $id")
                Result.success(id)
            } else {
                Log.w(TAG, "BOLT12 payment failed or timed out: $id")
                Result.failure(Exception("Payment failed or timed out"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pay offer", e)
            Result.failure(e)
        } finally {
            if (needsHold) pmm.releaseNetworkHold()
        }
    }

    // ── Receive ──────────────────────────────────────────────────────

    fun createInvoice(amountMsat: Long, description: String, expirySecs: Int = 3600): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val desc = Bolt11InvoiceDescription.Direct(description)
            val invoice = n.bolt11Payment().receive(amountMsat.toULong(), desc, expirySecs.toUInt())
            Result.success(invoice.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create invoice", e)
            Result.failure(e)
        }
    }

    fun createOffer(amountMsat: Long, description: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = n.bolt12Payment().receive(amountMsat.toULong(), description, null, null)
            Result.success(offer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offer", e)
            val msg = if (e.javaClass.simpleName.contains("OfferCreationFailed"))
                "BOLT12 offers are linked to channels. A channel is required to create an offer."
            else e.message ?: "Failed to create offer"
            Result.failure(Exception(msg))
        }
    }

    fun createVariableOffer(description: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            val offer = n.bolt12Payment().receiveVariableAmount(description, null)
            Result.success(offer.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create variable offer", e)
            val msg = if (e.javaClass.simpleName.contains("OfferCreationFailed"))
                "BOLT12 offers are linked to channels. A channel is required to create an offer."
            else e.message ?: "Failed to create offer"
            Result.failure(Exception(msg))
        }
    }

    // ── Payment History ──────────────────────────────────────────────

    fun listPayments(): List<PaymentDetails> = node?.listPayments() ?: emptyList()

    fun removePayment(id: String): Result<Unit> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        return try {
            n.removePayment(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "removePayment($id) failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun waitForPayment(n: Node, paymentId: String, timeoutSecs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSecs * 1000L
        while (System.currentTimeMillis() < deadline) {
            try { handleEvents?.invoke() } catch (_: Exception) {}

            // Poll for real route data from LDK path events
            pollRouteData(n, paymentId)

            val payment = n.listPayments().find { it.id == paymentId }
            if (payment != null) {
                when (payment.status) {
                    PaymentStatus.SUCCEEDED -> {
                        // Final poll to catch PaymentPathSuccessful
                        pollRouteData(n, paymentId)
                        return true
                    }
                    PaymentStatus.FAILED -> {
                        pollRouteData(n, paymentId)
                        return false
                    }
                    else -> {}
                }
            }
            Thread.sleep(500)
        }
        return false
    }

    /** Track which path attempts we've already processed */
    private var lastSeenPathCount = 0

    /** Check LDK for real path data and update tracker if found. */
    private fun pollRouteData(n: Node, paymentId: String) {
        try {
            val paths = n.paymentPathAttempts()
            if (paths.isEmpty()) return

            val graph = n.networkGraph()
            // Get all paths for this payment
            val myPaths = paths.filter { it.paymentId == paymentId }
            if (myPaths.isEmpty()) return

            // Process any NEW paths we haven't seen yet
            if (myPaths.size > lastSeenPathCount) {
                for (i in lastSeenPathCount until myPaths.size) {
                    val path = myPaths[i]
                    val attempt = buildAttempt(graph, paymentId, path)
                    if (attempt.status == PaymentTracker.AttemptStatus.FAILED) {
                        // Archive this failed attempt
                        tracker.archiveAttempt(attempt)
                    }
                }
                lastSeenPathCount = myPaths.size
            }

            // Always show the latest path as current
            val latestPath = myPaths.last()
            val currentAttempt = buildAttempt(graph, paymentId, latestPath)
            tracker._currentAttempt.value = currentAttempt
            Log.i(TAG, "Route data: ${currentAttempt.hops.size} hops, status=${currentAttempt.status} (attempt ${myPaths.size})")
        } catch (e: Exception) {
            // Don't spam logs on every poll
        }
    }

    /** Build a PaymentAttempt from a path info */
    private fun buildAttempt(graph: NetworkGraph, paymentId: String, path: PaymentPathInfo): PaymentTracker.PaymentAttempt {
        val hops = path.hops.map { hop ->
            val alias = lookupAlias(graph, hop.nodeId)
            PaymentTracker.Hop(
                nodeId = hop.nodeId,
                alias = alias ?: hop.nodeId.take(12) + "...",
                scid = hop.shortChannelId.toString(),
                feeMsat = hop.feeMsat.toLong(),
                status = PaymentTracker.HopStatus.PENDING
            )
        }
        if (hops.isEmpty()) return PaymentTracker.PaymentAttempt(
            paymentId = paymentId, amountMsat = tracker._currentAttempt.value?.amountMsat ?: 0,
            hops = emptyList(), status = PaymentTracker.AttemptStatus.IN_FLIGHT
        )

        val failedScid = path.failedScid
        val finalHops = if (failedScid != null && path.status == PaymentPathStatus.FAILED) {
            val failIdx = hops.indexOfFirst { it.scid == failedScid.toString() }
            hops.mapIndexed { i, hop ->
                when {
                    failIdx >= 0 && i < failIdx -> hop.copy(status = PaymentTracker.HopStatus.SUCCESS)
                    failIdx >= 0 && i == failIdx -> hop.copy(status = PaymentTracker.HopStatus.FAILED)
                    else -> hop
                }
            }
        } else if (path.status == PaymentPathStatus.SUCCEEDED) {
            hops.map { it.copy(status = PaymentTracker.HopStatus.SUCCESS) }
        } else hops

        val status = when (path.status) {
            PaymentPathStatus.SUCCEEDED -> PaymentTracker.AttemptStatus.SUCCEEDED
            PaymentPathStatus.FAILED -> PaymentTracker.AttemptStatus.FAILED
            else -> PaymentTracker.AttemptStatus.IN_FLIGHT
        }

        return PaymentTracker.PaymentAttempt(
            paymentId = paymentId,
            amountMsat = tracker._currentAttempt.value?.amountMsat ?: 0,
            hops = finalHops,
            status = status,
            failureHopIndex = if (failedScid != null) finalHops.indexOfFirst { it.scid == failedScid.toString() } else -1,
            failureReason = path.failureReason
        )
    }

    /**
     * Set initial placeholder hops (first channel peer + "routing...").
     * Real hops come from pollRouteData() during waitForPayment().
     */
    private fun captureRouteHops(n: Node, paymentId: String, amountMsat: Long) {
        try {
            val channels = n.listChannels()
            if (channels.isEmpty()) return
            val firstChannel = channels.firstOrNull { it.isUsable } ?: channels.first()
            val graph = n.networkGraph()
            val firstAlias = lookupAlias(graph, firstChannel.counterpartyNodeId)

            tracker._currentAttempt.value = tracker._currentAttempt.value?.copy(
                hops = listOf(
                    PaymentTracker.Hop(
                        nodeId = firstChannel.counterpartyNodeId,
                        alias = firstAlias ?: "Channel peer",
                        scid = firstChannel.channelId.take(16),
                        feeMsat = 0,
                        status = PaymentTracker.HopStatus.PENDING
                    ),
                    PaymentTracker.Hop(
                        nodeId = "...",
                        alias = "Routing...",
                        scid = "",
                        feeMsat = 0,
                        status = PaymentTracker.HopStatus.PENDING
                    )
                ),
                status = PaymentTracker.AttemptStatus.IN_FLIGHT
            )
        } catch (e: Exception) {
            Log.w(TAG, "captureRouteHops: ${e.message}")
        }
    }

    private fun ensureRpc(pmm: PowerModeManager) {
        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context) ?: return
        pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
    }
}
