package com.hackmit.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.domain.RiskBand
import com.hackmit.app.alert.AlertConfig
import com.hackmit.app.alert.AlertDeliveryStatus
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.components.DisclaimerCard
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.severityColor

@Composable
fun ResultsScreen(vm: AssessmentViewModel, nav: NavController) {
    val assessment = vm.assessment
    val context = LocalContext.current
    val alertConfig by vm.settingsStore.alertConfig.collectAsState(initial = AlertConfig())
    var showEmergencyDialog by remember { mutableStateOf(false) }

    ScreenScaffold(title = "Results", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (assessment == null) {
                Text("No results yet. Run the assessment first.")
                Button(onClick = { nav.navigate(Routes.HOME) }) { Text("Back to home") }
                return@Column
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${(assessment.overallScore * 100).toInt()}%",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = severityColor(assessment.overallScore),
                    )
                    Text(
                        assessment.band.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = severityColor(assessment.overallScore),
                    )
                    ScoreBar(assessment.overallScore)
                    Text(
                        assessment.band.advice,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Share result", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Send a concise screening summary to the trusted contact configured in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = vm::sendAssessmentAlert,
                        enabled = vm.alertDelivery.status != AlertDeliveryStatus.SENDING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (assessment.band == RiskBand.HIGH) {
                                "Send urgent care alert"
                            } else {
                                "Send screening summary"
                            },
                        )
                    }
                    if (vm.alertDelivery.status != AlertDeliveryStatus.IDLE) {
                        Text(
                            vm.alertDelivery.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (vm.alertDelivery.status == AlertDeliveryStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (assessment.band == RiskBand.HIGH) {
                        OutlinedButton(
                            onClick = { showEmergencyDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Call emergency services")
                        }
                    }
                }
            }

            ModuleType.entries.forEach { type ->
                val result = assessment.results[type] ?: return@forEach
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(type.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${(result.score * 100).toInt()}%",
                                fontWeight = FontWeight.Bold,
                                color = severityColor(result.score),
                            )
                        }
                        ScoreBar(result.score)
                        Text(result.summary, style = MaterialTheme.typography.bodyMedium)
                        result.metrics.forEach { InfoRow(it.label, it.value) }
                    }
                }
            }

            DisclaimerCard()

            OutlinedButton(
                onClick = {
                    vm.reset()
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("New assessment")
            }

            if (showEmergencyDialog) {
                AlertDialog(
                    onDismissRequest = { showEmergencyDialog = false },
                    title = { Text("Call emergency services?") },
                    text = {
                        Text(
                            "This will open the phone dialer for ${alertConfig.emergencyNumber}. " +
                                "StrokeSense cannot diagnose a stroke.",
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val number = alertConfig.emergencyNumber
                                    .filter { it.isDigit() || it == '+' }
                                    .ifBlank { "911" }
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                                )
                                showEmergencyDialog = false
                            },
                        ) { Text("Open dialer") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showEmergencyDialog = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}
