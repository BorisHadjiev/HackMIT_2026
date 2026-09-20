package com.hackmit.app.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.audio.DemoSpeech
import com.hackmit.app.audio.DemoWindow
import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.domain.BaselineProfile
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.severityColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SlurDemoScreen(vm: com.hackmit.app.ui.AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var baseline by remember { mutableStateOf<BaselineProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Load the healthy sample first to set a personal baseline.") }

    var results by remember { mutableStateOf<List<DemoWindow>>(emptyList()) }
    var lastAsset by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var nowIndex by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var tickerJob by remember { mutableStateOf<Job?>(null) }

    fun releasePlayer() {
        tickerJob?.cancel()
        tickerJob = null
        playing = false
        player?.release()
        player = null
    }

    fun startPlayback(asset: String) {
        releasePlayer()
        val bytes = runCatching { context.assets.open(asset).readBytes() }.getOrNull()
        if (bytes == null) {
            Log.e("SlurDemo", "could not read asset $asset")
            message = "Could not read audio."
            return
        }
        val file = File(context.cacheDir, "demo_${System.currentTimeMillis()}.wav").apply { writeBytes(bytes) }
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setVolume(1f, 1f)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }.getOrNull()
        if (p == null) {
            Log.e("SlurDemo", "could not create MediaPlayer")
            message = "Could not create player."
            return
        }
        player = p
        p.setOnPreparedListener {
            Log.d("SlurDemo", "prepared, starting ($asset)")
            p.start()
        }
        p.setOnCompletionListener {
            Log.d("SlurDemo", "completed")
            playing = false
            nowIndex = (results.size - 1).coerceAtLeast(0)
        }
        p.setOnErrorListener { _, what, extra ->
            Log.e("SlurDemo", "playback error what=$what extra=$extra")
            playing = false
            message = "Playback error ($what/$extra)"
            true
        }
        runCatching { p.prepareAsync() }.onFailure {
            Log.e("SlurDemo", "prepareAsync threw", it)
            message = "Could not prepare audio."
        }
        playing = true
        tickerJob = scope.launch {
            while (true) {
                val pos = runCatching { p.currentPosition }.getOrDefault(0)
                val idx = (pos / 1000L).toInt().coerceIn(0, (results.size - 1).coerceAtLeast(0))
                nowIndex = idx
                delay(200)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
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
            val windows = DemoSpeech.windows(samples)
            val scored = DemoSpeech.score(windows, base)
            if (scored.isEmpty()) {
                message = "$label: no 2 s windows extracted."
                busy = false
                return@launch
            }
            results = scored
            lastAsset = asset
            message = "$label: playing\u2026"
            startPlayback(asset)
            busy = false
        }
    }

    val peak = results.maxByOrNull { it.raw }
    val peakRaw = peak?.raw ?: 0f
    val peakLevel = when {
        peakRaw >= 0.625f -> AlertLevel.ALERT
        peakRaw >= 0.375f -> AlertLevel.WARNING
        else -> AlertLevel.NORMAL
    }
    val now = results.getOrNull(nowIndex)

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
                            "slur detector as the live monitor — no microphone needed. You'll hear " +
                            "the sample while the score updates in real time.",
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
                    ) { Text("2. Analyze slurred sample (hear it)") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { runDemo("audio/dysarthric.wav", "dysarthric sample") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Analyze real dysarthric sample (hear it)") }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (baseline != null && results.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Live analysis", style = MaterialTheme.typography.titleMedium)
                        if (now != null) {
                            InfoRow("Now", "${(now.raw * 100).toInt()}% (window ${nowIndex + 1}/${results.size})")
                            ScoreBar(now.raw)
                            InfoRow(
                                "Level",
                                when {
                                    now.raw >= 0.625f -> "ALERT"
                                    now.raw >= 0.375f -> "WARNING"
                                    else -> "NORMAL"
                                },
                            )
                            now.reasons.take(3).forEach {
                                Text("\u2022 $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                results.forEachIndexed { i, w ->
                                    Text(
                                        "${(w.raw * 100).toInt()}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (i == nowIndex) FontWeight.Bold else FontWeight.Normal,
                                        color = if (i == nowIndex) severityColor(w.raw) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            Text("Analyze a sample above to hear it while scoring.", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = lastAsset != null && !busy,
                                onClick = { lastAsset?.let { startPlayback(it) } },
                            ) { Text("Replay") }
                            if (playing) {
                                OutlinedButton(onClick = { releasePlayer() }) { Text("Stop") }
                            }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Summary", style = MaterialTheme.typography.titleMedium)
                        InfoRow("Peak slur deviation", "${(peakRaw * 100).toInt()}%")
                        ScoreBar(peakRaw)
                        InfoRow("Sustained score", "${((results.lastOrNull()?.score ?: 0f) * 100).toInt()}%")
                        InfoRow(
                            "Level",
                            when (peakLevel) {
                                AlertLevel.ALERT -> "ALERT"
                                AlertLevel.WARNING -> "WARNING"
                                AlertLevel.NORMAL -> "NORMAL"
                            },
                        )
                        (peak?.reasons ?: emptyList()).forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                        if (peakLevel == AlertLevel.ALERT) {
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