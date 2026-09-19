package com.hackmit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.BuildConfig
import com.hackmit.app.alert.AlertConfig
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()

    val storedKey by vm.settingsStore.deepgramKey.collectAsState(initial = "")
    val storedMock by vm.settingsStore.mockSensors.collectAsState(initial = true)
    val storedMac by vm.settingsStore.sensorMac.collectAsState(initial = "")
    val storedAlertConfig by vm.settingsStore.alertConfig.collectAsState(initial = AlertConfig())

    var keyInput by remember(storedKey) { mutableStateOf(storedKey) }
    var mock by remember(storedMock) { mutableStateOf(storedMock) }
    var address by remember(storedMac) { mutableStateOf(storedMac) }
    var transport by remember { mutableStateOf(SensorTransport.MOCK) }
    var gatewayUrl by remember(storedAlertConfig) { mutableStateOf(storedAlertConfig.gatewayUrl) }
    var gatewayToken by remember(storedAlertConfig) { mutableStateOf(storedAlertConfig.gatewayToken) }
    var trustedContactName by remember(storedAlertConfig) {
        mutableStateOf(storedAlertConfig.trustedContactName)
    }
    var trustedContactPhone by remember(storedAlertConfig) {
        mutableStateOf(storedAlertConfig.trustedContactPhone)
    }
    var emergencyNumber by remember(storedAlertConfig) { mutableStateOf(storedAlertConfig.emergencyNumber) }

    ScreenScaffold(title = "Settings", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Deepgram", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { scope.launch { vm.settingsStore.setDeepgramKey(keyInput) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save key")
                    }
                    Text(
                        "In production, proxy audio through a backend so the key never ships in the APK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Care alerts (Linq)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = gatewayUrl,
                        onValueChange = { gatewayUrl = it },
                        label = { Text("Alert gateway URL") },
                        placeholder = { Text("https://api.example.com/v1/stroke-alerts") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gatewayToken,
                        onValueChange = { gatewayToken = it },
                        label = { Text("Alert gateway token (demo only)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = trustedContactName,
                        onValueChange = { trustedContactName = it },
                        label = { Text("Trusted contact name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = trustedContactPhone,
                        onValueChange = { trustedContactPhone = it },
                        label = { Text("Trusted contact phone (E.164)") },
                        placeholder = { Text("+15551234567") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = emergencyNumber,
                        onValueChange = { emergencyNumber = it },
                        label = { Text("Local emergency number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                vm.settingsStore.setAlertConfig(
                                    AlertConfig(
                                        gatewayUrl = gatewayUrl,
                                        gatewayToken = gatewayToken,
                                        trustedContactName = trustedContactName,
                                        trustedContactPhone = trustedContactPhone,
                                        emergencyNumber = emergencyNumber,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save alert settings")
                    }
                    Text(
                        "The app sends only a short screening summary to your gateway. " +
                            "Keep the Linq integration token on that server, not on this phone. " +
                            "A gateway token is only for the local demo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Sensor source", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Use mock sensors")
                        Switch(
                            checked = mock,
                            onCheckedChange = {
                                mock = it
                                scope.launch { vm.settingsStore.setMockSensors(it) }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SensorTransport.entries.forEach { option ->
                            FilterChip(
                                selected = transport == option,
                                onClick = { transport = option },
                                label = { Text(option.name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("MAC / endpoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                vm.settingsStore.setSensorMac(address)
                                vm.settingsStore.setSensorTransport(transport.name)
                            }
                            vm.sensorRepository.select(transport, address)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Apply sensor source")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    InfoRow("App", BuildConfig.APPLICATION_ID)
                    InfoRow("Version", BuildConfig.VERSION_NAME)
                    InfoRow("Build type", BuildConfig.BUILD_TYPE)
                }
            }
        }
    }
}
