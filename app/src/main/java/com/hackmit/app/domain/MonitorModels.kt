package com.hackmit.app.domain

/**
 * A combined acoustic + lexical feature snapshot. Acoustic fields come from the
 * on-device DSP pipeline; lexical fields come from Deepgram word results.
 */
data class FeatureVector(
    val f0Mean: Float = 0f,
    val f0Std: Float = 0f,
    val jitter: Float = 0f,
    val shimmer: Float = 0f,
    val hnr: Float = 0f,
    val rms: Float = 0f,
    val zcr: Float = 0f,
    val centroid: Float = 0f,
    val ems4hz: Float = 0f,
    val wpm: Float = 0f,
    val confidence: Float = 0f,
    val pauseRatio: Float = 0f,
    val fillerRatio: Float = 0f,
) {
    fun toMap(): Map<String, Float> = mapOf(
        KEY_F0_MEAN to f0Mean,
        KEY_F0_STD to f0Std,
        KEY_JITTER to jitter,
        KEY_SHIMMER to shimmer,
        KEY_HNR to hnr,
        KEY_RMS to rms,
        KEY_ZCR to zcr,
        KEY_CENTROID to centroid,
        KEY_EMS4HZ to ems4hz,
        KEY_WPM to wpm,
        KEY_CONFIDENCE to confidence,
        KEY_PAUSE_RATIO to pauseRatio,
        KEY_FILLER_RATIO to fillerRatio,
    )

    companion object {
        const val KEY_F0_MEAN = "f0Mean"
        const val KEY_F0_STD = "f0Std"
        const val KEY_JITTER = "jitter"
        const val KEY_SHIMMER = "shimmer"
        const val KEY_HNR = "hnr"
        const val KEY_RMS = "rms"
        const val KEY_ZCR = "zcr"
        const val KEY_CENTROID = "centroid"
        const val KEY_EMS4HZ = "ems4hz"
        const val KEY_WPM = "wpm"
        const val KEY_CONFIDENCE = "confidence"
        const val KEY_PAUSE_RATIO = "pauseRatio"
        const val KEY_FILLER_RATIO = "fillerRatio"

        val LABELS: Map<String, String> = mapOf(
            KEY_F0_MEAN to "Pitch",
            KEY_F0_STD to "Pitch variability",
            KEY_JITTER to "Jitter",
            KEY_SHIMMER to "Shimmer",
            KEY_HNR to "Harmonics/noise",
            KEY_RMS to "Loudness",
            KEY_ZCR to "Zero crossings",
            KEY_CENTROID to "Brightness",
            KEY_EMS4HZ to "Rhythm (4 Hz)",
            KEY_WPM to "Speech rate",
            KEY_CONFIDENCE to "ASR confidence",
            KEY_PAUSE_RATIO to "Pause ratio",
            KEY_FILLER_RATIO to "Filler ratio",
        )
    }
}

/** Personal baseline: per-feature mean and std, captured during calibration. */
data class BaselineProfile(
    val createdAtMs: Long,
    val sampleCount: Int,
    val means: Map<String, Float>,
    val stds: Map<String, Float>,
)

enum class AlertLevel { NORMAL, WARNING, ALERT }

data class SlurAssessment(
    val score: Float,
    val level: AlertLevel,
    val reasons: List<String>,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class MonitorState(
    val running: Boolean = false,
    val speechActive: Boolean = false,
    val transcript: String = "",
    val features: FeatureVector = FeatureVector(),
    val score: Float = 0f,
    val level: AlertLevel = AlertLevel.NORMAL,
    val reasons: List<String> = emptyList(),
    val deepgramStatus: String = "Idle",
    val lastError: String? = null,
    val alertCountdownSec: Int? = null,
)
