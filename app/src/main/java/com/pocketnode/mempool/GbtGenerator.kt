package com.pocketnode.mempool

/**
 * Kotlin-only GBT (getblocktemplate) algorithm — greedy fee-rate packing.
 */
class GbtGenerator private constructor(
    private val maxBlockWeight: Int,
    private val maxBlocks: Int
) {
    companion object {
        fun create(maxBlockWeight: Int, maxBlocks: Int): GbtGenerator {
            return GbtGenerator(maxBlockWeight, maxBlocks)
        }
    }

    fun make(
        mempool: List<ThreadTransaction>,
        accelerations: List<ThreadAcceleration> = emptyList(),
        maxUid: Int
    ): GbtResult? {
        if (mempool.isEmpty()) return null
        return runFallback(mempool, accelerations)
    }

    private fun runFallback(
        mempool: List<ThreadTransaction>,
        accelerations: List<ThreadAcceleration>
    ): GbtResult? {
        if (mempool.isEmpty()) return null
        try {
            val accelerationMap = accelerations.associateBy { it.uid }
            val adjustedMempool = mempool.map { tx ->
                val acceleration = accelerationMap[tx.uid]
                if (acceleration != null) {
                    val newFee = tx.fee + acceleration.delta
                    val newEffective = newFee / (tx.weight / 4.0)
                    tx.copy(fee = newFee, effectiveFeePerVsize = newEffective)
                } else tx
            }

            val sortedTxs = adjustedMempool.sortedByDescending { it.effectiveFeePerVsize }
            val blocks = mutableListOf<IntArray>()
            val blockWeights = mutableListOf<Int>()
            val overflow = mutableListOf<Int>()
            var currentBlock = mutableListOf<Int>()
            var currentWeight = 0
            var blockCount = 0

            for (tx in sortedTxs) {
                if (blockCount >= maxBlocks) { overflow.add(tx.uid); continue }
                if (currentWeight + tx.weight <= maxBlockWeight) {
                    currentBlock.add(tx.uid)
                    currentWeight += tx.weight
                } else {
                    if (blockCount < maxBlocks - 1) {
                        blocks.add(currentBlock.toIntArray())
                        blockWeights.add(currentWeight)
                        blockCount++
                        currentBlock = mutableListOf(tx.uid)
                        currentWeight = tx.weight
                    } else {
                        overflow.add(tx.uid)
                    }
                }
            }
            if (currentBlock.isNotEmpty()) {
                blocks.add(currentBlock.toIntArray())
                blockWeights.add(currentWeight)
            }

            return GbtResult(
                blocks = blocks.toTypedArray(),
                blockWeights = blockWeights.toIntArray(),
                clusters = emptyArray(),
                rates = emptyArray(),
                overflow = overflow.toIntArray()
            )
        } catch (e: Exception) {
            android.util.Log.e("GbtGenerator", "Error in fallback", e)
            return null
        }
    }

    fun destroy() { /* no-op */ }
}
