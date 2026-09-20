package com.hackmit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hackmit.app.sensor.SensorSample
import com.hackmit.app.ui.theme.Coral
import com.hackmit.app.ui.theme.Green
import com.hackmit.app.ui.theme.Teal
import kotlin.math.min

/** Difference above this tints the Δ label coral. */
private const val DIFF_FLAG_DEG = 8f

/** Firmware maps pitch to 0 (arm down) through 60 (raised); 100 is the hard cap. */
private const val RAISED_DEG = 60f

/** Design canvas the silhouette is authored in. Always scaled uniformly. */
private const val DESIGN_W = 300f
private const val DESIGN_H = 500f
const val BODY_FIGURE_ASPECT = DESIGN_W / DESIGN_H

private fun fmtDeg(value: Float): String =
    if (value.isFinite()) String.format("%.1f°", value) else "—"

/**
 * Elevation of the drawn arm, in degrees away from hanging straight down: 0° maps to
 * down at the side, [RAISED_DEG] to straight out horizontally, and anything past that
 * lifts a little further so an over-raise still reads as different.
 */
private fun armElevation(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    return (degrees.coerceIn(0f, 100f) / RAISED_DEG * 90f).coerceAtMost(112f)
}

/**
 * Voxel-style block human on a 3:5 design box centered in [modifier]. One scale
 * factor only — fullscreen letterboxes instead of stretching.
 */
@Composable
fun BodyPoseFigure(
    leftDeg: Float,
    rightDeg: Float,
    modifier: Modifier = Modifier,
) {
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

    Canvas(modifier = modifier) {
        val scale = min(size.width / DESIGN_W, size.height / DESIGN_H)
        val origin = Offset(
            (size.width - DESIGN_W * scale) / 2f,
            (size.height - DESIGN_H * scale) / 2f,
        )

        fun pt(x: Float, y: Float) = Offset(origin.x + x * scale, origin.y + y * scale)
        fun block(left: Float, top: Float, width: Float, height: Float, color: Color) {
            drawRect(
                color = color,
                topLeft = pt(left, top),
                size = Size(width * scale, height * scale),
            )
        }

        val centerX = 150f
        val head = 80f
        val torsoW = 80f
        val torsoH = 130f
        val armW = 38f
        val armH = 110f
        val legW = 38f
        val legH = 190f
        val seam = 4f

        val headLeft = centerX - head / 2f
        val headTop = 50f
        val torsoLeft = centerX - torsoW / 2f
        val torsoTop = headTop + head - seam
        val shoulderY = torsoTop + 8f
        val legTop = torsoTop + torsoH - seam

        // Legs first, then torso/head, so overlapping blocks read as stacked voxels.
        block(torsoLeft, legTop, legW, legH, bodyColor)
        block(torsoLeft + torsoW - legW, legTop, legW, legH, bodyColor)
        block(torsoLeft, torsoTop, torsoW, torsoH, bodyColor)
        block(headLeft, headTop, head, head, bodyColor)

        drawBlockArm(
            degrees = leftDeg,
            side = -1f,
            color = Teal,
            centerX = centerX,
            torsoW = torsoW,
            shoulderY = shoulderY,
            armW = armW,
            armH = armH,
            scale = scale,
            origin = origin,
        )
        drawBlockArm(
            degrees = rightDeg,
            side = 1f,
            color = Green,
            centerX = centerX,
            torsoW = torsoW,
            shoulderY = shoulderY,
            armW = armW,
            armH = armH,
            scale = scale,
            origin = origin,
        )
    }
}

/**
 * Filled arm box hinged at the inner-top corner (the shoulder). Compose rotation
 * is clockwise, so the left arm swings out with a positive angle and the right
 * arm with a negative one.
 */
private fun DrawScope.drawBlockArm(
    degrees: Float,
    side: Float,
    color: Color,
    centerX: Float,
    torsoW: Float,
    shoulderY: Float,
    armW: Float,
    armH: Float,
    scale: Float,
    origin: Offset,
) {
    val elevation = armElevation(degrees)
    val shoulderX = centerX + side * (torsoW / 2f - 3f)
    val pivot = Offset(origin.x + shoulderX * scale, origin.y + shoulderY * scale)
    val angle = -side * elevation
    val armLeft = if (side < 0f) shoulderX - armW else shoulderX

    rotate(degrees = angle, pivot = pivot) {
        drawRect(
            color = color,
            topLeft = Offset(origin.x + armLeft * scale, origin.y + shoulderY * scale),
            size = Size(armW * scale, armH * scale),
        )
    }
}

/**
 * Bottom-of-screen pose card. Expand opens a fullscreen dialog; the figure stays
 * 3:5 and centered so it never fills the window by stretching.
 */
@Composable
fun ArmPoseSection(sample: SensorSample?, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val left = sample?.leftDeg ?: Float.NaN
    val right = sample?.rightDeg ?: Float.NaN
    val diff = sample?.diffDeg ?: Float.NaN

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Arm position", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = true }) { Text("Expand") }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                BodyPoseFigure(
                    leftDeg = left,
                    rightDeg = right,
                    modifier = Modifier.fillMaxSize(),
                )
                if (diff.isFinite()) {
                    DiffLabel(diff = diff, large = false, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Arm position", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { expanded = false }) { Text("Close") }
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val density = LocalDensity.current
                        val maxW = constraints.maxWidth.toFloat()
                        val maxH = constraints.maxHeight.toFloat()
                        val widthPx = min(maxW, maxH * BODY_FIGURE_ASPECT)
                        val heightPx = widthPx / BODY_FIGURE_ASPECT
                        Box(
                            modifier = Modifier.size(
                                width = with(density) { widthPx.toDp() },
                                height = with(density) { heightPx.toDp() },
                            ),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            BodyPoseFigure(
                                leftDeg = left,
                                rightDeg = right,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (diff.isFinite()) {
                                DiffLabel(diff = diff, large = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLabel(diff: Float, large: Boolean, modifier: Modifier = Modifier) {
    val flagged = diff.isFinite() && diff >= DIFF_FLAG_DEG
    Text(
        text = "Δ ${fmtDeg(diff)}",
        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (flagged) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp),
    )
}
