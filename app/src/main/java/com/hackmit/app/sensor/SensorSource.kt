package com.hackmit.app.sensor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.math.sin

/**
 * One dual-arm IMU reading from the raise-hands Arduino.
 *
 * [leftDeg] / [rightDeg] are mapped pitch (0 = down, higher = raised).
 * [diffDeg] is abs(left - right). Raw accel/gyro stay available for mock/debug.
 */
data class SensorSample(
    val timestampMs: Long,
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val leftDeg: Float = Float.NaN,
    val rightDeg: Float = Float.NaN,
    val diffDeg: Float = Float.NaN,
) {
    val accelMagnitude: Float get() = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
}

/**
 * Any hardware that can stream IMU data. Implementations must be safe to call
 * [connect] before collecting [samples].
 */
interface SensorSource {
    val displayName: String

    suspend fun connect(): Boolean

    /** Releases the link. Must be idempotent: screens tear down on exit and on demand. */
    fun disconnect()

    fun samples(): Flow<SensorSample>

    fun sendCommand(command: String): Boolean = false
}

private val ANG_LINE = Regex(
    """^ANG,L,(-?\d+(?:\.\d+)?|nan),R,(-?\d+(?:\.\d+)?|nan),DIFF,(-?\d+(?:\.\d+)?|nan)\s*$""",
    RegexOption.IGNORE_CASE,
)
private val HUMAN_LINE = Regex(
    """^L\s+(-?\d+(?:\.\d+)?|--)\s+R\s+(-?\d+(?:\.\d+)?|--)\s*$""",
    RegexOption.IGNORE_CASE,
)

internal fun parseOptionalDeg(raw: String): Float {
    val trimmed = raw.trim()
    if (trimmed.equals("nan", ignoreCase = true) || trimmed == "--" || trimmed.isEmpty()) {
        return Float.NaN
    }
    return trimmed.toFloatOrNull() ?: Float.NaN
}

/**
 * Parses firmware lines. Preferred:
 *   ANG,L,45.2,R,43.1,DIFF,2.1
 * Also accepts the older serial monitor form:
 *   L 45.2   R 43.1
 */
fun parseArmAngleLine(line: String, timestampMs: Long = System.currentTimeMillis()): SensorSample? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    ANG_LINE.matchEntire(trimmed)?.let { match ->
        val left = parseOptionalDeg(match.groupValues[1])
        val right = parseOptionalDeg(match.groupValues[2])
        var diff = parseOptionalDeg(match.groupValues[3])
        if (!diff.isFinite() && left.isFinite() && right.isFinite()) {
            diff = abs(left - right)
        }
        return SensorSample(timestampMs = timestampMs, leftDeg = left, rightDeg = right, diffDeg = diff)
    }

    HUMAN_LINE.matchEntire(trimmed)?.let { match ->
        val left = parseOptionalDeg(match.groupValues[1])
        val right = parseOptionalDeg(match.groupValues[2])
        val diff = if (left.isFinite() && right.isFinite()) abs(left - right) else Float.NaN
        return SensorSample(timestampMs = timestampMs, leftDeg = left, rightDeg = right, diffDeg = diff)
    }
    return null
}

/**
 * Synthetic source so the whole app is demoable without an Arduino.
 * [abnormal] injects a lagging left arm so the UI can show a positive screen.
 */
class MockSensorSource(private val abnormal: Boolean = false) : SensorSource {

    @Volatile
    private var streaming = false

    override val displayName: String = if (abnormal) "Mock IMU (abnormal)" else "Mock IMU (normal)"

    override suspend fun connect(): Boolean {
        delay(250)
        streaming = true
        return true
    }

    /** Ends the flow below so a disconnect leaves no collector ticking in the background. */
    override fun disconnect() {
        streaming = false
    }

    override fun sendCommand(command: String): Boolean = true

    override fun samples(): Flow<SensorSample> = flow {
        var t = 0L
        while (streaming) {
            val raise = 12f + 52f * (0.5f + 0.5f * sin(t / 2200.0)).toFloat()
            val jitterL = 0.7f * sin(t / 280.0).toFloat()
            val jitterR = 0.6f * sin(t / 310.0).toFloat()
            val left = if (abnormal) {
                raise - 16f - 5f * sin(t / 420.0).toFloat()
            } else {
                raise + jitterL
            }
            val right = raise + jitterR
            emit(
                SensorSample(
                    timestampMs = t,
                    ax = 0.05f * sin(t / 70.0).toFloat(),
                    ay = 0.04f * sin(t / 90.0).toFloat(),
                    az = 9.81f,
                    gx = jitterL,
                    gy = 0f,
                    gz = jitterR,
                    leftDeg = left.coerceAtLeast(0f),
                    rightDeg = right.coerceAtLeast(0f),
                    diffDeg = abs(left - right),
                ),
            )
            t += 50
            delay(50)
        }
    }
}

