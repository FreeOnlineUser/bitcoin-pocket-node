package com.pocketnode.lightning

import android.content.Context
import android.util.Log
import com.pocketnode.power.PowerModeManager
import org.lightningdevkit.ldknode.*

/**
 * Handles Lightning payment operations: send, receive, and payment tracking.
 * Extracted from LightningService to reduce god-object complexity.
 *
 * Holds a reference to the ldk-node Node instance, provided by LightningService.
 * All payment methods handle network holds automatically on Low/Away mode.
 */
class PaymentManager(private val context: Context) {

    companion object {
        private const val TAG = "PaymentManager"

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

    // ── Send ─────────────────────────────────────────────────────────

    fun payInvoice(invoiceStr: String, routeConfig: RouteParametersConfig? = null): Result<String> {
        val result = payInvoiceWithRetry(invoiceStr, routeConfig)
        return when (result) {
            is PaymentResult.Success -> Result.success(result.paymentId)
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
            val paymentId = n.bolt11Payment().send(invoice, config)
            Log.i(TAG, "Payment queued: $paymentId (maxFee=${config.maxTotalRoutingFeeMsat}msat)")
            val result = waitForPayment(n, paymentId, 30)
            if (result) {
                Log.i(TAG, "Payment confirmed successful: $paymentId")
                PaymentResult.Success(paymentId)
            } else {
                // Check if this looks like a routing failure
                val payment = n.listPayments().find { it.id == paymentId }
                val isRoutingFailure = payment?.status == PaymentStatus.FAILED
                if (isRoutingFailure && routeConfig == null) {
                    // Offer bumped retry
                    val bumped = bumpedRouteConfig(amountMsat)
                    Log.w(TAG, "Payment routing failed. Current max: ${config.maxTotalRoutingFeeMsat}msat, bump to: ${bumped.maxTotalRoutingFeeMsat}msat")
                    PaymentResult.RoutingFailed(
                        invoiceStr = invoiceStr,
                        amountMsat = amountMsat,
                        currentMaxFeeMsat = config.maxTotalRoutingFeeMsat?.toLong() ?: 0,
                        bumpedMaxFeeMsat = bumped.maxTotalRoutingFeeMsat?.toLong() ?: 0
                    )
                } else {
                    PaymentResult.Failed("Payment failed or timed out")
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
            val payment = n.listPayments().find { it.id == paymentId }
            if (payment != null) {
                when (payment.status) {
                    PaymentStatus.SUCCEEDED -> return true
                    PaymentStatus.FAILED -> return false
                    else -> {}
                }
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun ensureRpc(pmm: PowerModeManager) {
        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context) ?: return
        pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
    }
}
