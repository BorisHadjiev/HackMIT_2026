package com.hackmit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.components.DisclaimerCard
import com.hackmit.app.ui.components.ModuleCard
import com.hackmit.app.ui.components.MockBadge
import com.hackmit.app.ui.components.ScreenScaffold

@Composable
fun HomeScreen(vm: AssessmentViewModel, nav: NavController) {
    val monitorState by vm.speechMonitor.state.collectAsState()
    val debugMock by vm.settingsStore.debugMockSummary.collectAsState(initial = true)
    ScreenScaffold(
        title = "Stroke screening",
        actions = {
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("FAST check", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Face, speech and motor screening in one flow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vm.sensorRepository.isMock) MockBadge()
            }

            Spacer(Modifier.padding(top = 4.dp))

            ModuleCard(
                title = "Facial symmetry",
                subtitle = "Camera check for drooping",
                score = vm.results[ModuleType.FACE]?.score,
                icon = Icons.Filled.Face,
                onClick = { nav.navigate(Routes.FACE_CALIB) },
            )
            ModuleCard(
                title = "Speech",
                subtitle = "Microphone + Deepgram slur analysis",
                score = vm.results[ModuleType.SPEECH]?.score,
                icon = Icons.Filled.Mic,
                onClick = { nav.navigate(Routes.SPEECH_CALIB) },
            )
            ModuleCard(
                title = "Motor / balance",
                subtitle = "Arduino IMU left / right / difference",
                score = vm.results[ModuleType.MOTOR]?.score,
                icon = Icons.Filled.Sensors,
                onClick = { nav.navigate(Routes.MOTOR_CALIB) },
            )
            ModuleCard(
                title = "Continuous monitoring",
                subtitle = if (monitorState.running) {
                    "Listening (${if (monitorState.speechActive) "speech" else "silence"})"
                } else {
                    "Slur detection while the app is open"
                },
                score = if (monitorState.running) monitorState.score else null,
                icon = Icons.Filled.GraphicEq,
                onClick = { nav.navigate(Routes.MONITOR) },
            )
            ModuleCard(
                title = "Slur demo (sample audio)",
                subtitle = "Detect slur on bundled real recordings",
                score = null,
                icon = Icons.Filled.PlayArrow,
                onClick = { nav.navigate(Routes.SLUR_DEMO) },
            )
            ModuleCard(
                title = "Simulated 911 call",
                subtitle = "Practice reporting with a voice dispatcher (demo, no call placed)",
                score = null,
                icon = Icons.Filled.Phone,
                onClick = { nav.navigate(Routes.SIMULATED_911) },
            )

            Spacer(Modifier.padding(top = 4.dp))

            Button(
                onClick = {
                    vm.reset()
                    nav.navigate(Routes.FACE_CALIB)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start full assessment")
            }

            if (debugMock) {
                OutlinedButton(
                    onClick = {
                        vm.submitMock()
                        nav.navigate(Routes.RESULTS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip to mock results (debug)")
                }
            }

            if (vm.results.isNotEmpty()) {
                OutlinedButton(
                    onClick = { nav.navigate(Routes.RESULTS) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View results")
                }
            }

            DisclaimerCard()
        }
    }
}
