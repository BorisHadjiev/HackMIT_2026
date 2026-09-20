package com.hackmit.app.ui.screens

import android.Manifest
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.domain.Metric
import com.hackmit.app.domain.ModuleResult
import com.hackmit.app.domain.ModuleType
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.Routes
import com.hackmit.app.ui.components.CameraPreview
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScoreBar
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.SpeakButton
import com.hackmit.app.ui.components.rememberPermissionState
import com.hackmit.app.video.AsymmetryCalculator
import com.hackmit.app.video.FaceAnalysisController
import com.hackmit.app.video.FaceFrame
import kotlinx.coroutines.delay

@Composable
private fun CameraBox(granted: Boolean, onFrame: (ImageProxy) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            CameraPreview(Modifier.fillMaxSize(), onFrame = onFrame)
        } else {
            Text(
                "Camera permission required",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ServerStatusRow(controller: FaceAnalysisController) {
    InfoRow(
        "Face server",
        when {
            controller.serverOk -> "Connected (gx10)"
            else -> "Awaiting server…"
        },
        valueColor = if (controller.serverOk) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun FaceCalibrationScreen(vm: AssessmentViewModel, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    val controller = remember { FaceAnalysisController(context, vm.settingsStore, scope) }
    val frame by controller.frame

    val steps = listOf("Neutral face", "Big smile", "Raise your eyebrows", "Close your eyes tightly")
    var step by remember { mutableIntStateOf(0) }
    val samples = remember { mutableListOf<Float>() }

    LaunchedEffect(Unit) {
        controller.warmUp()
        while (true) {
            if (frame.detected) {
                samples.add(frame.asymmetry)
                if (samples.size > 40) samples.removeAt(0)
            }
            delay(120)
        }
    }

    ScreenScaffold(
        title = "Face calibration",
        onBack = { nav.popBackStack() },
        actions = { SpeakButton("Look straight at the camera and keep your face relaxed.", vm) },
    ) { padding ->
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
                Text("Step ${step + 1} of ${steps.size}")
                Text("Analyzed on gx10", style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(
                progress = { (step + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
            CameraBox(granted = permission.granted, onFrame = controller::handle, modifier = Modifier.fillMaxWidth().height(280.dp))
            Text(steps[step], style = MaterialTheme.typography.headlineSmall)
            Text(
                "Hold the pose while the app records your neutral baseline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ServerStatusRow(controller)
            if (frame.detected) {
                InfoRow("Live asymmetry", "${(frame.asymmetry * 100).toInt()}%")
            }

            if (!permission.granted) {
                Button(onClick = permission.request, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant camera access")
                }
            }

            Button(
                enabled = permission.granted,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (step < steps.lastIndex) {
                        step++
                    } else {
                        vm.faceBaseline = if (samples.isEmpty()) 0f else samples.sorted()[samples.size / 2]
                        nav.navigate(Routes.FACE_TEST)
                    }
                },
            ) {
                Text(if (step < steps.lastIndex) "Next pose" else "Finish calibration")
            }
        }
    }
}

@Composable
fun FaceTestScreen(vm: AssessmentViewModel, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    val controller = remember { FaceAnalysisController(context, vm.settingsStore, scope) }
    val frame by controller.frame

    var peak by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        controller.warmUp()
        while (true) {
            val f = controller.frame.value
            if (f.detected) peak = maxOf(peak, f.asymmetry)
            delay(120)
        }
    }

    val baseline = vm.faceBaseline ?: 0f

    ScreenScaffold(
        title = "Face test",
        onBack = { nav.popBackStack() },
        actions = { SpeakButton("Smile and show your teeth. Then frown. Check for drooping on one side.", vm) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Smile and show your teeth", style = MaterialTheme.typography.titleMedium)
            CameraBox(granted = permission.granted, onFrame = controller::handle, modifier = Modifier.fillMaxWidth().height(260.dp))
            ServerStatusRow(controller)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoRow("Asymmetry index", "${(frame.asymmetry * 100).toInt()}%")
                    ScoreBar(frame.asymmetry)
                    InfoRow("Peak during test", "${(peak * 100).toInt()}%")
                    InfoRow("Mouth droop", "${(frame.mouthDroop * 100).toInt()}%")
                    InfoRow("Eye asymmetry", "${(frame.eyeAsymmetry * 100).toInt()}%")
                    InfoRow("Calibrated baseline", "${(baseline * 100).toInt()}%")
                    InfoRow("Change vs baseline", "%+d".format(((peak - baseline) * 100).toInt()))
                }
            }

            if (!permission.granted) {
                Button(onClick = permission.request, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant camera access")
                }
            }

            Button(
                enabled = permission.granted,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val score = peak.coerceIn(0f, 1f)
                    vm.submit(
                        ModuleResult(
                            type = ModuleType.FACE,
                            score = score,
                            metrics = listOf(
                                Metric("Peak asymmetry", "${(score * 100).toInt()}%", score),
                                Metric("Mouth droop", "${(frame.mouthDroop * 100).toInt()}%", frame.mouthDroop),
                                Metric("Eye asymmetry", "${(frame.eyeAsymmetry * 100).toInt()}%", frame.eyeAsymmetry),
                                Metric("Baseline", "${(baseline * 100).toInt()}%", baseline),
                                Metric("Change vs baseline", "%+d".format(((score - baseline) * 100).toInt()), (score - baseline).coerceIn(0f, 1f)),
                            ),
                            summary = if (score >= AsymmetryCalculator.LOGISTIC_THRESHOLD) {
                                "Facial asymmetry detected"
                            } else {
                                "Face appears symmetric"
                            },
                        ),
                    )
                    nav.navigate(Routes.SPEECH_CALIB)
                },
            ) {
                Text("Complete face test")
            }
        }
    }
}