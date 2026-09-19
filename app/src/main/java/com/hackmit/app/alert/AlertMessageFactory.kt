package com.hackmit.app.alert

import com.hackmit.app.domain.Assessment
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.RiskBand
import java.util.UUID

/** Builds concise, human-readable messages for Linq or any future alert provider. */
class AlertMessageFactory {

    fun noTestDetected(): AlertDraft = AlertDraft(
        id = UUID.randomUUID().toString(),
        source = AlertSource.SYSTEM,
        kind = AlertKind.NO_TEST_DETECTED,
        severity = AlertSeverity.INFORMATIONAL,
        title = "StrokeSense: no completed test detected",
        body = "No completed screening results are available yet. This is not a medical assessment.",
    )

    fun assessmentSummary(assessment: Assessment): AlertDraft {
        val isUrgent = assessment.band == RiskBand.HIGH
        val findings = assessment.results.values
            .sortedByDescending(ModuleResult::score)
            .joinToString(separator = "; ") { result ->
                "${result.type.displayName}: ${(result.score * 100).toInt()}% concern (${result.summary})"
            }
            .ifBlank { "No completed modules." }

        return AlertDraft(
            id = UUID.randomUUID().toString(),
            source = AlertSource.ASSESSMENT,
            kind = if (isUrgent) AlertKind.EMERGENCY_RECOMMENDED else AlertKind.ASSESSMENT_SUMMARY,
            severity = if (isUrgent) AlertSeverity.URGENT else AlertSeverity.INFORMATIONAL,
            title = if (isUrgent) {
                "URGENT: StrokeSense recommends emergency help"
            } else {
                "StrokeSense screening summary: ${assessment.band.label}"
            },
            body = buildString {
                append("Screening score: ${(assessment.overallScore * 100).toInt()}%. ")
                append(findings)
                if (isUrgent) {
                    append(" Seek emergency help now. This screening result is not a diagnosis.")
                } else {
                    append(" This screening result is not a diagnosis.")
                }
            },
        )
    }

    /** Entry point for Deepgram, Elastic, or another integration to report a pre-summarized event. */
    fun externalSignal(
        source: AlertSource,
        title: String,
        summary: String,
        urgent: Boolean = false,
    ): AlertDraft = AlertDraft(
        id = UUID.randomUUID().toString(),
        source = source,
        kind = AlertKind.EXTERNAL_SIGNAL,
        severity = if (urgent) AlertSeverity.URGENT else AlertSeverity.INFORMATIONAL,
        title = title,
        body = summary,
    )
}
