package com.hackmit.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.hackmit.app.alert.AlertDelivery
import com.hackmit.app.alert.AlertDeliveryStatus
import com.hackmit.app.alert.AlertSource
import com.hackmit.app.di.AppContainer
import com.hackmit.app.domain.Assessment
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.sensor.BleScanReadiness
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.sensor.SleepWindow
import com.hackmit.app.sensor.resolveSensorTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MotorBaseline(val leftDeg: Float, val rightDeg: Float, val diffDeg: Float)

/** Where a sensor hookup attempt currently is, so the UI can name the failing stage. */
enum class SensorStage {
    IDLE,
    MOCK,
    PERMISSION_REQUIRED,
    BLUETOOTH_OFF,
    SCANNING,
    CONNECTING,
    LIVE,
    DISCONNECTED,
    NOT_FOUND,
    FAILED,
}

val SensorStage.label: String
    get() = when (this) {
        SensorStage.IDLE -> "Idle"
        SensorStage.MOCK -> "Mock data"
        SensorStage.PERMISSION_REQUIRED -> "Permission required"
        SensorStage.BLUETOOTH_OFF -> "Turn Bluetooth on"
        SensorStage.SCANNING -> "Scanning for StrokeSense…"
        SensorStage.CONNECTING -> "Connecting…"
        SensorStage.LIVE -> "Live"
        SensorStage.DISCONNECTED -> "Disconnected"
        SensorStage.NOT_FOUND -> "StrokeSense not found — is the board powered and running python/main.py?"
        SensorStage.FAILED -> "Could not connect"
    }

/**
 * Shared state for the whole assessment flow. Scoped to the Activity so results
 * survive navigation between calibration and test screens.
 */
class AssessmentViewModel(private val container: AppContainer) : ViewModel() {

    val sensorRepository get() = container.sensorRepository
    val settingsStore get() = container.settingsStore
    val speechMonitor get() = container.speechMonitor
    val alertManager get() = container.alertManager
    val baselineStore get() = container.baselineStore

    var results by mutableStateOf<Map<ModuleType, ModuleResult>>(emptyMap())
        private set

    var assessment by mutableStateOf<Assessment?>(null)
        private set

    var alertDelivery by mutableStateOf(AlertDelivery())
        private set

    var faceBaseline by mutableStateOf<Float?>(null)
    var speechBaselineWpm by mutableStateOf<Float?>(null)
    var motorBaseline by mutableStateOf<MotorBaseline?>(null)

    var sensorStage by mutableStateOf(SensorStage.IDLE)
        private set

    /** Human-readable form of [sensorStage], with the resolved MAC when we have one. */
    var sensorStatus by mutableStateOf(SensorStage.IDLE.label)
        private set

    /** Last MAC we tried or connected to; empty for mock. */
    var sensorAddress by mutableStateOf("")
        private set

    val isSensorStreaming: Boolean
        get() = sensorStage == SensorStage.LIVE || sensorStage == SensorStage.MOCK

    private fun stage(value: SensorStage, detail: String? = null) {
        sensorStage = value
        sensorStatus = if (detail.isNullOrBlank()) value.label else "${value.label} — $detail"
        when {
            value == SensorStage.MOCK -> sensorAddress = ""
            !detail.isNullOrBlank() -> sensorAddress = detail
        }
    }

    fun submit(result: ModuleResult) {
        results = results + (result.type to result)
        assessment = container.scorer.assess(results)
    }

    fun reset() {
        results = emptyMap()
        assessment = null
        faceBaseline = null
        speechBaselineWpm = null
        motorBaseline = null
        alertDelivery = AlertDelivery()
    }

    /**
     * Applies the saved Settings transport and optionally the demo "abnormal arms"
     * flag used by [MockSensorSource].
     *
     * For [SensorTransport.BLE] the board is a BLE-only peripheral that Android never
     * bonds, so there may be no saved MAC. In that case (or when a saved MAC is stale)
     * this runs a short scan for the StrokeSense advertisement and remembers what it
     * finds, which is what makes the board usable without system pairing.
     */
    suspend fun prepareMotorSensor(mockAbnormal: Boolean = false): Boolean {
        val useMock = settingsStore.mockSensors.first()
        val savedAddress = settingsStore.sensorMac.first().trim()
        val storedName = settingsStore.sensorTransport.first()
        val transport = resolveSensorTransport(useMock, storedName)
        if (transport == SensorTransport.MOCK && !useMock ||
            transport == SensorTransport.BLE && (useMock || storedName != SensorTransport.BLE.name)
        ) {
            settingsStore.setSensorSource(transport, savedAddress.ifBlank { null })
        }

        if (transport == SensorTransport.MOCK) {
            sensorRepository.select(SensorTransport.MOCK, savedAddress, mockAbnormal)
            val connected = sensorRepository.connect()
            stage(if (connected) SensorStage.MOCK else SensorStage.FAILED)
            if (connected) pushBoardSettings()
            return connected
        }

        if (sensorStage == SensorStage.LIVE &&
            sensorRepository.transport == SensorTransport.BLE &&
            savedAddress.isNotBlank() &&
            sensorRepository.lastAddress.equals(savedAddress, ignoreCase = true)
        ) {
            return true
        }

        when (sensorRepository.scanReadiness()) {
            BleScanReadiness.NO_PERMISSION -> {
                stage(SensorStage.PERMISSION_REQUIRED)
                return false
            }
            BleScanReadiness.BLUETOOTH_OFF -> {
                stage(SensorStage.BLUETOOTH_OFF)
                return false
            }
            BleScanReadiness.NO_SCANNER, BleScanReadiness.READY -> Unit
        }

        if (savedAddress.isNotBlank() && connectBle(savedAddress)) return true

        stage(SensorStage.SCANNING, savedAddress.ifBlank { null })
        val found = sensorRepository.findBleBoard()
        if (found == null) {
            stage(SensorStage.NOT_FOUND, savedAddress.ifBlank { null })
            return false
        }
        settingsStore.setSensorMac(found.address)
        return connectBle(found.address)
    }

    /**
     * Single Settings action: persist mock XOR BLE together, then connect BLE
     * (or switch to the mock stream).
     */
    suspend fun applySensorSource(transport: SensorTransport, address: String = ""): Boolean {
        settingsStore.setSensorSource(transport, address.takeIf { transport == SensorTransport.BLE })
        return prepareMotorSensor()
    }

    /** Persist Mock and start the demo stream. Tears down any board link. */
    fun chooseMock() {
        viewModelScope.launch {
            settingsStore.setSensorSource(SensorTransport.MOCK)
            sensorRepository.select(SensorTransport.MOCK)
            val connected = sensorRepository.connect()
            stage(if (connected) SensorStage.MOCK else SensorStage.FAILED)
        }
    }

    /** Persist BLE + mock=false. Drops any mock stream; connect via [prepareMotorSensor]. */
    fun chooseBle(address: String = "") {
        viewModelScope.launch {
            settingsStore.setSensorSource(SensorTransport.BLE, address.ifBlank { null })
            sensorRepository.select(SensorTransport.BLE, address)
            stage(SensorStage.IDLE, address.trim().ifBlank { null })
        }
    }

    private suspend fun connectBle(address: String): Boolean {
        stage(SensorStage.CONNECTING, address)
        sensorRepository.select(SensorTransport.BLE, address)
        val connected = sensorRepository.connect()
        if (connected) {
            stage(SensorStage.LIVE, address)
            pushBoardSettings()
        } else {
            stage(SensorStage.FAILED, address)
        }
        return connected
    }

    /** True while a real board link is up or still being opened — something to cancel. */
    val sensorLinkActive: Boolean
        get() = sensorStage == SensorStage.LIVE ||
            sensorStage == SensorStage.CONNECTING ||
            sensorStage == SensorStage.SCANNING

    /** Session the current link was opened under, see [releaseSensor]. */
    val sensorSessionId: Int get() = sensorRepository.sessionId

    /**
     * Deliberate disconnect. STOP is sent before the link is dropped so the board does
     * not keep buzzing, then the status reads [SensorStage.DISCONNECTED] instead of a
     * stale "Live". Callers stop collecting samples so the readout cannot look current.
     */
    fun disconnectSensor() {
        sensorRepository.stopAndDisconnect()
        stage(SensorStage.DISCONNECTED)
    }

    /**
     * Teardown for a screen leaving composition. Screens overlap during the navigation
     * transition, so only the screen that still owns [sessionId] may drop the link.
     */
    fun releaseSensor(sessionId: Int) {
        if (sessionId != sensorRepository.sessionId) return
        disconnectSensor()
    }

    /**
     * Board settings that live only in RAM on the board, so they have to be re-sent on
     * every fresh link. The gap keeps the two write-without-response frames apart.
     */
    private suspend fun pushBoardSettings() {
        val minutes = settingsStore.buzzCooldownMinutes.first()
        sensorRepository.sendCommand("SETCOOLDOWN,$minutes")
        delay(150)
        sensorRepository.sendCommand(settingsStore.sleepHours.first().command())
    }

    suspend fun setBuzzCooldownMinutes(minutes: Float): Boolean {
        val value = minutes.coerceIn(0.01f, 1440f)
        settingsStore.setBuzzCooldownMinutes(value)
        return sensorRepository.sendCommand("SETCOOLDOWN,$value")
    }

    /**
     * Saves quiet hours and pushes them to the board. Returns whether the board got the
     * command; when it did not, the window still ships on the next connect.
     */
    suspend fun setSleepWindow(window: SleepWindow): Boolean {
        settingsStore.setSleepHours(window)
        return sensorRepository.sendCommand(window.command())
    }

    fun startArmTest(): Boolean = sensorRepository.sendCommand("START")

    fun stopArmTest(): Boolean = sensorRepository.sendCommand("STOP")

    /** Sends a caregiver summary through the configured Linq gateway. */
    fun sendAssessmentAlert() {
        if (alertDelivery.status == AlertDeliveryStatus.SENDING) return
        alertDelivery = AlertDelivery(AlertDeliveryStatus.SENDING, "Sending alert…")
        viewModelScope.launch {
            alertDelivery = container.alertRepository.sendAssessmentSummary(assessment)
        }
    }

    /**
     * Opt-in entry point for a future Deepgram/Elastic callback. Call this only after
     * product policy has decided the external signal is worth sharing with the caregiver.
     */
    fun sendExternalAlert(
        source: AlertSource,
        title: String,
        summary: String,
        urgent: Boolean = false,
    ) {
        if (alertDelivery.status == AlertDeliveryStatus.SENDING) return
        alertDelivery = AlertDelivery(AlertDeliveryStatus.SENDING, "Sending alert…")
        viewModelScope.launch {
            alertDelivery = container.alertRepository.sendExternalSignal(source, title, summary, urgent)
        }
    }
}

class AssessmentViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return AssessmentViewModel(container) as T
    }
}
