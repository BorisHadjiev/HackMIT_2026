package com.hackmit.app.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.audio.DemoSpeech
import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.domain.BaselineProfile
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.severityColor
import kotlinx.coroutines.launch

@Composable
fun SlurDemoScreen(vm: com.hackmit.app.ui.AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var baseline by remember { mutableStateOf<BaselineProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Load the healthy sample first to set a personal baseline.") }
    var slurScore by remember { mutableStateOf(0f) }
    var slurLevel by remember { mutableStateOf(AlertLevel.NORMAL) }
    var slurReasons by remember { mutableStateOf<List<String>>(emptyList()) }
    var slurWindows by remember { mutableStateOf<List<Float>>(emptyList()) }

    fun play(asset: String) {
        runCatching {
            MediaPlayer.create(context, Uri.parse("file:///android_asset/audio/$asset"))?.start()
        }
    }

    fun runDemo(asset: String, label: String) {
        scope.launch {
            busy = true
            message = "Analyzing $label\u2026"
            val base = baseline
            if (base == null || base.sampleCount < 5) {
                message = "Not enough baseline windows (${base?.sampleCount ?: 0}). Recalibrate on the healthy sample."
                busy = false
                return@launch
            }
            val samples = DemoSpeech.decodeWav(context, asset)
            val results = DemoSpeech.score(DemoSpeech.windows(samples), base)
            if (results.isEmpty()) {
                message = "$label: no 2 s windows extracted."
                busy = false
                return@launch
            }
            val last = results.last()
            slurScore = last.score
            slurLevel = last.level
            slurReasons = last.reasons
            slurWindows = results.map { it.score }
            message = "$label: analyzed ${results.size} windows."
            play(asset)
            busy = false
        }
    }

    ScreenScaffold(title = "Slur demo (sample audio)", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("How it works", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Bundled 16 kHz recordings are run through the same on-device DSP and " +
                            "slur detector as the live monitor — no microphone needed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InfoRow("Baseline", if (baseline != null) "Ready (${baseline!!.sampleCount} windows)" else "Not calibrated")
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val samples = DemoSpeech.decodeWav(context, "audio/healthy.wav")
                                baseline = DemoSpeech.buildBaseline(samples)
                                message = "Baseline saved from the healthy sample (${baseline!!.sampleCount} windows)."
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("1. Calibrate on healthy sample") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { runDemo("audio/slurred.wav", "slurred sample") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("2. Analyze slurred sample") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { runDemo("audio/dysarthric.wav", "dysarthric sample") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Analyze real dysarthric sample") }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (baseline != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Result", style = MaterialTheme.typography.titleMedium)
                        InfoRow("Slur score", "${(slurScore * 100).toInt()}%")
                        ScoreBar(slurScore)
                        InfoRow(
                            "Level",
                            when (slurLevel) {
                                AlertLevel.ALERT -> "ALERT"
                                AlertLevel.WARNING -> "WARNING"
                                AlertLevel.NORMAL -> "NORMAL"
                            },
                        )
                        slurReasons.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                        if (slurWindows.size > 1) {
                            Text(
                                "Window scores: ${slurWindows.joinToString { "%.0f%%".format(it * 100) }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (slurLevel == AlertLevel.ALERT) {
                            Text(
                                "This is what an acute slur pattern looks like vs. your baseline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = severityColor(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}