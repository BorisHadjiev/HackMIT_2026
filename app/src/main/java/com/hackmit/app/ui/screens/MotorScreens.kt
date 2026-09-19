package com.hackmit.app.ui.screens

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.domain.Metric
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.MotorBaseline
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.LiveChart
import com.hackmit.app.ui.components.MockBadge
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import kotlinx.coroutines.flow.take

private fun baselineOf(values: List<Float>): MotorBaseline {
    if (values.isEmpty()) return MotorBaseline(9.81f, 0f)
    val mean = values.average().toFloat()
    val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
    return MotorBaseline(mean, variance)
}

private fun motorScore(values: List<Float>): Float {
    if (values.size < 5) return 0f
    val drift = (values.max() - values.min()).coerceAtLeast(0f)
    val mean = values.average().toFloat()
    val tremor = values.map { (it - mean) * (it - mean) }.average().toFloat()
    return ((drift / 4f) * 0.5f + (tremor / 2.5f) * 0.5f).coerceIn(0f, 1f)
}

@Composable
fun MotorCalibrationScreen(vm: AssessmentViewModel, nav: NavController) {
    var collecting by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<Float>>(emptyList()) }
    var baseline by remember { mutableStateOf<MotorBaseline?>(null) }

    LaunchedEffect(Unit) {
        vm.sensorRepository.select(SensorTransport.MOCK, mockAbnormal = false)
    }

    LaunchedEffect(collecting) {
        if (collecting) {
            samples = emptyList()
            vm.sensorRepository.samples().take(60).collect { sample ->
                samples = (samples + sample.accelMagnitude).takeLast(80)
            }
            baseline = baselineOf(samples)
            vm.motorBaseline = baseline
            collecting = false
        }
    }

    ScreenScaffold(title = "Motor calibration", onBack = { nav.popBackStack() }) { padding ->
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
                Text("Hold the sensor still")
                MockBadge()
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfoRow("Source", vm.sensorRepository.current.displayName)
                    InfoRow("Status", if (baseline != null) "Calibrated" else "Not calibrated")
                    InfoRow("Baseline magnitude", String.format("%.2f", baseline?.meanMagnitude ?: 0f))
                    InfoRow("Baseline tremor", String.format("%.3f", baseline?.tremorEnergy ?: 0f))
                }
            }

            LiveChart(
                values = samples,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )

            Button(
                enabled = !collecting,
                modifier = Modifier.fillMaxWidth(),
                onClick = { collecting = true },
            ) {
                Text(if (collecting) "Recording baseline\u2026" else "Record baseline (3s)")
            }

            Button(
                enabled = baseline != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { nav.navigate(Routes.MOTOR_TEST) },
            ) {
                Text("Continue to motor test")
            }

            Text(
                "TODO: replace MockSensorSource with BluetoothSppSource / BleSensorSource / WifiSensorSource.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MotorTestScreen(vm: AssessmentViewModel, nav: NavController) {
    var abnormal by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(abnormal) {
        vm.sensorRepository.select(SensorTransport.MOCK, mockAbnormal = abnormal)
        samples = emptyList()
        vm.sensorRepository.samples().collect { sample ->
            samples = (samples + sample.accelMagnitude).takeLast(120)
        }
    }

    val score = motorScore(samples)

    ScreenScaffold(title = "Motor test", onBack = { nav.popBackStack() }) { padding ->
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
                Text("Raise both arms and hold")
                MockBadge()
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoRow("Abnormality", "${(score * 100).toInt()}%")
                    ScoreBar(score)
                    InfoRow("Samples", samples.size.toString())
                    InfoRow("Drift", String.format("%.2f", samples.maxOrNull()?.minus(samples.minOrNull() ?: 0f) ?: 0f))
                    InfoRow("Baseline tremor", String.format("%.3f", vm.motorBaseline?.tremorEnergy ?: 0f))
                }
            }

            LiveChart(
                values = samples,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Simulate arm drift / tremor")
                Switch(checked = abnormal, onCheckedChange = { abnormal = it })
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.submit(
                        ModuleResult(
                            type = ModuleType.MOTOR,
                            score = score,
                            metrics = listOf(
                                Metric("Drift", String.format("%.2f", samples.maxOrNull()?.minus(samples.minOrNull() ?: 0f) ?: 0f), score),
                                Metric("Tremor energy", String.format("%.3f", vm.motorBaseline?.tremorEnergy ?: 0f), 0f),
                                Metric("Samples", samples.size.toString(), 0f),
                            ),
                            summary = if (score >= 0.33f) {
                                "Arm drift / tremor detected"
                            } else {
                                "Motor readings stable"
                            },
                        ),
                    )
                    nav.navigate(Routes.RESULTS)
                },
            ) {
                Text("Complete motor test")
            }
        }
    }
}
