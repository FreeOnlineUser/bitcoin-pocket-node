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

        /** Default routing config: generous fee budget for mobile reliability */
        private val ROUTE_CONFIG = RouteParametersConfig(
            maxTotalRoutingFeeMsat = 50_000UL,  // 50 sats max fee (covers multi-hop on payments up to ~500k sats)
            maxTotalCltvExpiryDelta = 1008u,     // ~1 week lockup max
            maxPathCount = 4u.toUByte(),         // Allow MPP across 4 paths
            maxChannelSaturationPowerOfHalf = 2u.toUByte()  // Default, avoid heavily saturated channels
        )
    }

    /** Node reference, set by LightningService after start */
    @Volatile var node: Node? = null

    /** Event handler callback, set by LightningService */
    var handleEvents: (() -> Unit)? = null

    // ── Send ─────────────────────────────────────────────────────────

    fun payInvoice(invoiceStr: String): Result<String> {
        val n = node ?: return Result.failure(Exception("Node not running"))
        val pmm = PowerModeManager.getInstance(context)
        val needsHold = PowerModeManager.modeFlow.value != PowerModeManager.Mode.MAX
        if (needsHold) {
            ensureRpc(pmm)
            pmm.holdNetwork()
        }
        return try {
            val invoice = Bolt11Invoice.fromStr(invoiceStr)
            val paymentId = n.bolt11Payment().send(invoice, ROUTE_CONFIG)
            Log.i(TAG, "Payment queued: $paymentId, waiting for result...")
            val result = waitForPayment(n, paymentId, 30)
            if (result) {
                Log.i(TAG, "Payment confirmed successful: $paymentId")
                Result.success(paymentId)
            } else {
                Log.w(TAG, "Payment failed or timed out: $paymentId")
                Result.failure(Exception("Payment failed or timed out"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pay invoice", e)
            Result.failure(e)
        } finally {
            if (needsHold) pmm.releaseNetworkHold()
        }
    }

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
            val paymentId = if (amountMsat != null)
                n.bolt12Payment().sendUsingAmount(offer, amountMsat.toULong(), null, null, ROUTE_CONFIG)
            else
                n.bolt12Payment().send(offer, null, null, ROUTE_CONFIG)
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

    /**
     * Poll payment status until SUCCEEDED or FAILED (up to timeoutSecs).
     * Returns true if succeeded, false if failed/timed out.
     */
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

    /** Ensure PMM has RPC client for network hold */
    private fun ensureRpc(pmm: PowerModeManager) {
        val creds = com.pocketnode.util.ConfigGenerator.readCredentials(context) ?: return
        pmm.setRpc(com.pocketnode.rpc.BitcoinRpcClient(creds.first, creds.second))
    }
}
