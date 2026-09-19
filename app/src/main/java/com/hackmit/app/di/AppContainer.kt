package com.hackmit.app.di

import android.content.Context
import com.hackmit.app.data.SettingsStore
import com.hackmit.app.scoring.StrokeRiskScorer
import com.hackmit.app.sensor.SensorRepository

/**
 * Tiny manual dependency container. Swap for Hilt later if the project grows.
 */
class AppContainer(context: Context) {
    val settingsStore: SettingsStore = SettingsStore(context)
    val sensorRepository: SensorRepository = SensorRepository()
    val scorer: StrokeRiskScorer = StrokeRiskScorer()
}
