package com.pocketnode.oracle

import com.pocketnode.rpc.BitcoinRpcClient

/**
 * The public edition, as released on GitHub. The UTXOracle licence (see
 * LICENSES/UTXORACLE-LICENSE.md) reserves live prices for its author: anything
 * from the mempool, from blocks under 6 confirmations, or updating faster than
 * once a block. So there is no live price here, and the recent figure leaves the
 * newest blocks out.
 */
object Edition {
    const val INTERNAL = false

    /**
     * Blocks at the tip left out of the recent-price window, so its newest block
     * has 6 confirmations.
     */
    const val RECENT_SKIP = 5

    const val RECENT_LABEL = "Recent"

    fun liveSource(rpc: BitcoinRpcClient): LivePriceSource? = null
}
