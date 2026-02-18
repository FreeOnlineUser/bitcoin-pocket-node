/*
 * AGPL-3.0 License
 * Portions of this code structure are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

package com.pocketnode.mempool

/**
 * JNI wrapper for the Rust GBT (getblocktemplate) algorithm
 * Ported from mempool.space's implementation
 * Falls back to Kotlin implementation if native library is unavailable
 */
class GbtGenerator private constructor(
    private val nativePtr: Long,
    private val useNative: Boolean,
    private val maxBlockWeight: Int,
    private val maxBlocks: Int
) {
    companion object {
        private var isNativeAvailable = false
        
        init {
            try {
                System.loadLibrary("gbt")
                initNative()
                isNativeAvailable = true
                android.util.Log.d("GbtGenerator", "Native GBT library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("GbtGenerator", "Native GBT library not available, using Kotlin fallback", e)
                isNativeAvailable = false
            }
        }

        /**
         * Create a new GBT generator
         * @param maxBlockWeight Maximum weight per block (typically 4000000)
         * @param maxBlocks Maximum number of blocks to project (typically 8)
         */
        fun create(maxBlockWeight: Int, maxBlocks: Int): GbtGenerator {
            return if (isNativeAvailable) {
                val ptr = createNative(maxBlockWeight, maxBlocks)
                GbtGenerator(ptr, true, maxBlockWeight, maxBlocks)
            } else {
                GbtGenerator(0L, false, maxBlockWeight, maxBlocks)
            }
        }

        @JvmStatic
        private external fun initNative()

        @JvmStatic
        private external fun createNative(maxBlockWeight: Int, maxBlocks: Int): Long
        
        @JvmStatic
        private external fun destroyNative(ptr: Long)
        
        @JvmStatic
        private external fun makeNative(
            ptr: Long,
            mempool: Array<ThreadTransaction>,
            accelerations: Array<ThreadAcceleration>,
            maxUid: Int
        ): GbtResult?
        
        @JvmStatic
        private external fun updateNative(
            ptr: Long,
            newTxs: Array<ThreadTransaction>,
            removeTxs: Array<Int>,
            accelerations: Array<ThreadAcceleration>,
            maxUid: Int
        ): GbtResult?
    }

    /**
     * Run GBT algorithm with initial mempool state
     * @param mempool List of transactions in mempool
     * @param accelerations List of fee accelerations (usually empty)
     * @param maxUid Maximum transaction UID seen
     * @return GBT result with projected blocks
     */
    fun make(
        mempool: List<ThreadTransaction>,
        accelerations: List<ThreadAcceleration> = emptyList(),
        maxUid: Int
    ): GbtResult? {
        return if (useNative) {
            makeNative(nativePtr, mempool.toTypedArray(), accelerations.toTypedArray(), maxUid)
        } else {
            makeFallback(mempool, accelerations, maxUid)
        }
    }

    /**
     * Update GBT with mempool changes
     * @param newTxs New transactions to add
     * @param removeTxs Transaction UIDs to remove
     * @param accelerations List of fee accelerations (usually empty)
     * @param maxUid Maximum transaction UID seen
     * @return Updated GBT result
     */
    fun update(
        newTxs: List<ThreadTransaction> = emptyList(),
        removeTxs: List<Int> = emptyList(),
        accelerations: List<ThreadAcceleration> = emptyList(),
        maxUid: Int
    ): GbtResult? {
        return if (useNative) {
            updateNative(
                nativePtr,
                newTxs.toTypedArray(),
                removeTxs.toTypedArray(),
                accelerations.toTypedArray(),
                maxUid
            )
        } else {
            // For fallback, just do a full remake (not as efficient but works)
            makeFallback(newTxs, accelerations, maxUid)
        }
    }

    /**
     * Simple Kotlin fallback implementation
     * Not as sophisticated as the Rust implementation, but functional
     */
    private fun makeFallback(
        mempool: List<ThreadTransaction>,
        accelerations: List<ThreadAcceleration>,
        maxUid: Int
    ): GbtResult? {
        if (mempool.isEmpty()) return null

        try {
            // Apply accelerations
            val accelerationMap = accelerations.associateBy { it.uid }
            val adjustedMempool = mempool.map { tx ->
                val acceleration = accelerationMap[tx.uid]
                if (acceleration != null) {
                    val newFee = tx.fee + acceleration.delta
                    val newEffectiveFeePerVsize = newFee / (tx.weight / 4.0) // weight to vsize
                    tx.copy(fee = newFee, effectiveFeePerVsize = newEffectiveFeePerVsize)
                } else {
                    tx
                }
            }

            // Sort by effective fee per vsize (descending)
            val sortedTxs = adjustedMempool.sortedByDescending { it.effectiveFeePerVsize }

            val blocks = mutableListOf<IntArray>()
            val blockWeights = mutableListOf<Int>()
            val overflow = mutableListOf<Int>()

            var currentBlock = mutableListOf<Int>()
            var currentWeight = 0
            var blockCount = 0

            for (tx in sortedTxs) {
                if (blockCount >= maxBlocks) {
                    overflow.add(tx.uid)
                    continue
                }

                // Simple weight check (ignoring dependencies for fallback)
                if (currentWeight + tx.weight <= maxBlockWeight) {
                    currentBlock.add(tx.uid)
                    currentWeight += tx.weight
                } else {
                    // Start new block if we haven't hit the limit
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

            // Add the last block if it has transactions
            if (currentBlock.isNotEmpty()) {
                blocks.add(currentBlock.toIntArray())
                blockWeights.add(currentWeight)
            }

            return GbtResult(
                blocks = blocks.toTypedArray(),
                blockWeights = blockWeights.toIntArray(),
                clusters = emptyArray(), // Not implemented in fallback
                rates = emptyArray(),   // Not implemented in fallback
                overflow = overflow.toIntArray()
            )

        } catch (e: Exception) {
            android.util.Log.e("GbtGenerator", "Error in fallback implementation", e)
            return null
        }
    }

    /**
     * Clean up native resources
     */
    fun destroy() {
        if (useNative && nativePtr != 0L) {
            destroyNative(nativePtr)
        }
    }
}