package com.hackmit.app.di

import android.content.Context
import com.hackmit.app.alerts.AlertManager
import com.hackmit.app.audio.SpeechMonitor
import com.hackmit.app.data.BaselineStore
import com.hackmit.app.data.SettingsStore
import com.hackmit.app.scoring.StrokeRiskScorer
import com.hackmit.app.sensor.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny manual dependency container. Swap for Hilt later if the project grows.
 */
class AppContainer(context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore: SettingsStore = SettingsStore(context)
    val sensorRepository: SensorRepository = SensorRepository()
    val scorer: StrokeRiskScorer = StrokeRiskScorer()
    val baselineStore: BaselineStore = BaselineStore(context)
    val alertManager: AlertManager = AlertManager(context, settingsStore, appScope)
    val speechMonitor: SpeechMonitor =
        SpeechMonitor(appScope, settingsStore, baselineStore, alertManager)
}
