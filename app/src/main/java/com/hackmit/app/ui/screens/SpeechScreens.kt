package com.hackmit.app.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.audio.SpeechMetrics
import com.hackmit.app.domain.Metric
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.LiveChart
import com.hackmit.app.ui.components.MockBadge
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.rememberPermissionState
import kotlinx.coroutines.delay
import kotlin.math.sin

private const val STANDARD_PHRASE = "The quick brown fox jumps over the lazy dog"

private fun mockSpeechMetrics(abnormal: Boolean): SpeechMetrics = if (abnormal) {
    SpeechMetrics(
        wordCount = 9,
        meanConfidence = 0.61f,
        wordsPerMinute = 74f,
        longPauseCount = 4,
        fillerRatio = 0.19f,
        slurScore = 0.72f,
    )
} else {
    SpeechMetrics(
        wordCount = 9,
        meanConfidence = 0.95f,
        wordsPerMinute = 146f,
        longPauseCount = 0,
        fillerRatio = 0.02f,
        slurScore = 0.09f,
    )
}

@Composable
fun SpeechCalibrationScreen(vm: AssessmentViewModel, nav: NavController) {
    val permission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(recording) {
        if (recording) {
            seconds = 0
            while (true) {
                delay(1000)
                seconds++
                if (seconds >= 6) recording = false
            }
        }
    }

    val revealed = STANDARD_PHRASE.split(" ").take(seconds + 1).joinToString(" ")

    ScreenScaffold(title = "Speech calibration", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Record your baseline voice")
                MockBadge()
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Read this aloud at a normal pace:", style = MaterialTheme.typography.labelLarge)
                    Text("\u201C$STANDARD_PHRASE\u201D", style = MaterialTheme.typography.titleMedium)
                }
            }

            LiveChart(
                values = List(60) { i -> (sin((i + seconds * 6) / 3.0).toFloat() * 0.5f + 0.5f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
            )

            if (seconds > 0) {
                Text(
                    "Heard: $revealed\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!permission.granted) {
                Button(onClick = permission.request, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant microphone access")
                }
            }

            Button(
                enabled = permission.granted,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (recording) {
                        recording = false
                        vm.speechBaselineWpm = 145f
                        nav.navigate(Routes.SPEECH_TEST)
                    } else {
                        recording = true
                    }
                },
            ) {
                Text(
                    when {
                        recording -> "Stop and continue"
                        else -> "Start baseline recording"
                    },
                )
            }

            Text(
                "TODO: stream PCM to DeepgramClient and derive the baseline WPM from real transcripts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SpeechTestScreen(vm: AssessmentViewModel, nav: NavController) {
    val permission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var abnormal by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }

    val metrics = mockSpeechMetrics(abnormal)

    ScreenScaffold(title = "Speech test", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Say: \u201CBaby hippopotamus\u201D")
                MockBadge()
            }

            LiveChart(
                values = List(80) { i -> (sin((i + tick) / 4.0).toFloat() * 0.5f + 0.5f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoRow("Slur score", "${(metrics.slurScore * 100).toInt()}%")
                    ScoreBar(metrics.slurScore)
                    InfoRow("Word confidence", "${(metrics.meanConfidence * 100).toInt()}%")
                    InfoRow("Speech rate", "${metrics.wordsPerMinute.toInt()} wpm")
                    InfoRow("Long pauses", metrics.longPauseCount.toString())
                    InfoRow("Filler ratio", "${(metrics.fillerRatio * 100).toInt()}%")
                    InfoRow("Baseline rate", "${vm.speechBaselineWpm?.toInt() ?: 0} wpm")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Simulate slurred speech")
                Switch(checked = abnormal, onCheckedChange = { abnormal = it })
            }

            Button(
                enabled = permission.granted,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.submit(
                        ModuleResult(
                            type = ModuleType.SPEECH,
                            score = metrics.slurScore,
                            metrics = listOf(
                                Metric("Slur score", "${(metrics.slurScore * 100).toInt()}%", metrics.slurScore),
                                Metric("Confidence", "${(metrics.meanConfidence * 100).toInt()}%", 1f - metrics.meanConfidence),
                                Metric("Speech rate", "${metrics.wordsPerMinute.toInt()} wpm", 0f),
                                Metric("Long pauses", metrics.longPauseCount.toString(), 0f),
                            ),
                            summary = if (metrics.slurScore >= 0.33f) {
                                "Slurred speech pattern detected"
                            } else {
                                "Speech within normal range"
                            },
                        ),
                    )
                    nav.navigate(Routes.MOTOR_CALIB)
                },
            ) {
                Text("Complete speech test")
            }
        }
    }
}
