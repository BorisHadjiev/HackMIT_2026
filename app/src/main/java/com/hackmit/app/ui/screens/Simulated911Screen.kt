package com.hackmit.app.ui.screens

import android.Manifest
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.audio.AgentEvent
import com.hackmit.app.audio.AgentStream
import com.hackmit.app.audio.AudioCapture
import com.hackmit.app.audio.PcmPlayer
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private data class Line(val role: String, val text: String)

/** How long after the agent's last audio frame we keep the mic muted (silence sent). */
private const val AGENT_TAIL_MS = 700L

/**
 * Simulated 911 call. The user is the caller; a Deepgram voice agent (BYO local LLM)
 * plays a dispatcher. NOTHING IS DIALED and no services are contacted — the agent is
 * instructed to say so. This is a training/demo experience.
 */
@Composable
fun Simulated911Screen(vm: AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()
    val mic = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }

    val agent = remember { AgentStream(vm.settingsStore, scope) }
    var player by remember { mutableStateOf<PcmPlayer?>(null) }
    var capture by remember { mutableStateOf<AudioCapture?>(null) }

    // Timestamp of the last agent audio frame. While the agent is speaking we send
    // silence (not the mic) so it never hears itself; the stream stays alive.
    val lastAgentAudioMs = remember { AtomicLong(0L) }
    // Mic stays closed until the greeting has finished (first AgentAudioDone).
    val greetingDone = remember { AtomicBoolean(false) }

    var active by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Idle") }
    var seconds by remember { mutableIntStateOf(0) }
    var onset by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Line>() }

    fun endCall() {
        active = false
        capture?.stop(); capture = null
        agent.stop()
        player?.stop(); player = null
        runCatching {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = false
        }
    }

    fun startCall() {
        lines.clear()
        seconds = 0
        val assessment = vm.assessment
        val context = buildMap {
            assessment?.let {
                put("p_stroke", it.overallScore.toString())
                put("severity", it.overallScore.toString())
                put("action", it.band.name)
            }
            assessment?.results?.values
                ?.filter { it.score >= 0.5f }
                ?.map { it.type.displayName }
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("signs", it.joinToString(",")) }
            if (onset.isNotBlank()) put("onset_minutes", onset)
            if (location.isNotBlank()) put("location", location)
        }
        val p = PcmPlayer()
        p.start()
        player = p
        // Route through the voice path so the platform echo canceller can work.
        runCatching {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = true
        }
        lastAgentAudioMs.set(SystemClock.uptimeMillis())
        greetingDone.set(false)
        active = true
        agent.start(
            context = context,
            onEvent = { e: AgentEvent ->
                when (e.type) {
                    "ConversationText" -> e.text?.let { lines.add(Line(e.role ?: "assistant", it)) }
                    "UserStartedSpeaking" -> player?.flush() // barge-in
                    "AgentAudioDone" -> {
                        lastAgentAudioMs.set(SystemClock.uptimeMillis())
                        greetingDone.set(true)
                    }
                    "Error" -> status = "Agent error"
                }
            },
            onAudio = {
                lastAgentAudioMs.set(SystemClock.uptimeMillis()) // agent is speaking
                player?.write(it)
            },
            onStatus = { status = it },
        )
        val c = AudioCapture(
            onChunk = { bytes ->
                if (active) {
                    // Send the mic only once the greeting is done and the agent is quiet;
                    // otherwise send silence so the agent can't hear itself.
                    val quiet = SystemClock.uptimeMillis() - lastAgentAudioMs.get() > AGENT_TAIL_MS
                    val allowMic = greetingDone.get() && quiet
                    agent.send(if (allowMic) bytes else ByteArray(bytes.size))
                }
            },
            source = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            echoCancel = true,
        )
        if (!c.start()) status = "Microphone unavailable" else capture = c
    }

    LaunchedEffect(active) {
        var t = 0
        while (active) {
            delay(1000)
            t += 1
            seconds += 1
            if (t >= 12) greetingDone.set(true) // safety if AgentAudioDone never arrives
        }
    }

    DisposableEffect(Unit) { onDispose { endCall() } }

    ScreenScaffold(title = "Simulated 911", onBack = { endCall(); nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFB3261E).copy(alpha = 0.12f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SIMULATION ONLY", fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
                    Text(
                        "This does not call 911 and no help is dispatched. If this were a real " +
                            "emergency, use your phone's dialer to call emergency services.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Phone, contentDescription = null, tint = Color(0xFF2E7D32))
                    Text("Simulated 911", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (active) "Caller connected · %02d:%02d".format(seconds / 60, seconds % 60)
                        else "Ready",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!active) {
                Text(
                    "StrokeSense places the call and reports the patient's screening result, " +
                        "symptoms and location to the dispatcher. You can answer its follow-ups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it.take(120) },
                    label = { Text("Patient location (address or cross streets)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = onset,
                    onValueChange = { onset = it.filter(Char::isDigit).take(3) },
                    label = { Text("Symptoms started (minutes ago, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!mic.granted) {
                    Button(onClick = mic.request, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant microphone access")
                    }
                }
                Button(
                    onClick = { startCall() },
                    enabled = mic.granted,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start simulated call") }
            } else {
                Button(
                    onClick = { endCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null)
                    Text("  End call")
                }
            }

            if (lines.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Transcript", style = MaterialTheme.typography.titleMedium)
                        lines.forEach { line ->
                            val isCaller = line.role == "user"
                            Text(
                                (if (isCaller) "You: " else "Dispatcher: ") + line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCaller) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isCaller) Color.Transparent else Color(0x11000000),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }

            Text(
                "Powered by Deepgram (speech + voice) and a local LLM on the edge. " +
                    "No audio leaves the gateway's transcription pipeline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}