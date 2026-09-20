package com.hackmit.app.audio

import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.domain.BaselineProfile
import com.hackmit.app.domain.FeatureVector
import com.hackmit.app.domain.SlurAssessment
import kotlin.math.abs

/**
 * Compares live features against the personal baseline and raises a slur assessment.
 *
 * Design notes:
 *  - Only *diagnostic* features count. Loudness, brightness and zero-crossing rate are
 *    dominated by mic position/environment, so they are excluded — otherwise simply
 *    speaking louder than during calibration would raise the score.
 *  - Each feature is directional: e.g. higher jitter/shimmer and lower HNR/confidence
 *    are abnormal, while a lower-than-baseline jitter is not.
 *  - Z-scores are capped so one noisy feature cannot saturate the score.
 *  - A smoothed score plus CUSUM requires a sustained change before alerting.
 */
class SlurDetector(
    private val baseline: BaselineProfile?,
    sensitivity: Float = 0.55f,
) {

    private enum class Direction { HIGH_ABNORMAL, LOW_ABNORMAL, TWO_SIDED }

    private data class FeatureSpec(val key: String, val weight: Float, val direction: Direction)

    var sensitivity: Float = sensitivity
        set(value) {
            field = value.coerceIn(0.1f, 0.9f)
            recomputeThresholds()
        }

    private var warnThreshold = 0.48f
    private var alertThreshold = 0.72f

    private var ewma = 0f
    private var cusum = 0f
    private var aboveCount = 0
    private var lastAlertMs = 0L

    init {
        recomputeThresholds()
    }

    private fun recomputeThresholds() {
        warnThreshold = (0.75f - 0.5f * sensitivity).coerceIn(0.2f, 0.7f)
        alertThreshold = (1.0f - 0.5f * sensitivity).coerceIn(0.4f, 0.92f)
    }

    fun reset() {
        ewma = 0f
        cusum = 0f
        aboveCount = 0
    }

    fun update(features: FeatureVector): SlurAssessment {
        val profile = baseline
            ?: return SlurAssessment(0f, AlertLevel.NORMAL, listOf("No baseline yet"))
        if (profile.sampleCount < MIN_BASELINE_SAMPLES) {
            return SlurAssessment(0f, AlertLevel.NORMAL, listOf("Baseline too small — recalibrate"))
        }

        val values = features.toMap()
        val contributions = mutableListOf<Pair<String, Float>>()
        var weightedSum = 0f
        var weightSum = 0f

        SPECS.forEach { spec ->
            val value = values[spec.key] ?: return@forEach
            val mean = profile.means[spec.key] ?: return@forEach
            val std = (profile.stds[spec.key] ?: 0f).coerceAtLeast(minStdFor(spec.key))
            val z = (value - mean) / std
            if (!z.isFinite()) return@forEach

            val directed = when (spec.direction) {
                Direction.HIGH_ABNORMAL -> z
                Direction.LOW_ABNORMAL -> -z
                Direction.TWO_SIDED -> abs(z)
            }
            // Only the abnormal direction contributes; cap to avoid saturation.
            val positive = directed.coerceIn(0f, MAX_Z)
            weightedSum += positive * spec.weight
            weightSum += spec.weight
            contributions += spec.key to positive
        }

        if (weightSum == 0f) {
            return SlurAssessment(0f, AlertLevel.NORMAL, listOf("Baseline incomplete"))
        }

        val zMean = weightedSum / weightSum
        val raw = (zMean / SCORE_Z_AT_MAX).coerceIn(0f, 1f)
        ewma = EWMA_ALPHA * raw + (1f - EWMA_ALPHA) * ewma

        cusum = (cusum + (ewma - warnThreshold)).coerceAtLeast(0f)
        aboveCount = if (ewma >= warnThreshold) aboveCount + 1 else 0

        val now = System.currentTimeMillis()
        val level = when {
            ewma >= alertThreshold && aboveCount >= 2 && now - lastAlertMs >= ALERT_COOLDOWN_MS -> {
                lastAlertMs = now
                cusum = 0f
                aboveCount = 0
                AlertLevel.ALERT
            }
            ewma >= warnThreshold -> AlertLevel.WARNING
            else -> AlertLevel.NORMAL
        }

        val reasons = contributions
            .filter { it.second >= 1.0f }
            .sortedByDescending { it.second }
            .take(3)
            .map { (key, z) -> "${FeatureVector.LABELS[key] ?: key} shifted (z=${"%.1f".format(z)})" }
            .ifEmpty { listOf("Speech pattern within baseline") }

        return SlurAssessment(score = ewma, level = level, reasons = reasons, timestampMs = now, raw = raw)
    }

    private fun minStdFor(key: String): Float = when (key) {
        FeatureVector.KEY_JITTER -> 0.01f
        FeatureVector.KEY_SHIMMER -> 0.03f
        FeatureVector.KEY_HNR -> 3f
        FeatureVector.KEY_CONFIDENCE -> 0.05f
        FeatureVector.KEY_PAUSE_RATIO -> 0.05f
        FeatureVector.KEY_WPM -> 12f
        FeatureVector.KEY_EMS4HZ -> 0.02f
        FeatureVector.KEY_F0_STD -> 5f
        else -> 1f
    }

    companion object {
        private const val ALERT_COOLDOWN_MS = 300_000L
        private const val EWMA_ALPHA = 0.3f
        private const val MAX_Z = 4f
        private const val SCORE_Z_AT_MAX = 2.5f
        private const val MIN_BASELINE_SAMPLES = 5

        private val SPECS = listOf(
            FeatureSpec(FeatureVector.KEY_JITTER, 1.6f, Direction.HIGH_ABNORMAL),
            FeatureSpec(FeatureVector.KEY_SHIMMER, 1.6f, Direction.HIGH_ABNORMAL),
            FeatureSpec(FeatureVector.KEY_HNR, 1.2f, Direction.LOW_ABNORMAL),
            FeatureSpec(FeatureVector.KEY_CONFIDENCE, 1.6f, Direction.LOW_ABNORMAL),
            FeatureSpec(FeatureVector.KEY_PAUSE_RATIO, 1.0f, Direction.HIGH_ABNORMAL),
            FeatureSpec(FeatureVector.KEY_WPM, 0.8f, Direction.TWO_SIDED),
            FeatureSpec(FeatureVector.KEY_EMS4HZ, 0.6f, Direction.TWO_SIDED),
            FeatureSpec(FeatureVector.KEY_F0_STD, 0.5f, Direction.TWO_SIDED),
        )
    }
}
