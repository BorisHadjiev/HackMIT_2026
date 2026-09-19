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
import kotlinx.coroutines.launch

data class MotorBaseline(val meanMagnitude: Float, val tremorEnergy: Float)

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
