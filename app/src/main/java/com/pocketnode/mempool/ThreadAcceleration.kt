/*
 * AGPL-3.0 License
 * Portions of this code structure are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

package com.pocketnode.mempool

/**
 * Represents a fee acceleration for a transaction
 */
data class ThreadAcceleration(
    val uid: Int,
    val delta: Double // fee delta
)