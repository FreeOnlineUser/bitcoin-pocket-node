package com.pocketnode.lightning

import android.util.Log

/**
 * Computes display balances from LDK's raw balance data.
 * Extracted from LightningService to keep balance logic testable and isolated.
 *
 * Key invariant: LDK's totalOnchainBalanceSats includes sweeper-tracked outputs
 * (pending close), so we subtract pending close to avoid double-counting.
 */
object BalanceTracker {
    private const val TAG = "BalanceTracker"
    private const val ANTI_REORG_DELAY = 6

    /**
     * Compute display on-chain balance by subtracting pending close.
     * Returns 0 during sync when the only discovered UTXO is the pending close output.
     */
    fun displayOnchain(totalOnchain: Long, pendingCloseSats: Long): Long {
        return maxOf(0L, totalOnchain - pendingCloseSats)
    }

    /**
     * Parse pending close balances from LDK's PendingSweepBalance list.
     * Hides entries that have passed the ANTI_REORG_DELAY threshold (funds are spendable).
     * The sweeper tracks outputs for 4038 blocks internally, but UI shouldn't show them that long.
     */
    fun parsePendingCloses(
        rawBalances: List<org.lightningdevkit.ldknode.PendingSweepBalance>,
        currentHeight: Int
    ): List<LightningService.LightningState.PendingClose> {
        return rawBalances.mapNotNull { psb ->
            when (psb) {
                is org.lightningdevkit.ldknode.PendingSweepBalance.PendingBroadcast ->
                    LightningService.LightningState.PendingClose(
                        psb.channelId ?: "", psb.amountSatoshis.toLong(), "Pending broadcast"
                    )
                is org.lightningdevkit.ldknode.PendingSweepBalance.BroadcastAwaitingConfirmation ->
                    LightningService.LightningState.PendingClose(
                        psb.channelId ?: "", psb.amountSatoshis.toLong(),
                        "Awaiting confirmation", psb.latestBroadcastHeight.toInt(),
                        txid = psb.latestSpendingTxid
                    )
                is org.lightningdevkit.ldknode.PendingSweepBalance.AwaitingThresholdConfirmations -> {
                    val spendableAt = psb.confirmationHeight.toInt() + ANTI_REORG_DELAY
                    val blocksLeft = maxOf(0, spendableAt - currentHeight)
                    val confs = currentHeight - psb.confirmationHeight.toInt()
                    if (blocksLeft <= 0) null
                    else LightningService.LightningState.PendingClose(
                        psb.channelId ?: "", psb.amountSatoshis.toLong(),
                        "Confirming", psb.confirmationHeight.toInt(),
                        txid = psb.latestSpendingTxid, blocksRemaining = blocksLeft, confirmations = confs
                    )
                }
                else -> LightningService.LightningState.PendingClose("", 0, "Unknown")
            }
        }.filter { it.amountSats > 0 }
    }

    /**
     * Determine if Lightning Pay should be the home screen.
     */
    fun shouldUnlockLightningPay(channelCount: Int): Boolean = channelCount > 0

    /**
     * Determine if Lightning Pay should be re-locked to dashboard.
     * Pending close doesn't keep Pay unlocked (it's on-chain, not Lightning).
     */
    fun shouldRelockLightningPay(channelCount: Int, lightningBalance: Long): Boolean {
        return channelCount == 0 && lightningBalance == 0L
    }

    /**
     * Log pending close details for debugging.
     */
    fun logPendingCloses(
        pendingCloses: List<LightningService.LightningState.PendingClose>,
        rawCount: Int
    ) {
        if (pendingCloses.isNotEmpty() || rawCount > 0) {
            Log.d(TAG, "pendingCloses: raw=$rawCount parsed=${pendingCloses.size}")
            pendingCloses.forEach { pc ->
                Log.d(TAG, "  close: ${pc.amountSats}sats status=${pc.status} blocks=${pc.blocksRemaining} txid=${pc.txid?.take(16)}")
            }
        }
    }
}
