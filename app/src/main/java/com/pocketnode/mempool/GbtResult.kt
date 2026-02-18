/*
 * AGPL-3.0 License
 * Portions of this code structure are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

package com.pocketnode.mempool

/**
 * Result from the GBT (getblocktemplate) algorithm
 * 
 * @property blocks A 2D array of transaction UIDs, each inner array represents a projected block
 * @property blockWeights Array of total weights per block
 * @property clusters 2D array of transaction UIDs representing clusters of dependent transactions
 * @property rates Array of [txid, effectiveFeePerVsize] pairs for transactions with updated rates
 * @property overflow Array of transaction UIDs that couldn't fit in any block
 */
data class GbtResult(
    val blocks: Array<IntArray> = emptyArray(),
    val blockWeights: IntArray = intArrayOf(),
    val clusters: Array<IntArray> = emptyArray(),
    val rates: Array<DoubleArray> = emptyArray(),
    val overflow: IntArray = intArrayOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GbtResult

        if (!blocks.contentDeepEquals(other.blocks)) return false
        if (!blockWeights.contentEquals(other.blockWeights)) return false
        if (!clusters.contentDeepEquals(other.clusters)) return false
        if (!rates.contentDeepEquals(other.rates)) return false
        if (!overflow.contentEquals(other.overflow)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = blocks.contentDeepHashCode()
        result = 31 * result + blockWeights.contentHashCode()
        result = 31 * result + clusters.contentDeepHashCode()
        result = 31 * result + rates.contentDeepHashCode()
        result = 31 * result + overflow.contentHashCode()
        return result
    }
}