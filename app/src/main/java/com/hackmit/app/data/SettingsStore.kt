package com.hackmit.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hackmit.app.BuildConfig
import com.hackmit.app.alert.AlertConfig
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.sensor.SleepWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "stroke_settings")

class SettingsStore(private val context: Context) {

    private val keyDeepgram = stringPreferencesKey("deepgram_api_key")
    private val keyDeepgramProxy = stringPreferencesKey("deepgram_proxy_url")
    private val keyMock = booleanPreferencesKey("mock_sensors")
    private val keyMac = stringPreferencesKey("sensor_mac")
    private val keyTransport = stringPreferencesKey("sensor_transport")
    private val keyAlertGateway = stringPreferencesKey("alert_gateway_url")
    private val keyAlertGatewayToken = stringPreferencesKey("alert_gateway_token")
    private val keyTrustedContactName = stringPreferencesKey("trusted_contact_name")
    private val keyTrustedContactPhone = stringPreferencesKey("trusted_contact_phone")
    private val keyEmergencyNumber = stringPreferencesKey("emergency_number")

    private val keyConsent = booleanPreferencesKey("monitor_consent")
    private val keyMonitoring = booleanPreferencesKey("monitoring_enabled")
    private val keySensitivity = floatPreferencesKey("alert_sensitivity")
    private val keyContact = stringPreferencesKey("emergency_contact")
    private val keySms = booleanPreferencesKey("alert_sms_enabled")
    private val keyBuzzCooldown = floatPreferencesKey("buzz_cooldown_minutes")
    private val keySleepEnabled = booleanPreferencesKey("sleep_hours_enabled")
    private val keySleepStart = stringPreferencesKey("sleep_hours_start")
    private val keySleepEnd = stringPreferencesKey("sleep_hours_end")
    private val keyDebug = booleanPreferencesKey("debug_mode")
    private val keyDebugMockSummary = booleanPreferencesKey("debug_mock_summary")

    val deepgramKey: Flow<String> = context.dataStore.data.map {
        it[keyDeepgram]?.takeIf { k -> k.isNotBlank() } ?: BuildConfig.DEEPGRAM_API_KEY
    }
    val deepgramProxyUrl: Flow<String> = context.dataStore.data.map {
        it[keyDeepgramProxy]?.takeIf(String::isNotBlank) ?: BuildConfig.DEEPGRAM_PROXY_URL
    }
    val mockSensors: Flow<Boolean> = context.dataStore.data.map { it[keyMock] ?: true }
    val sensorMac: Flow<String> = context.dataStore.data.map { it[keyMac].orEmpty() }
    val sensorTransport: Flow<String> = context.dataStore.data.map { it[keyTransport] ?: "MOCK" }
    val alertConfig: Flow<AlertConfig> = context.dataStore.data.map {
        AlertConfig(
            gatewayUrl = it[keyAlertGateway]?.takeIf(String::isNotBlank)
                ?: BuildConfig.GATEWAY_BASE_URL.trimEnd('/') + "/v1/stroke-alerts",
            gatewayToken = it[keyAlertGatewayToken]?.takeIf(String::isNotBlank)
                ?: BuildConfig.GATEWAY_TOKEN,
            trustedContactName = it[keyTrustedContactName].orEmpty(),
            trustedContactPhone = it[keyTrustedContactPhone].orEmpty(),
            emergencyNumber = it[keyEmergencyNumber]?.takeIf(String::isNotBlank) ?: "911",
        )
    }

    val consentGranted: Flow<Boolean> = context.dataStore.data.map { it[keyConsent] ?: false }
    val monitoringEnabled: Flow<Boolean> = context.dataStore.data.map { it[keyMonitoring] ?: false }
    val alertSensitivity: Flow<Float> = context.dataStore.data.map { it[keySensitivity] ?: 0.55f }
    val emergencyContact: Flow<String> = context.dataStore.data.map { it[keyContact].orEmpty() }
    val alertSmsEnabled: Flow<Boolean> = context.dataStore.data.map { it[keySms] ?: false }
    val buzzCooldownMinutes: Flow<Float> = context.dataStore.data.map {
        it[keyBuzzCooldown] ?: 1.0f
    }

    /** Quiet hours during which the board must not buzz. Off until the user sets it. */
    val sleepHours: Flow<SleepWindow> = context.dataStore.data.map {
        SleepWindow(
            enabled = it[keySleepEnabled] ?: false,
            start = it[keySleepStart]?.takeIf(String::isNotBlank) ?: "22:00",
            end = it[keySleepEnd]?.takeIf(String::isNotBlank) ?: "07:00",
        )
    }

    /**
     * Debug mode: force standard simulated IMU data so every feature works without
     * connecting the Arduino. On by default; turn off to use the real board.
     */
    val debugMode: Flow<Boolean> = context.dataStore.data.map { it[keyDebug] ?: true }

    /**
     * Debug mode 2: show a "Skip to mock results" shortcut so end-of-flow features
     * (summary, risk check, alerts) can be tested without running the FAST tests.
     */
    val debugMockSummary: Flow<Boolean> = context.dataStore.data.map { it[keyDebugMockSummary] ?: true }

    suspend fun setDeepgramKey(value: String) {
        context.dataStore.edit { it[keyDeepgram] = value.trim() }
    }

    suspend fun setDeepgramProxyUrl(value: String) {
        context.dataStore.edit { it[keyDeepgramProxy] = value.trim() }
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

    /**
     * Writes mock + transport together so they cannot drift. [address] is optional
     * so a Mock tap does not wipe a saved MAC.
     */
    suspend fun setSensorSource(transport: SensorTransport, address: String? = null) {
        context.dataStore.edit {
            it[keyMock] = transport == SensorTransport.MOCK
            it[keyTransport] = transport.name
            if (address != null) it[keyMac] = address.trim()
        }
    }

    suspend fun setConsentGranted(value: Boolean) {
        context.dataStore.edit { it[keyConsent] = value }
    }

    suspend fun setMonitoringEnabled(value: Boolean) {
        context.dataStore.edit { it[keyMonitoring] = value }
    }

    suspend fun setAlertSensitivity(value: Float) {
        context.dataStore.edit { it[keySensitivity] = value.coerceIn(0.1f, 0.9f) }
    }

    suspend fun setEmergencyContact(value: String) {
        context.dataStore.edit { it[keyContact] = value.trim() }
    }

    suspend fun setAlertSmsEnabled(value: Boolean) {
        context.dataStore.edit { it[keySms] = value }
    }

    suspend fun setBuzzCooldownMinutes(value: Float) {
        context.dataStore.edit { it[keyBuzzCooldown] = value.coerceIn(0.01f, 1440f) }
    }

    suspend fun setSleepHours(value: SleepWindow) {
        context.dataStore.edit {
            it[keySleepEnabled] = value.enabled
            it[keySleepStart] = value.start.trim()
            it[keySleepEnd] = value.end.trim()
        }
    }

    suspend fun setDebugMode(value: Boolean) {
        context.dataStore.edit { it[keyDebug] = value }
    }

    suspend fun setDebugMockSummary(value: Boolean) {
        context.dataStore.edit { it[keyDebugMockSummary] = value }
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
