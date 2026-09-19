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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.alert.AlertConfig
import com.hackmit.app.audio.DeepgramClient
import com.hackmit.app.audio.DeepgramRouter
import com.hackmit.app.audio.SpeechMetrics
import com.hackmit.app.audio.SpeechSession
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
private const val TEST_PHRASE = "Baby hippopotamus"

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
    val apiKey by vm.settingsStore.deepgramKey.collectAsState(initial = "")
    val proxyUrl by vm.settingsStore.deepgramProxyUrl.collectAsState(initial = "")
    val alertConfig by vm.settingsStore.alertConfig.collectAsState(initial = AlertConfig())
    val gatewayToken = alertConfig.gatewayToken
    val useProxy = DeepgramRouter.isProxy(proxyUrl, gatewayToken)
    val sessionKey = if (useProxy) gatewayToken else apiKey
    val sessionEndpoint =
        if (useProxy) "$proxyUrl?${DeepgramClient.DEFAULT_QUERY}" else DeepgramClient.DEFAULT_ENDPOINT

    var recording by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var transcript by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<SpeechSession?>(null) }

    DisposableEffect(Unit) {
        onDispose { session?.stop() }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        elapsed = 0
        while (true) {
            delay(1000)
            elapsed++
            if (!live) {
                transcript = STANDARD_PHRASE.split(" ").take(elapsed + 1).joinToString(" ")
            }
        }
    }

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
                values = List(60) { i -> (sin((i + elapsed * 6) / 3.0).toFloat() * 0.5f + 0.5f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
            )

            Text(
                when {
                    recording -> "Recording\u2026 ${elapsed}s  (tap Stop when you finish the sentence)"
                    else -> "Tap Start, read the sentence, then tap Stop."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (transcript.isNotBlank()) {
                Text(
                    "Heard: $transcript",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
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
                        val metrics = session?.stop()
                        recording = false
                        vm.speechBaselineWpm =
                            if (metrics != null && metrics.wordCount > 0) metrics.wordsPerMinute else 145f
                        nav.navigate(Routes.SPEECH_TEST)
                    } else {
                        val newSession = SpeechSession(
                            apiKey = sessionKey,
                            endpoint = sessionEndpoint,
                            onTranscript = { transcript = it },
                            onStatus = { status = it },
                        )
                        live = newSession.isLive
                        session = newSession
                        newSession.start()
                        recording = true
                    }
                },
            ) {
                Text(if (recording) "Stop and continue" else "Start baseline recording")
            }

            Text(
                when {
                    !useProxy && apiKey.isBlank() ->
                        "Demo mode: add a Deepgram key in Settings for real transcription."
                    useProxy -> "Streaming microphone audio through the StrokeSense gateway."
                    else -> "Streaming microphone audio to Deepgram."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SpeechTestScreen(vm: AssessmentViewModel, nav: NavController) {
    val permission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val apiKey by vm.settingsStore.deepgramKey.collectAsState(initial = "")
    val proxyUrl by vm.settingsStore.deepgramProxyUrl.collectAsState(initial = "")
    val alertConfig by vm.settingsStore.alertConfig.collectAsState(initial = AlertConfig())
    val gatewayToken = alertConfig.gatewayToken
    val useProxy = DeepgramRouter.isProxy(proxyUrl, gatewayToken)
    val sessionKey = if (useProxy) gatewayToken else apiKey
    val sessionEndpoint =
        if (useProxy) "$proxyUrl?${DeepgramClient.DEFAULT_QUERY}" else DeepgramClient.DEFAULT_ENDPOINT

    var abnormal by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var transcript by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<SpeechSession?>(null) }
    var metrics by remember { mutableStateOf<SpeechMetrics?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { session?.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        elapsed = 0
        while (true) {
            delay(500)
            elapsed++
            if (!live) {
                transcript = TEST_PHRASE.split(" ").take((elapsed / 2) + 1).joinToString(" ")
            }
            metrics = session?.peek()
        }
    }

    val displayMetrics = when {
        abnormal -> mockSpeechMetrics(true)
        metrics != null && metrics!!.wordCount > 0 -> metrics!!
        else -> mockSpeechMetrics(false)
    }

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
                Text("Say: \u201C$TEST_PHRASE\u201D")
                MockBadge()
            }

            LiveChart(
                values = List(80) { i -> (sin((i + tick) / 4.0).toFloat() * 0.5f + 0.5f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
            )

            Text(
                if (recording) {
                    "Recording\u2026 ${elapsed / 2}s  (tap Stop when done)"
                } else {
                    "Tap Start, say the phrase, then tap Stop."
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (transcript.isNotBlank()) {
                Text(
                    "Heard: $transcript",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoRow("Slur score", "${(displayMetrics.slurScore * 100).toInt()}%")
                    ScoreBar(displayMetrics.slurScore)
                    InfoRow("Word confidence", "${(displayMetrics.meanConfidence * 100).toInt()}%")
                    InfoRow("Speech rate", "${displayMetrics.wordsPerMinute.toInt()} wpm")
                    InfoRow("Long pauses", displayMetrics.longPauseCount.toString())
                    InfoRow("Filler ratio", "${(displayMetrics.fillerRatio * 100).toInt()}%")
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
                    if (recording) {
                        val measured = session?.stop()
                        recording = false
                        val finalMetrics = when {
                            abnormal -> mockSpeechMetrics(true)
                            measured != null && measured.wordCount > 0 -> measured
                            else -> mockSpeechMetrics(false)
                        }
                        vm.submit(
                            ModuleResult(
                                type = ModuleType.SPEECH,
                                score = finalMetrics.slurScore,
                                metrics = listOf(
                                    Metric("Slur score", "${(finalMetrics.slurScore * 100).toInt()}%", finalMetrics.slurScore),
                                    Metric("Confidence", "${(finalMetrics.meanConfidence * 100).toInt()}%", 1f - finalMetrics.meanConfidence),
                                    Metric("Speech rate", "${finalMetrics.wordsPerMinute.toInt()} wpm", 0f),
                                    Metric("Long pauses", finalMetrics.longPauseCount.toString(), 0f),
                                ),
                                summary = if (finalMetrics.slurScore >= 0.33f) {
                                    "Slurred speech pattern detected"
                                } else {
                                    "Speech within normal range"
                                },
                            ),
                        )
                        nav.navigate(Routes.MOTOR_CALIB)
                    } else {
                        val newSession = SpeechSession(
                            apiKey = sessionKey,
                            endpoint = sessionEndpoint,
                            onTranscript = { transcript = it },
                            onStatus = { status = it },
                        )
                        live = newSession.isLive
                        session = newSession
                        newSession.start()
                        recording = true
                    }
                },
            ) {
                Text(if (recording) "Stop and finish test" else "Start speech test")
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
