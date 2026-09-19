package com.hackmit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.domain.ModuleType
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
        }
    }
}
