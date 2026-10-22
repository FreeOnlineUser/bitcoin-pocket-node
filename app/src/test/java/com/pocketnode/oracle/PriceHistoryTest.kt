package com.pocketnode.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PriceHistoryTest {

    @Test
    fun savesLoadsAndKeepsOneWeek() {
        val file = File.createTempFile("history", ".json").apply { delete() }
        try {
            val h = PriceHistory(file)
            val now = 1_800_000_000L
            h.add(PricePoint(100, now - PriceHistory.KEEP_SECONDS - 1, 60_000))
            h.add(PricePoint(101, now - PriceHistory.KEEP_SECONDS, 61_000))
            h.add(PricePoint(102, now, 62_000))
            h.trim()
            h.save()

            val loaded = PriceHistory(file).apply { load() }
            assertFalse(loaded.has(100))
            assertTrue(loaded.has(101))
            assertEquals(listOf(101, 102), loaded.all().map { it.height })
            assertEquals(62_000, loaded.all().last().price)
        } finally {
            file.delete()
        }
    }

    @Test
    fun damagedFileLoadsEmpty() {
        val file = File.createTempFile("history", ".json").apply { writeText("[[1,2") }
        try {
            assertTrue(PriceHistory(file).apply { load() }.all().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun dropsDoubledPointButKeepsRealMove() {
        val h = PriceHistory(File.createTempFile("history", ".json").apply { delete() })
        // Flat at 82k, one block reads double, then the price steps down 20% and stays.
        for (i in 0 until 40) {
            val price = when {
                i == 15 -> 164_000
                i >= 25 -> 65_600
                else -> 82_000
            }
            h.add(PricePoint(1000 + i, i * 600L, price))
        }
        h.dropOutliers(0.3, 0.4)
        assertFalse(h.has(1015))
        assertEquals(39, h.all().size)
    }

    @Test
    fun dropsDoubledRunBesideAGap() {
        val h = PriceHistory(File.createTempFile("history", ".json").apply { delete() })
        // Eight doubled points, then two days missing, then a normal stretch.
        for (i in 0 until 8) h.add(PricePoint(1000 + i, i * 600L, 164_000))
        for (i in 0 until 60) h.add(PricePoint(1300 + i, 200_000L + i * 600L, 82_000 + i * 10))
        h.dropOutliers(0.3, 0.4)
        assertTrue(h.all().all { it.price < 100_000 })
        assertEquals(60, h.all().size)
    }
}
