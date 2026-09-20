package com.hackmit.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hackmit.app.audio.Tts
import com.hackmit.app.data.SettingsStore
import com.hackmit.app.ui.AssessmentViewModel
import kotlinx.coroutines.launch

/** Plays [text] via the local gateway TTS when the speaker icon is tapped. */
@Composable
fun SpeakButton(text: String, vm: AssessmentViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    IconButton(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                error = null
                Tts.speak(context, vm.settingsStore, text) { error = it }
                busy = false
            }
        },
    ) {
        Icon(Icons.Filled.VolumeUp, contentDescription = "Speak instructions")
    }
    if (error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(8.dp))
            Text(error!!, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

/** Row of [text] plus its speak button. */
@Composable
fun SpeakableRow(text: String, vm: AssessmentViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        SpeakButton(text, vm)
    }
}