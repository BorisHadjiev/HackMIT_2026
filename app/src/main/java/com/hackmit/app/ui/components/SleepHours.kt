package com.hackmit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.hackmit.app.sensor.SleepWindow
import com.hackmit.app.ui.AssessmentViewModel
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Quiet hours editor. The STM32 side has no clock, so the window is pushed to the UNO Q
 * Linux bridge, which refuses to start a cue — and stops a running one — while inside
 * it. Times are local wall clock and a start after the end means overnight.
 */
@Composable
fun SleepHoursEditor(vm: AssessmentViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val stored by vm.settingsStore.sleepHours.collectAsState(initial = SleepWindow())
    var startText by remember(stored) { mutableStateOf(stored.start) }
    var endText by remember(stored) { mutableStateOf(stored.end) }
    var quietOn by remember(stored) { mutableStateOf(stored.enabled) }
    var status by remember { mutableStateOf("") }

    val nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sleep hours (no buzzing)")
            Switch(checked = quietOn, onCheckedChange = { quietOn = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it },
                label = { Text("Sleep from") },
                placeholder = { Text("22:00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it },
                label = { Text("Wake at") },
                placeholder = { Text("07:00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val window = SleepWindow(enabled = quietOn, start = startText, end = endText)
                if (quietOn && !window.valid) {
                    status = "Use HH:MM, e.g. 22:00 to 07:00"
                    return@Button
                }
                scope.launch {
                    val sent = vm.setSleepWindow(window)
                    status = when {
                        !sent -> "Saved — the board gets it on the next connect"
                        quietOn -> "Board stays quiet ${window.label}"
                        else -> "Sleep hours off on the board"
                    }
                }
            },
        ) { Text("Send sleep hours") }
        if (stored.contains(nowMinutes)) {
            Text(
                "Inside sleep hours now — the board will not buzz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
