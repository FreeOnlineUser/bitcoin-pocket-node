package com.pocketnode.mempool

import kotlinx.serialization.Serializable

/**
 * Represents a mempool transaction entry from getrawmempool RPC call
 */
@Serializable
data class MempoolEntry(
    val vsize: Int,
    val weight: Int,
    val fee: Double,
    val modifiedfee: Double,
    val time: Long,
    val height: Int,
    val descendantcount: Int,
    val descendantsize: Int,
    val descendantfees: Double,
    val ancestorcount: Int,
    val ancestorsize: Int,
    val ancestorfees: Double,
    val wtxid: String,
    val fees: MempoolFees,
    val depends: List<String> = emptyList(),
    val spentby: List<String> = emptyList(),
    val bip125_replaceable: Boolean = false,
    val unbroadcast: Boolean = false
)

@Serializable
data class MempoolFees(
    val base: Double,
    val modified: Double,
    val ancestor: Double,
    val descendant: Double
)