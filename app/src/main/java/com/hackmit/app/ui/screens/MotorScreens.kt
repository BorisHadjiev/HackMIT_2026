package com.hackmit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.domain.Metric
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.sensor.SensorSample
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.sensor.bluetoothPermissions
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.MotorBaseline
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.SensorStage
import com.hackmit.app.ui.components.ArmPoseSection
import com.hackmit.app.ui.components.ConnectionStatusBanner
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.LiveChart
import com.hackmit.app.ui.components.MockBadge
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.SleepHoursEditor
import com.hackmit.app.ui.components.SpeakButton
import com.hackmit.app.ui.components.rememberPermissionsState
import com.hackmit.app.ui.theme.Coral
import com.hackmit.app.ui.theme.Green
import com.hackmit.app.ui.theme.Teal
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun fmtDeg(value: Float): String =
    if (value.isFinite()) String.format("%.1f°", value) else "—"

private fun meanFinite(values: List<Float>): Float {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return Float.NaN
    return finite.average().toFloat()
}

private fun baselineOf(samples: List<SensorSample>): MotorBaseline {
    val left = meanFinite(samples.map { it.leftDeg })
    val right = meanFinite(samples.map { it.rightDeg })
    val diff = meanFinite(samples.map { it.diffDeg }).let { mean ->
        if (mean.isFinite()) mean else if (left.isFinite() && right.isFinite()) abs(left - right) else Float.NaN
    }
    return MotorBaseline(left, right, diff)
}

private fun motorScore(samples: List<SensorSample>): Float {
    if (samples.size < 5) return 0f
    val diffs = samples.map { it.diffDeg }.filter { it.isFinite() }
    if (diffs.isEmpty()) return 0f
    val meanDiff = diffs.average().toFloat()
    val peakDiff = diffs.max()
    val meanScore = ((meanDiff - 5f) / 20f).coerceIn(0f, 1f)
    val peakScore = ((peakDiff - 8f) / 25f).coerceIn(0f, 1f)
    return (meanScore * 0.6f + peakScore * 0.4f).coerceIn(0f, 1f)
}

@Composable
private fun ArmAngleReadout(sample: SensorSample?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AngleCard("Left", sample?.leftDeg ?: Float.NaN, Teal, Modifier.weight(1f))
        AngleCard("Right", sample?.rightDeg ?: Float.NaN, Green, Modifier.weight(1f))
        AngleCard("Diff", sample?.diffDeg ?: Float.NaN, Coral, Modifier.weight(1f))
    }
}

@Composable
private fun AngleCard(label: String, degrees: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                fmtDeg(degrees),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

@Composable
private fun ArmCueControls(vm: AssessmentViewModel, connected: Boolean) {
    val scope = rememberCoroutineScope()
    val storedMinutes by vm.settingsStore.buzzCooldownMinutes.collectAsState(initial = 1f)
    var minutesText by remember(storedMinutes) { mutableStateOf(storedMinutes.toString()) }
    var cueStatus by remember { mutableStateOf("") }
    val canCommand = connected || vm.sensorRepository.isMock

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Arduino cue", style = MaterialTheme.typography.titleMedium)
            Text(
                "After a successful raise, buzzing waits this many minutes, then rearms. " +
                    "Start the arm test from here — the board will not buzz on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = minutesText,
                onValueChange = { minutesText = it },
                label = { Text("Minutes between buzzing sections") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = canCommand,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val minutes = minutesText.toFloatOrNull()
                    if (minutes == null) {
                        cueStatus = "Enter a number of minutes"
                        return@Button
                    }
                    scope.launch {
                        val ok = vm.setBuzzCooldownMinutes(minutes)
                        cueStatus = if (ok) "Cooldown set to ${"%.2f".format(minutes)} min" else "Could not send cooldown"
                    }
                },
            ) { Text("Send cooldown to Arduino") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = canCommand,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        cueStatus = if (vm.startArmTest()) "Arm test started" else "Could not start"
                    },
                ) { Text("Start arm test") }
                OutlinedButton(
                    enabled = canCommand,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        cueStatus = if (vm.stopArmTest()) "Buzzing stopped" else "Could not stop"
                    },
                ) { Text("Stop") }
            }
            if (cueStatus.isNotBlank()) {
                Text(cueStatus, style = MaterialTheme.typography.bodySmall)
            }
            SleepHoursEditor(vm)
        }
    }
}

private fun needsBluetooth(transport: SensorTransport): Boolean =
    transport == SensorTransport.BLE

/**
 * Link controls for the board. Reconnect opens a fresh GATT link; Disconnect
 * sends STOP and closes the current one.
 */
@Composable
private fun SensorLinkControls(
    vm: AssessmentViewModel,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    if (!needsBluetooth(vm.sensorRepository.transport)) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onReconnect,
            modifier = Modifier.weight(1f),
        ) { Text("Reconnect") }
        OutlinedButton(
            enabled = vm.sensorLinkActive,
            onClick = onDisconnect,
            modifier = Modifier.weight(1f),
        ) { Text("Disconnect") }
    }
}

@Composable
fun MotorCalibrationScreen(vm: AssessmentViewModel, nav: NavController) {
    val btPermission = rememberPermissionsState(bluetoothPermissions())
    var collecting by remember { mutableStateOf(false) }
    var recordedCount by remember { mutableStateOf(0) }
    var samples by remember { mutableStateOf<List<SensorSample>>(emptyList()) }
    var live by remember { mutableStateOf<SensorSample?>(null) }
    var baseline by remember { mutableStateOf<MotorBaseline?>(null) }
    var connected by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var linkWanted by remember { mutableStateOf(true) }
    var sessionId by remember { mutableStateOf(-1) }

    DisposableEffect(Unit) {
        onDispose { vm.releaseSensor(sessionId) }
    }

    // Flipping linkWanted off cancels this effect, which cancels the sample collector.
    LaunchedEffect(btPermission.granted, retryKey, linkWanted) {
        if (!linkWanted) return@LaunchedEffect
        live = null
        samples = emptyList()
        connected = vm.prepareMotorSensor(mockAbnormal = false)
        sessionId = vm.sensorSessionId
        if (!connected && vm.sensorStage == SensorStage.PERMISSION_REQUIRED) {
            btPermission.request()
            return@LaunchedEffect
        }
        if (!connected) return@LaunchedEffect
        val recorded = mutableListOf<SensorSample>()
        vm.sensorRepository.samples().collect { sample ->
            live = sample
            samples = (samples + sample).takeLast(80)
            if (collecting) {
                recorded += sample
                recordedCount = recorded.size
                if (recorded.size >= 60) {
                    baseline = baselineOf(recorded)
                    vm.motorBaseline = baseline
                    collecting = false
                    recorded.clear()
                    recordedCount = 0
                }
            }
        }
    }

    ScreenScaffold(
        title = "Motor calibration",
        onBack = { nav.popBackStack() },
        actions = { SpeakButton("Hold both arms out in front of you, palms up, for ten seconds.", vm) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Raise both arms and hold")
            ConnectionStatusBanner(stage = vm.sensorStage, address = vm.sensorAddress)
            if (vm.sensorRepository.isMock && vm.sensorStage == SensorStage.MOCK) MockBadge()

            if (!btPermission.granted && needsBluetooth(vm.sensorRepository.transport)) {
                Button(onClick = btPermission.request, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow Bluetooth")
                }
            }

            val streaming = vm.isSensorStreaming
            ArmAngleReadout(if (streaming) live else null)
            ArmCueControls(vm, connected && streaming)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (vm.sensorStage == SensorStage.LIVE && vm.sensorAddress.isNotBlank()) {
                        InfoRow("Board", "StrokeSense  ${vm.sensorAddress}")
                    }
                    InfoRow("Baseline left", fmtDeg(baseline?.leftDeg ?: Float.NaN))
                    InfoRow("Baseline right", fmtDeg(baseline?.rightDeg ?: Float.NaN))
                    InfoRow("Baseline diff", fmtDeg(baseline?.diffDeg ?: Float.NaN))
                    SensorLinkControls(
                        vm = vm,
                        onReconnect = {
                            linkWanted = true
                            retryKey++
                        },
                        onDisconnect = {
                            vm.disconnectSensor()
                            linkWanted = false
                            connected = false
                            collecting = false
                            recordedCount = 0
                            live = null
                            samples = emptyList()
                        },
                    )
                }
            }

            LiveChart(
                values = samples.map { it.leftDeg }.filter { it.isFinite() },
                secondary = samples.map { it.rightDeg }.filter { it.isFinite() },
                tertiary = samples.map { it.diffDeg }.filter { it.isFinite() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
            Text(
                "Teal left · green right · coral difference",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                enabled = connected && !collecting,
                modifier = Modifier.fillMaxWidth(),
                onClick = { collecting = true },
            ) {
                Text(if (collecting) "Recording baseline… $recordedCount/60" else "Record baseline (3s)")
            }

            Button(
                enabled = baseline != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { nav.navigate(Routes.MOTOR_TEST) },
            ) {
                Text("Continue to motor test")
            }

            ArmPoseSection(if (streaming) live else null)
        }
    }
}

@Composable
fun MotorTestScreen(vm: AssessmentViewModel, nav: NavController) {
    val btPermission = rememberPermissionsState(bluetoothPermissions())
    var abnormal by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<SensorSample>>(emptyList()) }
    var live by remember { mutableStateOf<SensorSample?>(null) }
    var connected by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var linkWanted by remember { mutableStateOf(true) }
    var sessionId by remember { mutableStateOf(-1) }

    DisposableEffect(Unit) {
        onDispose { vm.releaseSensor(sessionId) }
    }

    // Flipping linkWanted off cancels this effect, which cancels the sample collector.
    LaunchedEffect(abnormal, btPermission.granted, retryKey, linkWanted) {
        if (!linkWanted) return@LaunchedEffect
        live = null
        samples = emptyList()
        val ok = vm.prepareMotorSensor(mockAbnormal = abnormal)
        connected = ok
        sessionId = vm.sensorSessionId
        if (!ok && vm.sensorStage == SensorStage.PERMISSION_REQUIRED) {
            btPermission.request()
            return@LaunchedEffect
        }
        if (!ok) return@LaunchedEffect
        vm.sensorRepository.samples().collect { sample ->
            live = sample
            samples = (samples + sample).takeLast(120)
        }
    }

    val score = motorScore(samples)
    val meanDiff = meanFinite(samples.map { it.diffDeg })
    val peakDiff = samples.map { it.diffDeg }.filter { it.isFinite() }.maxOrNull() ?: Float.NaN

    ScreenScaffold(
        title = "Motor test",
        onBack = { nav.popBackStack() },
        actions = { SpeakButton("Hold both arms out, palms up, and close your eyes for ten seconds.", vm) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Keep both arms raised")
            ConnectionStatusBanner(stage = vm.sensorStage, address = vm.sensorAddress)
            if (vm.sensorRepository.isMock && vm.sensorStage == SensorStage.MOCK) MockBadge()

            val streaming = vm.isSensorStreaming
            ArmAngleReadout(if (streaming) live else null)
            ArmCueControls(vm, connected && streaming)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (vm.sensorStage == SensorStage.LIVE && vm.sensorAddress.isNotBlank()) {
                        InfoRow("Board", "StrokeSense  ${vm.sensorAddress}")
                    }
                    InfoRow("Asymmetry", "${(score * 100).toInt()}%")
                    ScoreBar(score)
                    InfoRow("Mean difference", fmtDeg(if (streaming) meanDiff else Float.NaN))
                    InfoRow("Peak difference", fmtDeg(if (streaming) peakDiff else Float.NaN))
                    InfoRow("Baseline diff", fmtDeg(vm.motorBaseline?.diffDeg ?: Float.NaN))
                    SensorLinkControls(
                        vm = vm,
                        onReconnect = {
                            linkWanted = true
                            retryKey++
                        },
                        onDisconnect = {
                            vm.disconnectSensor()
                            linkWanted = false
                            connected = false
                            live = null
                            samples = emptyList()
                        },
                    )
                }
            }

            LiveChart(
                values = samples.map { it.leftDeg }.filter { it.isFinite() },
                secondary = samples.map { it.rightDeg }.filter { it.isFinite() },
                tertiary = samples.map { it.diffDeg }.filter { it.isFinite() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )

            if (vm.sensorRepository.isMock) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Simulate uneven arm raise")
                    Switch(checked = abnormal, onCheckedChange = { abnormal = it })
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.submit(
                        ModuleResult(
                            type = ModuleType.MOTOR,
                            score = score,
                            metrics = listOf(
                                Metric("Left arm", fmtDeg(live?.leftDeg ?: meanFinite(samples.map { it.leftDeg })), 0f),
                                Metric("Right arm", fmtDeg(live?.rightDeg ?: meanFinite(samples.map { it.rightDeg })), 0f),
                                Metric("Angle difference", fmtDeg(meanDiff), score),
                                Metric("Peak difference", fmtDeg(peakDiff), score),
                            ),
                            summary = if (score >= 0.33f) {
                                "Uneven arm raise detected"
                            } else {
                                "Arm angles matched"
                            },
                            usedMockData = vm.sensorRepository.isMock,
                        ),
                    )
                    nav.navigate(Routes.RESULTS)
                },
            ) {
                Text("Complete motor test")
            }

            ArmPoseSection(if (streaming) live else null)
        }
    }
}
