package com.hackmit.app.alert

import com.hackmit.app.data.SettingsStore
import com.hackmit.app.domain.Assessment
import kotlinx.coroutines.flow.first

/** Coordinates configuration, message creation, and delivery without exposing Linq to the UI. */
class AlertRepository(
    private val settingsStore: SettingsStore,
    private val messageFactory: AlertMessageFactory = AlertMessageFactory(),
    private val gateway: AlertGateway = LinqAlertGateway(),
) {
    suspend fun sendAssessmentSummary(assessment: Assessment?): AlertDelivery {
        val draft = assessment?.let(messageFactory::assessmentSummary) ?: messageFactory.noTestDetected()
        return gateway.deliver(draft, settingsStore.alertConfig.first())
    }

    suspend fun sendExternalSignal(
        source: AlertSource,
        title: String,
        summary: String,
        urgent: Boolean = false,
    ): AlertDelivery = gateway.deliver(
        messageFactory.externalSignal(source, title, summary, urgent),
        settingsStore.alertConfig.first(),
    )
}
