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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                MockBadge()
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
                subtitle = "Arduino IMU drift + tremor",
                score = vm.results[ModuleType.MOTOR]?.score,
                icon = Icons.Filled.Sensors,
                onClick = { nav.navigate(Routes.MOTOR_CALIB) },
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
