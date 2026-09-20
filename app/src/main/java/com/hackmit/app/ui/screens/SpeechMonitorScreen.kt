package com.hackmit.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.audio.AudioCapture
import com.hackmit.app.audio.SlurServer
import com.hackmit.app.audio.Speaker
import com.hackmit.app.audio.Wav
import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.severityColor
import com.hackmit.app.ui.components.rememberPermissionState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SpeechMonitorScreen(vm: AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()
    val state by vm.speechMonitor.state.collectAsState()
    val consent by vm.settingsStore.consentGranted.collectAsState(initial = false)
    val sensitivity by vm.settingsStore.alertSensitivity.collectAsState(initial = 0.55f)
    val contact by vm.settingsStore.emergencyContact.collectAsState(initial = "")
    val smsEnabled by vm.settingsStore.alertSmsEnabled.collectAsState(initial = false)
    val countdown by vm.alertManager.countdown.collectAsState()

    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    var hasBaseline by remember { mutableStateOf(vm.baselineStore.load() != null) }
    var calibrating by remember { mutableStateOf(false) }
    var calibMessage by remember { mutableStateOf<String?>(null) }
    var enrolling by remember { mutableStateOf(false) }
    var enrollMessage by remember { mutableStateOf<String?>(null) }
    var aiTestBusy by remember { mutableStateOf(false) }
    var aiTestScore by remember { mutableStateOf<Float?>(null) }
    var aiTestMessage by remember { mutableStateOf<String?>(null) }
    var aiTestMode by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "Continuous monitoring", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!consent) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Consent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Continuous monitoring listens to your speech to detect sudden changes. " +
                                "Audio is processed on-device and by Deepgram; only derived features are " +
                                "stored, never raw audio. Monitoring runs only while the app is open.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { scope.launch { vm.settingsStore.setConsentGranted(true) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("I understand and consent")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoRow("Status", if (state.running) "Monitoring" else "Stopped")
                    InfoRow("Speech", if (state.speechActive) "Detected" else "Silence")
                    InfoRow("Deepgram", state.deepgramStatus)
                    state.aiScore?.let {
                        InfoRow("AI slur score (WavLM)", "${(it * 100).toInt()}%")
                    }
                    if (state.aiMode != null) {
                        InfoRow(
                            "AI mode",
                            if (state.aiMode == "personal") "Personal (calibrated)" else "Corpus",
                        )
                    }
                    InfoRow("Baseline", if (hasBaseline) "Ready" else "Not calibrated")
                    state.lastError?.let { InfoRow("Last error", it) }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Voice baseline", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Status", if (hasBaseline) "Ready" else "Not calibrated")
                    Button(
                        enabled = micPermission.granted && !calibrating,
                        onClick = {
                            scope.launch {
                                calibrating = true
                                val profile = vm.speechMonitor.recordBaseline(BASELINE_MS)
                                hasBaseline = profile != null
                                calibMessage = if (profile == null) {
                                    "Not enough speech detected — recalibrate and speak continuously."
                                } else {
                                    "Baseline saved from ${profile.sampleCount} speech windows."
                                }
                                calibrating = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                calibrating -> "Recording\u2026 speak naturally"
                                hasBaseline -> "Recalibrate voice baseline (25s)"
                                else -> "Calibrate voice baseline (25s)"
                            },
                        )
                    }
                    if (calibMessage != null) {
                        Text(
                            calibMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Speaker gating", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enroll your voice so only you are transcribed — other speakers' audio " +
                            "is filtered out on the gateway before it reaches Deepgram.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = micPermission.granted && !enrolling,
                        onClick = {
                            scope.launch {
                                enrolling = true
                                enrollMessage = null
                                val pcm = ByteArrayOutputStream()
                                val capture = AudioCapture { pcm.write(it) }
                                if (!capture.start()) {
                                    enrollMessage = "Microphone unavailable"
                                } else {
                                    delay(6000)
                                    capture.stop()
                                    enrollMessage = Speaker.enroll(vm.settingsStore, pcm.toByteArray())
                                }
                                enrolling = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (enrolling) "Enrolling\u2026 speak now" else "Enroll my voice (6s)")
                    }
                    enrollMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("AI slur test (your voice)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Records 4 seconds and sends it to the gx10 WavLM classifier. If you have " +
                            "calibrated your voice baseline, it scores against your own voice " +
                            "(personal mode); otherwise it uses the population model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = micPermission.granted && !aiTestBusy,
                        onClick = {
                            scope.launch {
                                aiTestBusy = true
                                aiTestScore = null
                                aiTestMessage = null
                                val pcm = ByteArrayOutputStream()
                                val capture = AudioCapture { pcm.write(it) }
                                if (!capture.start()) {
                                    aiTestMessage = "Microphone unavailable"
                                } else {
                                    delay(4000)
                                    capture.stop()
                                    val wav = Wav.wrapPcm16(pcm.toByteArray())
                                    aiTestScore = SlurServer.analyze(vm.settingsStore, wav)
                                    aiTestMode = SlurServer.mode(vm.settingsStore)
                                    if (aiTestScore == null) aiTestMessage = "Could not reach the AI server."
                                }
                                aiTestBusy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (aiTestBusy) "Recording\u2026 speak now" else "Record 4s + AI score")
                    }
                    aiTestScore?.let { s ->
                        InfoRow("AI score", "${(s * 100).toInt()}%")
                        InfoRow(
                            "Verdict",
                            if (s >= 0.9447f) "Slurred" else "Clear",
                            valueColor = if (s >= 0.9447f) severityColor(1f) else Color(0xFF2E7D32),
                        )
                        val modeText = aiTestMode ?: "corpus"
                        InfoRow("Model", if (modeText == "personal") "Personal (your voice)" else "Population")
                    }
                    aiTestMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!micPermission.granted) {
                Button(onClick = micPermission.request, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant microphone access")
                }
            }
            if (notificationPermission != null && !notificationPermission.granted) {
                OutlinedButton(
                    onClick = notificationPermission.request,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow notifications for alerts")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Continuous monitoring")
                    Text(
                        "Runs only while the app is open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.running,
                    enabled = consent && hasBaseline && micPermission.granted,
                    onCheckedChange = { on ->
                        scope.launch {
                            vm.settingsStore.setMonitoringEnabled(on)
                            if (on) vm.speechMonitor.startMonitoring() else vm.speechMonitor.stopMonitoring()
                        }
                    },
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Live analysis", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Slur score", "${(state.score * 100).toInt()}%")
                    ScoreBar(state.score)
                    if (state.transcript.isNotBlank()) {
                        Text(
                            "Heard: ${state.transcript}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoRow("Pitch", "${state.features.f0Mean.toInt()} Hz")
                    InfoRow("Jitter", "%.3f".format(state.features.jitter))
                    InfoRow("Shimmer", "%.3f".format(state.features.shimmer))
                    InfoRow("Harmonics/noise", "${state.features.hnr.toInt()} dB")
                    InfoRow("Speech rate", "${state.features.wpm.toInt()} wpm")
                    InfoRow("Confidence", "${(state.features.confidence * 100).toInt()}%")
                    InfoRow("Pause ratio", "${(state.features.pauseRatio * 100).toInt()}%")
                    InfoRow("Rhythm (4 Hz)", "%.3f".format(state.features.ems4hz))
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Alert sensitivity", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = sensitivity,
                        onValueChange = {
                            scope.launch { vm.settingsStore.setAlertSensitivity(it) }
                            vm.speechMonitor.updateSensitivity(it)
                        },
                        valueRange = 0.1f..0.9f,
                    )
                    Text(
                        "Higher = more sensitive (more alerts). Current: ${(sensitivity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InfoRow(
                        "Emergency SMS",
                        when {
                            !smsEnabled -> "Off"
                            contact.isBlank() -> "Add a contact in Settings"
                            else -> contact
                        },
                    )
                }
            }

            if (state.level == AlertLevel.ALERT || countdown != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = severityColor(1f).copy(alpha = 0.18f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Possible stroke signs", style = MaterialTheme.typography.titleMedium)
                        state.reasons.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                        if (countdown != null) {
                            Text("Texting your emergency contact in ${countdown}s\u2026")
                            Button(
                                onClick = { vm.alertManager.cancelSms() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Cancel text")
                            }
                        }
                        Button(
                            onClick = { nav.navigate(Routes.FACE_CALIB) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Run FAST assessment now")
                        }
                    }
                }
            }
        }
    }
}

private const val BASELINE_MS = 25_000L
