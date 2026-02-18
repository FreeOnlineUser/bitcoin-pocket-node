/*
 * AGPL-3.0 License
 * Portions of this code structure are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

package com.pocketnode.mempool

/**
 * Represents a transaction in the mempool for GBT processing
 */
data class ThreadTransaction(
    val uid: Int,
    val order: Int,
    val fee: Double,
    val weight: Int,
    val sigops: Int,
    val effectiveFeePerVsize: Double,
    val inputs: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ThreadTransaction

        if (uid != other.uid) return false
        if (order != other.order) return false
        if (fee != other.fee) return false
        if (weight != other.weight) return false
        if (sigops != other.sigops) return false
        if (effectiveFeePerVsize != other.effectiveFeePerVsize) return false
        if (!inputs.contentEquals(other.inputs)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uid
        result = 31 * result + order
        result = 31 * result + fee.hashCode()
        result = 31 * result + weight
        result = 31 * result + sigops
        result = 31 * result + effectiveFeePerVsize.hashCode()
        result = 31 * result + inputs.contentHashCode()
        return result
    }
}