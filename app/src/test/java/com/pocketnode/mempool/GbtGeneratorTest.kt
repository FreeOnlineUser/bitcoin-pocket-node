package com.pocketnode.mempool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GbtGeneratorTest {
    private fun tx(uid: Int, feeRate: Double, weight: Int = 400_000) = ThreadTransaction(
        uid = uid, order = uid, fee = feeRate * weight / 4 / 100_000_000, weight = weight,
        sigops = 0, effectiveFeePerVsize = feeRate / 100_000_000, inputs = IntArray(0)
    )

    @Test
    fun packsByFeeRateIntoFullBlocks() {
        // 25 txs of a tenth of a block each: two full blocks and a half one.
        val mempool = (1..25).map { tx(it, feeRate = it.toDouble()) }
        val result = GbtGenerator.create(4_000_000, 8).make(mempool, maxUid = 26)!!

        assertEquals(3, result.blocks.size)
        assertArrayEquals((25 downTo 16).toList().toIntArray(), result.blocks[0])
        assertEquals(4_000_000, result.blockWeights[0])
        assertEquals(5, result.blocks[2].size)
    }

    @Test
    fun stopsAtMaxBlocks() {
        val mempool = (1..100).map { tx(it, feeRate = 5.0) }
        val result = GbtGenerator.create(4_000_000, 8).make(mempool, maxUid = 101)!!

        assertEquals(8, result.blocks.size)
        assertEquals(20, result.overflow.size)
    }
}
