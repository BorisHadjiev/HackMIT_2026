package com.hackmit.app.scoring

import com.hackmit.app.domain.Assessment
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.domain.RiskBand

/**
 * Combines per-module abnormality scores into one risk band.
 *
 * Weights follow the FAST emphasis: face and speech are the most specific stroke signs,
 * motor adds supporting evidence. Tune these once real data exists.
 */
class StrokeRiskScorer(
    private val weights: Map<ModuleType, Float> = mapOf(
        ModuleType.FACE to 0.4f,
        ModuleType.SPEECH to 0.4f,
        ModuleType.MOTOR to 0.2f,
    ),
) {

    fun assess(results: Map<ModuleType, ModuleResult>): Assessment {
        var weighted = 0f
        var totalWeight = 0f
        results.forEach { (type, result) ->
            val w = weights[type] ?: 0f
            weighted += result.score * w
            totalWeight += w
        }
        val overall = if (totalWeight == 0f) 0f else (weighted / totalWeight).coerceIn(0f, 1f)
        return Assessment(results = results, overallScore = overall, band = bandFor(overall))
    }

    fun bandFor(score: Float): RiskBand = when {
        score >= 0.66f -> RiskBand.HIGH
        score >= 0.33f -> RiskBand.MODERATE
        else -> RiskBand.LOW
    }
}
