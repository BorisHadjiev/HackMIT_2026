package com.hackmit.app.alert

/** Where an alert-worthy observation originated. ELASTIC is available for a future Elastic event feed. */
enum class AlertSource {
    ASSESSMENT,
    DEEPGRAM,
    ELASTIC,
    SYSTEM,
}

enum class AlertSeverity {
    INFORMATIONAL,
    URGENT,
}

enum class AlertKind {
    NO_TEST_DETECTED,
    ASSESSMENT_SUMMARY,
    EMERGENCY_RECOMMENDED,
    EXTERNAL_SIGNAL,
}

/** A transport-neutral message. This deliberately contains a summary, not raw audio or transcripts. */
data class AlertDraft(
    val id: String,
    val source: AlertSource,
    val kind: AlertKind,
    val severity: AlertSeverity,
    val title: String,
    val body: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/**
 * Configuration stored on device. The endpoint must be an app-owned backend which
 * owns the Linq API token; never place that credential in the APK.
 */
data class AlertConfig(
    val gatewayUrl: String = "",
    /** Demo-only shared gateway secret. Prefer per-user backend authentication in production. */
    val gatewayToken: String = "",
    val trustedContactName: String = "",
    val trustedContactPhone: String = "",
    val emergencyNumber: String = "911",
)

enum class AlertDeliveryStatus {
    IDLE,
    SENDING,
    SENT,
    NOT_CONFIGURED,
    FAILED,
}

data class AlertDelivery(
    val status: AlertDeliveryStatus = AlertDeliveryStatus.IDLE,
    val message: String = "",
    val traceId: String? = null,
)
