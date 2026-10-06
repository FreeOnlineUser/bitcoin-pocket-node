package com.pocketnode.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketnode.oracle.PricePoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The week of on-chain prices: one dot per block (its last-hour figure), with
 * day marks at local midnight and the week's high and low. [live], the mempool estimate,
 * shows as a grey hollow marker just past the last block, apart from the mined prices.
 */
@Composable
fun PriceChart(points: List<PricePoint>, modifier: Modifier = Modifier, live: Int? = null) {
    if (points.size < 2) return
    val line = Color(0xFFFF9800)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val label = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val liveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    val t0 = points.first().time
    val tLast = points.last().time
    // With a live estimate, leave a strip on the right for it: it's a guess about
    // the next blocks, not a mined price, so it stands apart from the dots.
    val t1 = if (live != null) tLast + ((tLast - t0) * 0.05).toLong().coerceAtLeast(600) else tLast
    val lo = points.minOf { it.price }
    val hi = points.maxOf { it.price }
    val yLo = minOf(lo, live ?: lo)
    val yHi = maxOf(hi, live ?: hi)
    val pad = ((yHi - yLo) * 0.03).coerceAtLeast(1.0)
    val yMin = yLo - pad
    val yMax = yHi + pad

    // Local midnights inside the range, for day marks.
    val days = mutableListOf<Long>()
    Calendar.getInstance().apply {
        timeInMillis = t0 * 1000
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
        while (timeInMillis / 1000 < t1) {
            days.add(timeInMillis / 1000)
            add(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val dayName = SimpleDateFormat("EEE", Locale.getDefault())

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * size.width
                // Top and bottom bands stay clear of dots for the high and low labels.
                val band = 16.dp.toPx()
                fun y(p: Double) = size.height - band - ((p - yMin) / (yMax - yMin)).toFloat() * (size.height - 2 * band)
                for (d in days) {
                    drawLine(grid, Offset(x(d), 0f), Offset(x(d), size.height), strokeWidth = 1.dp.toPx())
                }
                // One dot per block.
                val r = 1.5.dp.toPx()
                for (p in points) drawCircle(line, radius = r, center = Offset(x(p.time), y(p.price.toDouble())))
                if (live != null) {
                    // Where mined prices end.
                    drawLine(
                        grid, Offset(x(tLast) + 3.dp.toPx(), 0f), Offset(x(tLast) + 3.dp.toPx(), size.height),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    )
                    drawCircle(
                        liveColor,
                        radius = 3.5.dp.toPx(),
                        center = Offset(x(t1) - 5.dp.toPx(), y(live.toDouble())),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
            // Fixed corner labels, one line each, so nothing reflows as prices change.
            val small = MaterialTheme.typography.labelSmall
            Text("$${"%,d".format(hi)}", style = small, fontFamily = FontFamily.Monospace, color = label,
                maxLines = 1, modifier = Modifier.align(Alignment.TopStart))
            Text("$${"%,d".format(lo)}", style = small, fontFamily = FontFamily.Monospace, color = label,
                maxLines = 1, modifier = Modifier.align(Alignment.BottomStart))
            live?.let {
                Text("live $${"%,d".format(it)}", style = small, fontFamily = FontFamily.Monospace,
                    color = liveColor, maxLines = 1, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        // Day labels under each day's span.
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            val bounds = listOf(t0) + days + listOf(t1)
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until bounds.size - 1) {
                    val span = (bounds[i + 1] - bounds[i]).toFloat() / (t1 - t0).coerceAtLeast(1)
                    if (span <= 0f) continue
                    Text(
                        if (span > 0.08f) dayName.format(Date(bounds[i] * 1000)) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = label,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(span)
                    )
                }
            }
        }
    }
}
