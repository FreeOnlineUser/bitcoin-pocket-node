package com.pocketnode.oracle

import org.json.JSONArray
import java.io.File
import java.util.TreeMap

/** The last-hour on-chain price as of one block. */
data class PricePoint(val height: Int, val time: Long, val price: Int)

/**
 * A week of on-chain prices, one per block, kept in a small file. A week fits
 * inside the prune window (2 GB is about 8 days of blocks), so any missing stretch
 * can always be rebuilt from blocks still on disk; see OracleUpdater's backfill.
 */
class PriceHistory(private val file: File) {

    companion object {
        const val KEEP_SECONDS = 7 * 24 * 3600L
    }

    private val points = TreeMap<Int, PricePoint>()

    @Synchronized
    fun load() {
        points.clear()
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                points[p.getInt(0)] = PricePoint(p.getInt(0), p.getLong(1), p.getInt(2))
            }
        } catch (_: Exception) {
            // A damaged file just means rebuilding the week from blocks.
            points.clear()
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        for (p in points.values) arr.put(JSONArray().put(p.height).put(p.time).put(p.price))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun add(p: PricePoint) {
        points[p.height] = p
    }

    @Synchronized
    fun has(height: Int) = points.containsKey(height)

    /** Drops points more than a week older than the newest one. */
    @Synchronized
    fun trim() {
        val newest = points.lastEntry()?.value?.time ?: return
        points.values.removeAll { it.time < newest - KEEP_SECONDS }
    }

    /**
     * Drops points far from the median of the blocks around them. A 6-block window
     * now and then reads double or half the price; one such point would otherwise
     * set the chart's scale for a week.
     */
    @Synchronized
    fun dropOutliers(maxDeviation: Double) {
        val list = points.values.toList()
        val bad = list.indices.filter { i ->
            val near = (maxOf(0, i - 6) until minOf(list.size, i + 7)).filter { it != i }.map { list[it].price }.sorted()
            near.size >= 4 && kotlin.math.abs(list[i].price.toDouble() / near[near.size / 2] - 1) > maxDeviation
        }
        bad.forEach { points.remove(list[it].height) }
    }

    @Synchronized
    fun all(): List<PricePoint> = points.values.toList()
}
