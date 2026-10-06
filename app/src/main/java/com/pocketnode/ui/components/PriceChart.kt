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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketnode.oracle.PricePoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The week of on-chain prices: one last-hour figure per block, with day marks
 * at local midnight and the week's high and low.
 */
@Composable
fun PriceChart(points: List<PricePoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val line = Color(0xFFFF9800)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val label = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    val t0 = points.first().time
    val t1 = points.last().time
    val lo = points.minOf { it.price }
    val hi = points.maxOf { it.price }
    val pad = ((hi - lo) * 0.08).coerceAtLeast(1.0)
    val yMin = lo - pad
    val yMax = hi + pad

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
        Row(Modifier.fillMaxWidth()) {
            Text(
                "high $${"%,d".format(hi)}  low $${"%,d".format(lo)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = label
            )
        }
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * size.width
                fun y(p: Double) = size.height - ((p - yMin) / (yMax - yMin)).toFloat() * size.height
                for (d in days) {
                    drawLine(grid, Offset(x(d), 0f), Offset(x(d), size.height), strokeWidth = 1.dp.toPx())
                }
                val path = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(x(p.time), y(p.price.toDouble()))
                    else path.lineTo(x(p.time), y(p.price.toDouble()))
                }
                drawPath(path, line, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
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
                        if (span > 0.06f) dayName.format(Date(bounds[i] * 1000)) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = label,
                        modifier = Modifier.weight(span)
                    )
                }
            }
        }
    }
}
