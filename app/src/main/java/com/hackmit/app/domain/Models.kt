package com.hackmit.app.domain

enum class ModuleType(val displayName: String) {
    FACE("Facial symmetry"),
    SPEECH("Speech"),
    MOTOR("Motor / balance"),
}

/** A single measurement surfaced in the results UI. [severity] is 0f (normal) .. 1f (very abnormal). */
data class Metric(
    val label: String,
    val value: String,
    val severity: Float,
)

/** Result of one module. [score] is 0f (normal) .. 1f (highly abnormal) versus the user's baseline. */
data class ModuleResult(
    val type: ModuleType,
    val score: Float,
    val metrics: List<Metric>,
    val summary: String,
    val usedMockData: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis(),
)

enum class RiskBand(val label: String, val advice: String) {
    LOW("Low concern", "No strong asymmetry detected. Re-test if symptoms develop."),
    MODERATE("Monitor closely", "Some asymmetry detected. Seek medical advice if it persists or worsens."),
    HIGH("Seek help now", "Significant asymmetry detected. Call emergency services immediately."),
}

data class Assessment(
    val results: Map<ModuleType, ModuleResult>,
    val overallScore: Float,
    val band: RiskBand,
)
