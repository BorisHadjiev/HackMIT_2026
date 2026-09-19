package com.hackmit.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hackmit.app.BuildConfig
import com.hackmit.app.alert.AlertConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "stroke_settings")

class SettingsStore(private val context: Context) {

    private val keyDeepgram = stringPreferencesKey("deepgram_api_key")
    private val keyMock = booleanPreferencesKey("mock_sensors")
    private val keyMac = stringPreferencesKey("sensor_mac")
    private val keyTransport = stringPreferencesKey("sensor_transport")
    private val keyAlertGateway = stringPreferencesKey("alert_gateway_url")
    private val keyAlertGatewayToken = stringPreferencesKey("alert_gateway_token")
    private val keyTrustedContactName = stringPreferencesKey("trusted_contact_name")
    private val keyTrustedContactPhone = stringPreferencesKey("trusted_contact_phone")
    private val keyEmergencyNumber = stringPreferencesKey("emergency_number")

    val deepgramKey: Flow<String> = context.dataStore.data.map {
        it[keyDeepgram]?.takeIf { k -> k.isNotBlank() } ?: BuildConfig.DEEPGRAM_API_KEY
    }
    val mockSensors: Flow<Boolean> = context.dataStore.data.map { it[keyMock] ?: true }
    val sensorMac: Flow<String> = context.dataStore.data.map { it[keyMac].orEmpty() }
    val sensorTransport: Flow<String> = context.dataStore.data.map { it[keyTransport] ?: "MOCK" }
    val alertConfig: Flow<AlertConfig> = context.dataStore.data.map {
        AlertConfig(
            gatewayUrl = it[keyAlertGateway].orEmpty(),
            gatewayToken = it[keyAlertGatewayToken].orEmpty(),
            trustedContactName = it[keyTrustedContactName].orEmpty(),
            trustedContactPhone = it[keyTrustedContactPhone].orEmpty(),
            emergencyNumber = it[keyEmergencyNumber]?.takeIf(String::isNotBlank) ?: "911",
        )
    }

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

    suspend fun setAlertConfig(value: AlertConfig) {
        context.dataStore.edit {
            it[keyAlertGateway] = value.gatewayUrl.trim()
            it[keyAlertGatewayToken] = value.gatewayToken.trim()
            it[keyTrustedContactName] = value.trustedContactName.trim()
            it[keyTrustedContactPhone] = value.trustedContactPhone.trim()
            it[keyEmergencyNumber] = value.emergencyNumber.trim()
        }
    }
}
