package com.pocketnode.lightning

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lightningdevkit.ldknode.NetworkGraph
import org.lightningdevkit.ldknode.Node

/**
 * Tracks payment routing attempts in real-time for UI display.
 * Parses LDK verbose log output to extract hop information.
 *
 * Since ldk-node doesn't expose path info in its event API,
 * we capture it from the routing log messages.
 */
class PaymentTracker {

    companion object {
        private const val TAG = "PaymentTracker"
    }

    data class Hop(
        val nodeId: String,      // pubkey (hex, truncated for display)
        val alias: String?,      // node name from graph, null if unknown
        val scid: String,        // short channel ID
        val feeMsat: Long,       // fee charged at this hop
        val status: HopStatus
    )

    enum class HopStatus {
        PENDING,    // HTLC in flight
        SUCCESS,    // HTLC settled
        FAILED      // This hop or downstream failed
    }

    data class PaymentAttempt(
        val paymentId: String,
        val amountMsat: Long,
        val hops: List<Hop>,
        val status: AttemptStatus,
        val failureHopIndex: Int = -1,  // which hop failed (-1 = none)
        val failureReason: String? = null
    )

    enum class AttemptStatus {
        ROUTING,    // Finding route
        IN_FLIGHT,  // HTLC sent, waiting
        SUCCEEDED,
        FAILED
    }

    // Internal but accessible from PaymentManager for hop updates
    val _currentAttempt = MutableStateFlow<PaymentAttempt?>(null)
    val currentAttempt: StateFlow<PaymentAttempt?> = _currentAttempt.asStateFlow()

    private val _attempts = MutableStateFlow<List<PaymentAttempt>>(emptyList())
    val attempts: StateFlow<List<PaymentAttempt>> = _attempts.asStateFlow()

    /** Reference to node's network graph for alias lookups */
    @Volatile var networkGraph: NetworkGraph? = null

    fun startPayment(paymentId: String, amountMsat: Long) {
        _currentAttempt.value = PaymentAttempt(
            paymentId = paymentId,
            amountMsat = amountMsat,
            hops = emptyList(),
            status = AttemptStatus.ROUTING
        )
    }

    /**
     * Parse a route from LDK's "Got route: path N:" log output.
     * Called by a log interceptor or after route is found.
     */
    fun onRouteFound(paymentId: String, hopPubkeys: List<String>, hopScids: List<Long>, hopFees: List<Long>) {
        val graph = networkGraph
        val hops = hopPubkeys.mapIndexed { i, pubkey ->
            val alias = try {
                graph?.node(pubkey)?.announcementInfo?.alias
            } catch (_: Exception) { null }
            Hop(
                nodeId = pubkey,
                alias = alias,
                scid = if (i < hopScids.size) hopScids[i].toString() else "",
                feeMsat = if (i < hopFees.size) hopFees[i] else 0,
                status = HopStatus.PENDING
            )
        }
        _currentAttempt.value = _currentAttempt.value?.copy(
            hops = hops,
            status = AttemptStatus.IN_FLIGHT
        )
        Log.i(TAG, "Route found: ${hops.size} hops: ${hops.map { it.alias ?: it.nodeId.take(12) }}")
    }

    /**
     * Parse hop info from LDK's verbose log lines.
     * Returns list of (pubkey, scid, feeMsat) tuples, or empty if not a route log.
     */
    fun parseRouteFromLog(logLine: String): List<Triple<String, Long, Long>> {
        // LDK logs: "Got route: path 0:" followed by RouteHop entries
        // We parse the UpdateAddHTLC for the first hop, and PaymentPathFailed for the full path
        return emptyList() // Placeholder — log parsing is fragile, use event-based approach below
    }

    fun onPaymentSucceeded(paymentId: String) {
        val current = _currentAttempt.value ?: return
        if (current.paymentId != paymentId) return
        val updated = current.copy(
            status = AttemptStatus.SUCCEEDED,
            hops = current.hops.map { it.copy(status = HopStatus.SUCCESS) }
        )
        _currentAttempt.value = updated
        _attempts.value = _attempts.value + updated
    }

    fun onPaymentFailed(paymentId: String, failedScid: String?, reason: String?) {
        val current = _currentAttempt.value ?: return
        if (current.paymentId != paymentId) return

        val failIndex = if (failedScid != null) {
            current.hops.indexOfFirst { it.scid == failedScid }
        } else -1

        val updated = current.copy(
            status = AttemptStatus.FAILED,
            failureHopIndex = failIndex,
            failureReason = reason,
            hops = current.hops.mapIndexed { i, hop ->
                when {
                    failIndex >= 0 && i < failIndex -> hop.copy(status = HopStatus.SUCCESS)
                    failIndex >= 0 && i == failIndex -> hop.copy(status = HopStatus.FAILED)
                    else -> hop
                }
            }
        )
        _currentAttempt.value = updated
        _attempts.value = _attempts.value + updated
    }

    fun clear() {
        _currentAttempt.value = null
        _attempts.value = emptyList()
    }

    /** Look up node alias from graph */
    fun lookupAlias(pubkey: String): String? {
        return try {
            networkGraph?.node(pubkey)?.announcementInfo?.alias
        } catch (_: Exception) { null }
    }
}
