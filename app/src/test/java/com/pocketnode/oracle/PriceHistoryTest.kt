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
}
