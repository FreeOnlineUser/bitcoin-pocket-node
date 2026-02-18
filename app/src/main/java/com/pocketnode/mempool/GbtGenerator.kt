/*
 * AGPL-3.0 License
 * Portions of this code structure are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

package com.pocketnode.mempool

/**
 * JNI wrapper for the Rust GBT (getblocktemplate) algorithm
 * Ported from mempool.space's implementation
 */
class GbtGenerator private constructor(
    private val nativePtr: Long
) {
    companion object {
        init {
            System.loadLibrary("gbt")
            initNative()
        }

        /**
         * Create a new GBT generator
         * @param maxBlockWeight Maximum weight per block (typically 4000000)
         * @param maxBlocks Maximum number of blocks to project (typically 8)
         */
        fun create(maxBlockWeight: Int, maxBlocks: Int): GbtGenerator {
            val ptr = createNative(maxBlockWeight, maxBlocks)
            return GbtGenerator(ptr)
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
        return makeNative(nativePtr, mempool.toTypedArray(), accelerations.toTypedArray(), maxUid)
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
        return updateNative(
            nativePtr,
            newTxs.toTypedArray(),
            removeTxs.toTypedArray(),
            accelerations.toTypedArray(),
            maxUid
        )
    }

    /**
     * Clean up native resources
     */
    fun destroy() {
        if (nativePtr != 0L) {
            destroyNative(nativePtr)
        }
    }
}