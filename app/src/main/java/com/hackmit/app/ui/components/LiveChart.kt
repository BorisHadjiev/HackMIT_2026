package com.hackmit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Minimal line chart drawn on a Canvas so we don't pull in a charting library.
 */
@Composable
fun LiveChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    secondary: List<Float> = emptyList(),
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary,
    tertiary: List<Float> = emptyList(),
    tertiaryColor: Color = MaterialTheme.colorScheme.secondary,
) {
    Canvas(modifier = modifier) {
        val series = listOf(
            values to lineColor,
            secondary to secondaryColor,
            tertiary to tertiaryColor,
        ).filter { it.first.size >= 2 }
        if (series.isEmpty()) return@Canvas

        val all = series.flatMap { it.first }
        val maxV = all.maxOrNull() ?: 1f
        val minV = all.minOrNull() ?: 0f
        val range = (maxV - minV).coerceAtLeast(0.001f)

        series.forEach { (points, color) ->
            val stepX = size.width / (points.size - 1)
            val path = Path()
            points.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - ((v - minV) / range) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 4f))
        }
    }
}
