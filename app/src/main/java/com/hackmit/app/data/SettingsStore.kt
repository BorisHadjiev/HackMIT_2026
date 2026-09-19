package com.hackmit.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hackmit.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "stroke_settings")

class SettingsStore(private val context: Context) {

    private val keyDeepgram = stringPreferencesKey("deepgram_api_key")
    private val keyMock = booleanPreferencesKey("mock_sensors")
    private val keyMac = stringPreferencesKey("sensor_mac")
    private val keyTransport = stringPreferencesKey("sensor_transport")

    val deepgramKey: Flow<String> = context.dataStore.data.map {
        it[keyDeepgram]?.takeIf { k -> k.isNotBlank() } ?: BuildConfig.DEEPGRAM_API_KEY
    }
    val mockSensors: Flow<Boolean> = context.dataStore.data.map { it[keyMock] ?: true }
    val sensorMac: Flow<String> = context.dataStore.data.map { it[keyMac].orEmpty() }
    val sensorTransport: Flow<String> = context.dataStore.data.map { it[keyTransport] ?: "MOCK" }

    suspend fun setDeepgramKey(value: String) {
        context.dataStore.edit { it[keyDeepgram] = value.trim() }
    }

    suspend fun setMockSensors(value: Boolean) {
        context.dataStore.edit { it[keyMock] = value }
    }

    suspend fun setSensorMac(value: String) {
        context.dataStore.edit { it[keyMac] = value.trim() }
    }

    suspend fun setSensorTransport(value: String) {
        context.dataStore.edit { it[keyTransport] = value }
    }
}
